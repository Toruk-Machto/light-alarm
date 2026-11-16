package sg.lightalarm;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;

final class AlarmStore {
    static final long MIN = 60_000L, DAY = 24 * 60 * MIN;
    static final int MAIN = 0, PRE = 1, SNOOZE = 2, RETRY = 3, BACKUP = 4;
    static final int TAP = 0, HOLD = 1, SLIDE = 2;
    final ArrayList<Alarm> alarms = new ArrayList<>();
    final ArrayList<Event> pending = new ArrayList<>(), ringing = new ArrayList<>();
    final ArrayList<Log> history = new ArrayList<>();
    private final SharedPreferences prefs;

    static final class Alarm {
        String id = UUID.randomUUID().toString(), label = "", note = "";
        boolean enabled = true, reminder, hide;
        int tone = 1, vibration = 1, fade = 0, pre = 0, snooze = 5, limit = 3;
        int dismiss = HOLD, seconds = 120, retry = 0, backup = 0;
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
            a.snooze = obj.optInt("snooze", 5); a.limit = obj.optInt("limit", 3);
            a.dismiss = obj.optInt("dismiss", HOLD); a.seconds = obj.optInt("seconds", 120);
            a.retry = obj.optInt("retry"); a.backup = obj.optInt("backup");
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
                    "fade", fade, "pre", pre, "snooze", snooze, "limit", limit, "dismiss", dismiss,
                    "seconds", seconds, "retry", retry, "backup", backup, "kind", s.kind,
                    "minute", s.minute, "monthDay", s.monthDay, "ordinal", s.ordinal,
                    "weekday", s.weekday, "date", s.date, "end", s.end, "zone", s.zone,
                    "lastDay", s.lastDay, "week", days);
        }
    }

    record Event(String alarm, int type, long at, long group, String day, int snoozes,
                 long deadline, boolean retried) {
        Event move(int phase, long time, int count, boolean wasRetried) {
            return new Event(alarm, phase, time, group, day, count, 0, wasRetried);
        }
        Event active(long deadline, int count, boolean wasRetried) {
            return new Event(alarm, type, at, group, day, count, deadline, wasRetried);
        }
        static Event from(JSONObject o) {
            return new Event(o.optString("alarm"), o.optInt("type"), o.optLong("at"),
                    o.optLong("group"), o.optString("day"), o.optInt("snoozes"),
                    o.optLong("deadline"), o.optBoolean("retried"));
        }
        JSONObject json() {
            return object("alarm", alarm, "type", type, "at", at, "group", group,
                    "day", day, "snoozes", snoozes, "deadline", deadline, "retried", retried);
        }
        boolean same(Event event) { return alarm.equals(event.alarm) && group == event.group; }
        String key() { return alarm + "/" + group + "/" + type + "/" + at; }
        String phase() {
            return switch (type) {
                case PRE -> "Gentle pre-alarm";
                case SNOOZE -> "Snoozed alarm";
                case RETRY -> "Retry";
                case BACKUP -> "Backup alarm";
                default -> "";
            };
        }
    }

    record Log(long at, String label, String action) {
        JSONObject json() { return object("at", at, "label", label, "action", action); }
    }

    AlarmStore(Context context) {
        prefs = context.getSharedPreferences("light_alarm", Context.MODE_PRIVATE);
        JSONObject root;
        try { root = new JSONObject(prefs.getString("state", "{}")); }
        catch (JSONException e) { throw new IllegalStateException("Could not read saved alarms", e); }
        for (JSONObject o : objects(root, "alarms")) alarms.add(Alarm.from(o));
        for (JSONObject o : objects(root, "pending")) pending.add(Event.from(o));
        for (JSONObject o : objects(root, "ringing")) ringing.add(Event.from(o));
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

    void recover(long now, boolean boot, boolean wallChanged) {
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
        purge(now);
        for (Event ring : new ArrayList<>(ringing)) {
            if (ring.deadline > now) continue;
            ringing.remove(ring);
            if (ring.type == PRE) continue;
            log(ring, "Missed", now);
            Alarm a = find(ring.alarm);
            if (a != null && a.retry > 0 && !ring.retried && ring.type != BACKUP)
                pending.add(ring.move(RETRY, now + a.retry * MIN, ring.snoozes, true));
        }
        pending.sort(Comparator.comparingLong(Event::at));
        for (Event e : new ArrayList<>(pending)) {
            if (e.at > now) break;
            if (!pending.remove(e)) continue;
            Alarm a = find(e.alarm);
            if (a == null) continue;
            if (e.type == MAIN) {
                a.schedule.lastDay = e.day;
                if (a.backup > 0 && e.at + a.backup * MIN > now)
                    pending.add(e.move(BACKUP, e.at + a.backup * MIN, 0, false));
                plan(a, now);
            }
            if (e.type == PRE && (now >= e.group || now - e.at > 30_000)) continue;
            if (e.type != PRE && now - e.at > 2 * MIN) { log(e, "Missed", now); continue; }
            int snoozes = e.snoozes;
            boolean retried = e.retried;
            for (Event old : ringing) {
                if (!old.same(e)) continue;
                snoozes = Math.max(snoozes, old.snoozes);
                retried |= old.retried;
            }
            if (e.type == BACKUP || e.type == RETRY) {
                for (Event scheduled : pending) {
                    if (!scheduled.same(e)) continue;
                    snoozes = Math.max(snoozes, scheduled.snoozes);
                    retried |= scheduled.retried;
                }
                pending.removeIf(x -> x.same(e) && (x.type == RETRY || x.type == SNOOZE));
            }
            ringing.removeIf(x -> x.same(e));
            ringing.add(e.active(now + (e.type == PRE ? 8 : a.seconds) * 1000L, snoozes, retried));
            log(e, "Rang", now);
        }
    }

    void respond(String key, String action, long now) {
        for (Event e : new ArrayList<>(ringing)) {
            Alarm a = find(e.alarm);
            if (a == null || !e.key().equals(key)) continue;
            boolean snooze = action.equals("snooze");
            if (snooze && (e.type == PRE || e.snoozes >= a.limit || a.snooze <= 0)) return;
            ringing.remove(e);
            if (e.type != PRE) {
                pending.removeIf(p -> p.same(e) && (!snooze || p.type != BACKUP));
                if (snooze) pending.add(e.move(SNOOZE, now + a.snooze * MIN, e.snoozes + 1, e.retried));
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
