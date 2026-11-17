package sg.lightalarm;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.provider.Settings;
import org.json.*;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;

final class AlarmStore {
    static final long MIN = 60_000L, DAY = 24 * 60 * MIN;
    static final int MAIN = 0, PRE = 1, SNOOZE = 2, RETRY = 3, BACKUP = 4; // 3/4 read legacy saved events only
    static final int TAP = 0, HOLD = 1, SLIDE = 2;
    final ArrayList<Alarm> alarms = new ArrayList<>();
    final ArrayList<Event> pending = new ArrayList<>(), ringing = new ArrayList<>();
    final ArrayList<Log> history = new ArrayList<>();
    private final SharedPreferences prefs;
    final int bootCount;

    static final class Alarm {
        String id = UUID.randomUUID().toString(), label = "", note = "";
        boolean enabled = true, reminder, hide;
        int tone = 1, vibration = 1, fade = 0, pre = 0, followupGap = 5, followupCount = 3;
        int seconds = 120, volume = 100;
        boolean repeatUnanswered = true, reviewFollowups;
        final Schedule schedule = new Schedule();

        String title() { return label.isBlank() ? (reminder ? "Reminder" : "Alarm") : label; }

        static Alarm from(JSONObject obj) {
            Alarm a = new Alarm();
            a.id = obj.optString("id", a.id);
            a.label = obj.optString("label");
            a.note = obj.optString("note");
            a.enabled = obj.optBoolean("enabled", true);
            a.reminder = obj.optBoolean("reminder");
            a.hide = obj.optBoolean("hide");
            a.tone = obj.optInt("tone", 1); a.vibration = obj.optInt("vibration", 1);
            a.fade = obj.optInt("fade"); a.pre = obj.optInt("pre");
            a.seconds = obj.optInt("seconds", 120);
            a.volume = Math.max(10, Math.min(100, obj.optInt("volume", 100)));
            boolean old = !obj.has("followupCount");
            int oldGap = obj.optInt("snooze", 5), oldCount = obj.optInt("limit", 3);
            int retry = obj.optInt("retry"), backup = obj.optInt("backup");
            a.followupGap = Math.max(1, Math.min(60, obj.optInt("followupGap", oldGap > 0 ? oldGap : 5)));
            a.followupCount = Math.max(0, Math.min(15, obj.optInt("followupCount", oldGap > 0 ? oldCount : 0)));
            a.repeatUnanswered = obj.optBoolean("repeatUnanswered", old && (retry > 0 || backup > 0));
            a.reviewFollowups = obj.optBoolean("reviewFollowups", old && (retry > 0 || backup > 0));
            if (old && a.reviewFollowups) {
                if (a.followupCount == 0) a.followupCount = 1;
                if (oldGap <= 0) a.followupGap = Math.max(1, Math.min(60, retry > 0 ? retry : backup));
            }
            if (old) a.followupGap = Math.max(a.followupGap, (a.seconds + 59) / 60);
            Schedule s = a.schedule;
            s.kind = obj.optInt("kind");
            s.minute = obj.optInt("minute", 420);
            s.monthDay = obj.optInt("monthDay", 1);
            s.ordinal = obj.optInt("ordinal", 1);
            s.weekday = obj.optInt("weekday", 1);
            s.date = obj.optString("date", s.date);
            s.zone = obj.optString("zone");
            s.end = obj.optString("end");
            s.lastDay = obj.optString("lastDay");
            JSONArray days = obj.optJSONArray("week");
            if (days != null) for (int i = 0; i < 7; i++) s.week[i] = days.optInt(i, -1);
            return a;
        }

        JSONObject json() {
            Schedule s = schedule;
            JSONArray days = new JSONArray();
            for (int time : s.week) days.put(time);
            return object("id", id, "label", label, "note", note, "enabled", enabled,
                    "reminder", reminder, "hide", hide, "tone", tone, "vibration", vibration,
                    "fade", fade, "pre", pre, "followupGap", followupGap, "followupCount", followupCount,
                    "seconds", seconds, "volume", volume, "repeatUnanswered", repeatUnanswered,
                    "reviewFollowups", reviewFollowups, "kind", s.kind,
                    "minute", s.minute, "monthDay", s.monthDay, "ordinal", s.ordinal,
                    "weekday", s.weekday, "date", s.date, "end", s.end, "zone", s.zone,
                    "lastDay", s.lastDay, "week", days);
        }
    }

    record Event(String alarm, int type, long at, long group, String day, int snoozes,
                 long deadline, boolean retried, long elapsedDeadline, int bootCount, long elapsedAt) {
        Event(String alarm, int type, long at, long group, String day, int snoozes,
              long deadline, boolean retried, long elapsedDeadline, int bootCount) {
            this(alarm, type, at, group, day, snoozes, deadline, retried, elapsedDeadline, bootCount, 0);
        }
        Event(String alarm, int type, long at, long group, String day, int snoozes,
              long deadline, boolean retried) {
            this(alarm, type, at, group, day, snoozes, deadline, retried, 0, -1);
        }
        Event move(int phase, long time, int count, boolean wasRetried) {
            return new Event(alarm, phase, time, group, day, count, 0, wasRetried);
        }
        Event active(long deadline, long elapsedDeadline, int bootCount, int count, boolean wasRetried) {
            return new Event(alarm, type, at, group, day, count, deadline, wasRetried, elapsedDeadline, bootCount, elapsedAt);
        }
        static Event from(JSONObject o) {
            return new Event(o.optString("alarm"), o.optInt("type"), o.optLong("at"),
                    o.optLong("group"), o.optString("day"), o.optInt("snoozes"),
                    o.optLong("deadline"), o.optBoolean("retried"),
                    o.optLong("elapsedDeadline"), o.optInt("bootCount", -1), o.optLong("elapsedAt"));
        }
        JSONObject json() {
            return object("alarm", alarm, "type", type, "at", at, "group", group,
                    "day", day, "snoozes", snoozes, "deadline", deadline, "retried", retried,
                    "elapsedDeadline", elapsedDeadline, "bootCount", bootCount, "elapsedAt", elapsedAt);
        }
        boolean same(Event event) { return alarm.equals(event.alarm) && group == event.group; }
        String key() { return alarm + "/" + group + "/" + type + "/" + at; }
        String phase() {
            return switch (type) {
                case PRE -> "Gentle pre-alarm";
                case SNOOZE -> "Follow-up " + snoozes;
                case RETRY -> "Follow-up (previous version)";
                case BACKUP -> "Follow-up (previous version)";
                default -> "";
            };
        }
    }

    record Log(long at, String label, String action) {
        JSONObject json() { return object("at", at, "label", label, "action", action); }
    }

    AlarmStore(Context context) {
        prefs = context.getSharedPreferences("light_alarm", Context.MODE_PRIVATE);
        bootCount = Settings.Global.getInt(context.getContentResolver(), Settings.Global.BOOT_COUNT, 0);
        JSONObject root;
        try { root = new JSONObject(prefs.getString("state", "{}")); }
        catch (JSONException e) { throw new IllegalStateException("Could not read saved alarms", e); }
        for (JSONObject o : objects(root, "alarms")) alarms.add(Alarm.from(o));
        for (JSONObject o : objects(root, "pending")) pending.add(Event.from(o));
        for (JSONObject o : objects(root, "ringing")) ringing.add(Event.from(o));
        // Upgrade active events saved by earlier versions to a bounded monotonic deadline.
        for (int i = 0; i < ringing.size(); i++) {
            Event event = ringing.get(i);
            if (event.elapsedDeadline > 0) continue;
            Alarm alarm = find(event.alarm);
            long duration = (event.type == PRE ? 8 : alarm == null ? 120 : alarm.seconds) * 1000L;
            long remaining = Math.max(0, Math.min(duration, event.deadline - System.currentTimeMillis()));
            ringing.set(i, event.active(event.deadline, SystemClock.elapsedRealtime() + remaining,
                    bootCount, event.snoozes, event.retried));
        }
        for (JSONObject o : objects(root, "history"))
            history.add(new Log(o.optLong("at"), o.optString("label"), o.optString("action")));
    }

    Alarm find(String id) {
        for (Alarm a : alarms) if (a.id.equals(id)) return a;
        return null;
    }

    void save() {
        JSONArray a = new JSONArray(), p = new JSONArray(), r = new JSONArray(), h = new JSONArray();
        for (Alarm alarm : alarms) a.put(alarm.json());
        for (Event e : pending) p.put(e.json());
        for (Event e : ringing) r.put(e.json());
        for (Log log : history) h.put(log.json());
        if (!prefs.edit().putString("state", object("alarms", a, "pending", p,
                "ringing", r, "history", h).toString()).commit())
            throw new IllegalStateException("Could not save alarm");
    }

    void purge(long now) { history.removeIf(log -> log.at <= now - DAY); }

    void put(Alarm alarm, long now) {
        alarms.removeIf(a -> a.id.equals(alarm.id));
        alarms.add(alarm);
        cancel(alarm.id);
        plan(alarm, now);
    }

    void cancel(String id) {
        pending.removeIf(e -> e.alarm.equals(id));
        ringing.removeIf(e -> e.alarm.equals(id));
    }

    void plan(Alarm alarm, long now) {
        if (!alarm.enabled) return;
        Schedule.Next next = alarm.schedule.next(now, ZoneId.systemDefault());
        if (next == null) { alarm.enabled = false; return; }
        long at = next.at();
        pending.add(new Event(alarm.id, MAIN, at, at, next.day(), 0, 0, false));
        long pre = at - alarm.pre * MIN;
        if (alarm.pre > 0 && pre > now)
            pending.add(new Event(alarm.id, PRE, pre, at, next.day(), 0, 0, false));
    }

    long dueAt(Event e, long now, long elapsedNow) {
        return e.elapsedAt > 0 && e.bootCount == bootCount ? now + e.elapsedAt - elapsedNow : e.at;
    }

    private void followups(Alarm alarm, Event source, long now, long elapsedNow) {
        pending.removeIf(e -> e.same(source) && e.type != MAIN && e.type != PRE);
        for (int number = source.snoozes + 1; number <= alarm.followupCount; number++) {
            long gap = (number - source.snoozes) * alarm.followupGap * MIN;
            pending.add(new Event(alarm.id, SNOOZE, now + gap, source.group, source.day,
                    number, 0, true, 0, bootCount, elapsedNow + gap));
        }
    }

    void recover(long now, boolean boot, boolean wallChanged) {
        long elapsed = SystemClock.elapsedRealtime();
        // Keep follow-up intervals stable across clock changes and process restarts.
        for (int i = 0; i < pending.size(); i++) {
            Event e = pending.get(i);
            if (e.type == MAIN || e.type == PRE) continue;
            long at = dueAt(e, now, elapsed);
            pending.set(i, new Event(e.alarm, e.type, at, e.group, e.day, e.snoozes,
                    0, e.retried, 0, bootCount, elapsed + at - now));
        }
        if (boot) {
            for (Event e : ringing) log(e, "Missed", now);
            ringing.clear();
        }
        if (!wallChanged) for (Event e : pending) {
            if (e.type != PRE && e.at <= now) log(e, "Missed", now);
        }
        pending.removeIf(e -> e.type == MAIN || e.type == PRE || e.at <= now);
        for (Alarm a : alarms) plan(a, now);
        purge(now);
    }

    void tick(long now) {
        tick(now, SystemClock.elapsedRealtime());
    }

    void tick(long now, long elapsedNow) {
        purge(now);
        for (Event ring : new ArrayList<>(ringing)) {
            if (ring.bootCount == bootCount && ring.elapsedDeadline > elapsedNow) continue;
            ringing.remove(ring);
            if (ring.type == PRE) continue;
            log(ring, "Missed", now);
            // The complete sequence is already scheduled, so expiry needs no retry wake lock.
        }
        pending.sort(Comparator.comparingLong(e -> dueAt(e, now, elapsedNow)));
        for (Event e : new ArrayList<>(pending)) {
            long due = dueAt(e, now, elapsedNow);
            if (due > now) break;
            if (!pending.remove(e)) continue;
            Alarm a = find(e.alarm);
            if (a == null) continue;
            if (e.type == MAIN) {
                a.schedule.lastDay = e.day;
                if (a.repeatUnanswered && a.followupCount > 0 && now - due <= 2 * MIN)
                    followups(a, e, now, elapsedNow);
                plan(a, now);
            }
            if (e.type == PRE && (now >= e.group || now - e.at > 30_000)) continue;
            if (e.type != PRE && now - due > 2 * MIN) { log(e, "Missed", now); continue; }
            int snoozes = e.snoozes;
            boolean retried = e.retried;
            for (Event old : ringing) {
                if (!old.same(e)) continue;
                snoozes = Math.max(snoozes, old.snoozes);
                retried |= old.retried;
            }
            ringing.removeIf(x -> x.same(e));
            long duration = (e.type == PRE ? 8 : a.seconds) * 1000L;
            Event shown = new Event(e.alarm, e.type, due, e.group, e.day, snoozes, 0, retried);
            ringing.add(shown.active(now + duration, elapsedNow + duration, bootCount, snoozes, retried));
            log(e, "Rang", now);
        }
    }

    void respond(String key, String action, long now) {
        respond(key, action, now, SystemClock.elapsedRealtime());
    }

    void respond(String key, String action, long now, long elapsedNow) {
        for (Event e : new ArrayList<>(ringing)) {
            Alarm a = find(e.alarm);
            if (a == null || !e.key().equals(key)) continue;
            boolean snooze = action.equals("snooze");
            if (snooze && (e.type == PRE || e.snoozes >= a.followupCount || a.followupGap <= 0)) return;
            ringing.remove(e);
            if (e.type != PRE) {
                pending.removeIf(p -> p.same(e));
                if (snooze) followups(a, e, now, elapsedNow);
            }
            log(e, snooze ? "Snoozed" : action.equals("skip") ? "Skipped" :
                    a.reminder ? "Done" : "Dismissed", now);
            return;
        }
    }

    private void log(Event e, String action, long now) {
        Alarm a = find(e.alarm);
        if (a != null) history.add(0, new Log(now, a.title(),
                e.phase().isEmpty() ? action : action + " · " + e.phase()));
    }

    static JSONObject object(Object... pairs) {
        JSONObject obj = new JSONObject();
        try {
            for (int i = 0; i < pairs.length; i += 2) obj.put((String)pairs[i], pairs[i + 1]);
        } catch (JSONException e) { throw new IllegalArgumentException(e); }
        return obj;
    }

    private static ArrayList<JSONObject> objects(JSONObject root, String key) {
        ArrayList<JSONObject> out = new ArrayList<>();
        JSONArray array = root.optJSONArray(key);
        if (array != null) for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj != null) out.add(obj);
        }
        return out;
    }
}
