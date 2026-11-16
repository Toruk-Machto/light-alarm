package sg.lightalarm;

import android.app.*;
import android.content.*;
import android.os.Bundle;
import java.time.*;

public final class EngineChecks extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }

    @Override public void onStart() {
        Context test = new ContextWrapper(getTargetContext()) {
            @Override public SharedPreferences getSharedPreferences(String name, int mode) {
                return super.getSharedPreferences(name + "_test", mode);
            }
        };
        test.getSharedPreferences("light_alarm", Context.MODE_PRIVATE).edit().clear().commit();
        Bundle result = new Bundle();
        try {
            runChecks(test);
            result.putString("stream", "\nPASS: pre-alarm, overlapping alarms, snooze limit, backup cancellation, retry, persistence, boot recovery and 24-hour expiry\n");
            finish(Activity.RESULT_OK, result);
        } catch (Throwable error) {
            result.putString("stream", "\nFAIL: " + android.util.Log.getStackTraceString(error));
            finish(Activity.RESULT_CANCELED, result);
        }
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
        data.tick(main - 3 * minute);
        AlarmStore.Event pre = ring(data, a.id);
        check(pre.type() == AlarmStore.PRE, "Pre-alarm");
        data.respond(pre.key(), "dismiss", main - 3 * minute + 1000);
        data.tick(main);
        check(data.ringing.size() == 2, "Independent overlapping alarms");
        data.respond(ring(data, b.id).key(), "skip", main + 100);
        data.respond(ring(data, a.id).key(), "snooze", main + 200);
        check(data.pending.stream().anyMatch(e -> e.type() == AlarmStore.BACKUP), "Snooze keeps backup armed");
        data.tick(main + minute + 200);
        AlarmStore.Event snooze = ring(data, a.id);
        check(snooze.type() == AlarmStore.SNOOZE && snooze.snoozes() == 1, "Snooze count");
        data.respond(snooze.key(), "snooze", main + minute + 500);
        check(!data.ringing.isEmpty(), "Snooze cap");
        data.tick(main + minute + 21_000);
        data.tick(main + 2 * minute + 21_000);
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
