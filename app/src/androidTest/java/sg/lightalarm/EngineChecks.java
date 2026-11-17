package sg.lightalarm;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import java.time.*;

public final class EngineChecks extends Instrumentation {
    private Bundle arguments;
    @Override public void onCreate(Bundle arguments) { this.arguments = arguments; super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Context test = new ContextWrapper(getTargetContext()) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(name + "_test", mode);
            }
        };
        test.getSharedPreferences("light_alarm", Context.MODE_PRIVATE).edit().clear().commit();
        Bundle result = new Bundle();
        try {
            if (arguments != null && arguments.containsKey("live")) {
                liveCheck(Integer.parseInt(arguments.getString("live")));
                result.putString("stream", "\nScheduled a 20-second emulator alarm in 8 seconds.\n");
                finish(Activity.RESULT_OK, result);
                return;
            }
            runChecks(test);
            batteryChecks(test);
            result.putString("stream", "\nPASS: pre-alarm, overlapping alarms, snooze limit, backup cancellation, retry, persistence, boot recovery 24-hour expiry, clock changes, monotonic persistence, legacy upgrade, reboot expiry and finite vibration\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
    }

    private void liveCheck(int tone) {
        Context context = getTargetContext();
        context.getSharedPreferences("light_alarm", Context.MODE_PRIVATE).edit().clear().commit();
        AlarmStore data = new AlarmStore(context);
        AlarmStore.Alarm a = alarm(tone == 0 ? "Silent battery check" : "Locked-screen check");
        a.tone = tone; a.vibration = 1; a.seconds = 20; a.dismiss = AlarmStore.TAP;
        long at = System.currentTimeMillis() + 8_000;
        data.alarms.add(a);
        data.pending.add(new AlarmStore.Event(a.id, AlarmStore.MAIN, at, at, "2027-01-05", 0, 0, false));
        data.save();
        AlarmReceiver.schedule(context, data);
    }

    private void runChecks(Context context) {
        long minute = AlarmStore.MIN;
        long main = Instant.parse("2027-01-05T07:00:00Z").toEpochMilli();
        AlarmStore data = new AlarmStore(context);
        AlarmStore.Alarm a = alarm("Main");
        a.pre = 3; a.backup = 15; a.snooze = 1; a.limit = 1; a.retry = 1; a.seconds = 20;
        AlarmStore.Alarm b = alarm("Reminder");
        b.reminder = true;
        data.put(a, main - 10 * minute);
        data.put(b, main - 10 * minute);
        data.tick(main - 3 * minute, main - 3 * minute);
        AlarmStore.Event pre = ring(data, a.id);
        check(pre.type() == AlarmStore.PRE, "Pre-alarm");
        data.respond(pre.key(), "dismiss", main - 3 * minute + 1000);
        data.tick(main, main);
        check(data.ringing.size() == 2, "Independent overlapping alarms");
        data.respond(ring(data, b.id).key(), "skip", main + 100);
        data.respond(ring(data, a.id).key(), "snooze", main + 200);
        check(data.pending.stream().anyMatch(e -> e.type() == AlarmStore.BACKUP), "Snooze keeps backup armed");
        data.tick(main + minute + 200, main + minute + 200);
        AlarmStore.Event snooze = ring(data, a.id);
        check(snooze.type() == AlarmStore.SNOOZE && snooze.snoozes() == 1, "Snooze count");
        data.respond(snooze.key(), "snooze", main + minute + 500);
        check(!data.ringing.isEmpty(), "Snooze cap");
        data.tick(main + minute + 21_000, main + minute + 21_000);
        data.tick(main + 2 * minute + 21_000, main + 2 * minute + 21_000);
        AlarmStore.Event retry = ring(data, a.id);
        check(retry.type() == AlarmStore.RETRY, "Missed alarm retries");
        data.respond(retry.key(), "dismiss", main + 2 * minute + 23_000);
        check(data.pending.stream().noneMatch(e -> e.type() == AlarmStore.BACKUP), "Dismiss cancels backup");
        data.save();
        data = new AlarmStore(context);
        check(data.alarms.size() == 2 && data.history.size() >= 7, "Round trip preserves alarms and events");
        AlarmStore.Alarm future = alarm("After reboot");
        future.schedule.minute = 500;
        data.put(future, main);
        data.recover(main + 10 * minute, true, false);
        check(data.pending.stream().anyMatch(e -> e.alarm().equals(future.id) && e.type() == AlarmStore.MAIN), "Boot recovery");
        data.purge(main + AlarmStore.DAY + 5 * minute);
        check(data.history.isEmpty() && data.alarms.size() == 3, "History expires independently of alarm definitions");
    }

    private void batteryChecks(Context context) {
        context.getSharedPreferences("light_alarm", Context.MODE_PRIVATE).edit().clear().commit();
        long wall = System.currentTimeMillis(), elapsed = android.os.SystemClock.elapsedRealtime();
        AlarmStore data = new AlarmStore(context);
        AlarmStore.Alarm a = alarm("Monotonic test");
        a.seconds = 120;
        data.alarms.add(a);
        data.pending.add(new AlarmStore.Event(a.id, AlarmStore.MAIN, wall, wall, "2027-01-05", 0, 0, false));
        data.tick(wall, elapsed);
        check(data.ringing.size() == 1, "Ring starts");
        long deadline = ring(data, a.id).elapsedDeadline();
        check(deadline == elapsed + 120_000, "Two-minute elapsed deadline");
        data.tick(wall - 3_600_000, elapsed + 30_000);
        check(data.ringing.size() == 1, "Clock backward keeps remaining duration");
        data.tick(wall + 3_600_000, elapsed + 60_000);
        check(data.ringing.size() == 1, "Clock forward does not truncate ringing");
        data.save();
        data = new AlarmStore(context);
        check(ring(data, a.id).elapsedDeadline() == deadline, "Process restart preserves elapsed deadline");
        data.tick(wall - 3_600_000, elapsed + 120_000);
        check(data.ringing.isEmpty(), "Clock backward cannot extend ringing");
        data.ringing.add(new AlarmStore.Event(a.id, AlarmStore.MAIN, wall, wall, "2027-01-05", 0,
                wall + 120_000, false, elapsed + 120_000, data.bootCount - 1));
        data.tick(wall, elapsed);
        check(data.ringing.isEmpty(), "Old boot cannot revive an active ring");
        data.ringing.add(new AlarmStore.Event(a.id, AlarmStore.MAIN, wall, wall, "2027-01-05", 0,
                wall + 3_600_000, false));
        data.save();
        data = new AlarmStore(context);
        long remaining = ring(data, a.id).elapsedDeadline() - android.os.SystemClock.elapsedRealtime();
        check(remaining > 119_000 && remaining <= 120_000, "Legacy ring is capped at configured duration");
        for (int pattern = 1; pattern <= 3; pattern++) {
            check(java.util.Arrays.stream(Tone.waveform(pattern, 0, 600_000).times()).sum() == 600_000, "Finite full vibration " + pattern);
            check(java.util.Arrays.stream(Tone.waveform(pattern, 12_345, 30_000).times()).sum() == 17_655, "Resumed vibration " + pattern);
        }
        check(java.util.Arrays.equals(Tone.waveform(1, 400, 1100).times(), new long[]{50, 450, 200}), "Waveform clips both ends");
        check(java.util.Arrays.equals(Tone.waveform(1, 400, 1100).levels(), new int[]{-1, 0, -1}), "Vibration phase preserved");
        check(Tone.timedPattern(2, 0, 600_000) != null, "Platform accepts finite ten-minute pattern");
        context.getSharedPreferences("light_alarm", Context.MODE_PRIVATE).edit().clear().commit();
    }

    private AlarmStore.Alarm alarm(String label) {
        AlarmStore.Alarm a = new AlarmStore.Alarm();
        a.label = label;
        a.schedule.zone = "UTC";
        a.schedule.date = "2027-01-05";
        return a;
    }

    private AlarmStore.Event ring(AlarmStore data, String id) {
        return data.ringing.stream().filter(e -> e.alarm().equals(id)).findFirst().orElseThrow();
    }

    private void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
