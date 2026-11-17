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
            volumeChecks(getTargetContext());
            for (int n = 1; n < Tone.NAMES.length; n++) {
                final int sound = n;
                runOnMainSync(() -> { Tone tone = new Tone(); tone.play(sound, 0, 0); tone.close(); });
            }
            holdChecks();
            result.putString("stream", "\nPASS: shared time, follow-up sequence, count cap, resnooze, unanswered option, independent overlaps, persistence, migration, reboot, clock changes, finite vibration, volume restoration, DND respect and cancellable hold controls\n");
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
        AlarmStore.Alarm a = alarm(tone == 0 ? "Silent battery check" : "Morning alarm");
        a.tone = tone; a.vibration = 1; a.seconds = Integer.parseInt(arguments.getString("duration", "20")); a.followupGap = 1; a.followupCount = 2;
        long at = System.currentTimeMillis() + 8_000;
        data.alarms.add(a);
        data.pending.add(new AlarmStore.Event(a.id, AlarmStore.MAIN, at, at, "2027-01-05", 0, 0, false));
        data.save();
        AlarmReceiver.schedule(context, data);
    }

    private void runChecks(Context context) throws Exception {
        long minute = AlarmStore.MIN;
        long main = Instant.parse("2027-01-05T07:00:00Z").toEpochMilli(), elapsed = 1_000_000;
        AlarmStore data = new AlarmStore(context);
        AlarmStore.Alarm a = alarm("Main");
        check(a.repeatUnanswered, "New alarms repeat unanswered by default");
        a.repeatUnanswered = false; a.pre = 3; a.followupGap = 1; a.followupCount = 3; a.seconds = 20;
        AlarmStore.Alarm b = alarm("Reminder"); b.reminder = true; b.repeatUnanswered = false;
        data.put(a, main - 10 * minute); data.put(b, main - 10 * minute);
        data.tick(main - 3 * minute, elapsed - 3 * minute);
        check(ring(data, a.id).type() == AlarmStore.PRE, "Pre-alarm");
        data.respond(ring(data, a.id).key(), "dismiss", main - 3 * minute + 1000, elapsed - 3 * minute + 1000);
        data.tick(main, elapsed);
        check(data.ringing.size() == 2, "Independent overlapping alarms");
        check(data.pending.stream().noneMatch(e -> e.type() == AlarmStore.SNOOZE), "Unanswered off by default");
        data.respond(ring(data, b.id).key(), "skip", main + 100, elapsed + 100);
        data.respond(ring(data, a.id).key(), "snooze", main + 200, elapsed + 200);
        check(data.pending.size() == 3, "One snooze queues all three follow-ups");
        data.save(); data = new AlarmStore(context);
        check(data.pending.size() == 3, "Sequence survives process restart");
        data.tick(main + minute + 200, elapsed + minute + 200);
        check(ring(data, a.id).snoozes() == 1, "First follow-up");
        data.respond(ring(data, a.id).key(), "snooze", main + minute + 5000, elapsed + minute + 5000);
        check(data.pending.size() == 2, "Resnooze keeps only remaining budget");
        data.tick(main + 2 * minute + 5000, elapsed + 2 * minute + 5000);
        check(ring(data, a.id).snoozes() == 2, "Second follow-up");
        data.tick(main + 3 * minute + 5000, elapsed + 3 * minute + 5000);
        check(ring(data, a.id).snoozes() == 3, "Final follow-up arrives without another snooze");
        data.respond(ring(data, a.id).key(), "snooze", main + 3 * minute + 6000, elapsed + 3 * minute + 6000);
        check(data.ringing.size() == 1 && data.pending.isEmpty(), "Count cap cannot reset");
        data.respond(ring(data, a.id).key(), "dismiss", main + 3 * minute + 7000, elapsed + 3 * minute + 7000);
        check(data.ringing.isEmpty(), "Final dismiss");
        a.repeatUnanswered = true;
        data.alarms.clear(); data.alarms.add(a); data.alarms.add(b);
        data.pending.add(new AlarmStore.Event(a.id, AlarmStore.MAIN, main, main, "2027-01-05", 0, 0, false));
        data.pending.add(new AlarmStore.Event(b.id, AlarmStore.MAIN, main, main, "2027-01-05", 0, 0, false));
        data.tick(main, elapsed);
        check(data.pending.size() == 3, "Unanswered switch arms sequence at initial ring");
        data.respond(ring(data, a.id).key(), "dismiss", main + 1000, elapsed + 1000);
        check(data.pending.isEmpty() && data.ringing.size() == 1, "Dismiss cancels its sequence only");
        data.respond(ring(data, b.id).key(), "dismiss", main + 1000, elapsed + 1000);
        data.pending.add(new AlarmStore.Event(a.id, AlarmStore.SNOOZE, main + minute, main, "2027-01-05", 1, 0, true, 0, data.bootCount, elapsed + minute));
        data.tick(main + 3_600_000, elapsed + 30_000);
        check(data.ringing.isEmpty(), "Clock jump does not trigger follow-up early");
        data.tick(main - 3_600_000, elapsed + minute);
        check(data.ringing.size() == 1, "Clock backward does not postpone follow-up");
        data.save(); data = new AlarmStore(context);
        data.recover(main + 10 * minute, true, false);
        check(data.ringing.isEmpty(), "Reboot clears old ringing");
        data.purge(main + AlarmStore.DAY + 20 * minute);
        check(data.history.isEmpty() && data.alarms.size() == 2, "24-hour retention preserves alarm definitions");
        AlarmStore.Alarm legacy = AlarmStore.Alarm.from(new org.json.JSONObject("{\"snooze\":5,\"limit\":3,\"retry\":2,\"backup\":10,\"week\":[420,430,-1,-1,-1,-1,-1]}"));
        check(legacy.reviewFollowups && legacy.repeatUnanswered && legacy.followupCount == 3, "Legacy options migration notice");
        check(legacy.schedule.mixedWeekTimes(), "Upgrade preserves different weekday times");
        legacy.schedule.sharedTime(480);
        check(legacy.schedule.week[0] == 480 && legacy.schedule.week[1] == 480 && legacy.schedule.week[2] == -1, "One time updates enabled weekdays only");
        check(!legacy.schedule.mixedWeekTimes(), "Shared time removes old differences");
    }

    private void volumeChecks(Context context) {
        android.media.AudioManager audio = context.getSystemService(android.media.AudioManager.class);
        int stream = android.media.AudioManager.STREAM_ALARM, original = audio.getStreamVolume(stream);
        int max = audio.getStreamMaxVolume(stream), originalRinger = audio.getRingerMode();
        AlarmVolume first = new AlarmVolume(context), second = new AlarmVolume(context);
        try {
            if (AlarmVolume.dndBlocksAlarms(context)) {
                check(!first.apply(100) && audio.getStreamVolume(stream) == original, "DND is not overridden");
                return;
            }
            audio.setStreamVolume(stream, 0, 0);
            int baseline = audio.getStreamVolume(stream);
            check(first.apply(100) && audio.getStreamVolume(stream) == max, "Minimum volume raised for alarm");
            check(audio.getRingerMode() == originalRinger, "Alarm volume preserves ringer mode");
            check(second.apply(40) && audio.getStreamVolume(stream) == max, "Concurrent owners keep louder volume");
            first.close();
            check(audio.getStreamVolume(stream) == Math.max(1, Math.round(max * .4f)), "Remaining owner takes over");
            second.close();
            check(audio.getStreamVolume(stream) == baseline, "Original minimum volume restored");
            first.apply(100); audio.setStreamVolume(stream, 1, 0); first.close();
            check(audio.getStreamVolume(stream) == 1, "Manual volume changes preserved");
            context.getSharedPreferences("alarm_volume_restore", Context.MODE_PRIVATE).edit()
                .putBoolean("active", true).putInt("original", 2).putInt("applied", max).commit();
            audio.setStreamVolume(stream, max, 0);
            AlarmVolume.restoreIfUnused(context);
            check(audio.getStreamVolume(stream) == 2, "Process-death volume recovery");
        } finally {
            first.close(); second.close(); audio.setStreamVolume(stream, original, 0);
        }
    }

    private void holdChecks() throws Exception {
        final int[] fired = {0};
        final HoldButton[] button = new HoldButton[1];
        android.app.Activity activity = startActivitySync(new Intent(getTargetContext(), MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        runOnMainSync(() -> {
            android.widget.LinearLayout parent = new android.widget.LinearLayout(activity);
            button[0] = new HoldButton(activity, "Test", true, () -> fired[0]++);
            parent.addView(button[0], new android.widget.LinearLayout.LayoutParams(300,100));
            activity.setContentView(parent);
        });
        waitForIdleSync();
        touch(button[0], android.view.MotionEvent.ACTION_DOWN, 20, 20);
        android.os.SystemClock.sleep(100);
        touch(button[0], android.view.MotionEvent.ACTION_UP, 20, 20);
        android.os.SystemClock.sleep(1550);
        check(fired[0] == 0, "Short tap does nothing");
        touch(button[0], android.view.MotionEvent.ACTION_DOWN, 20, 20);
        android.os.SystemClock.sleep(100);
        touch(button[0], android.view.MotionEvent.ACTION_MOVE, 400, 20);
        android.os.SystemClock.sleep(1550);
        check(fired[0] == 0, "Moving outside cancels");
        touch(button[0], android.view.MotionEvent.ACTION_DOWN, 20, 20);
        android.os.SystemClock.sleep(100);
        runOnMainSync(() -> button[0].cancelHold());
        android.os.SystemClock.sleep(1550);
        check(fired[0] == 0, "Lifecycle cancellation stops hold");
        touch(button[0], android.view.MotionEvent.ACTION_DOWN, 20, 20);
        android.os.SystemClock.sleep(1800);
        touch(button[0], android.view.MotionEvent.ACTION_UP, 20, 20);
        check(fired[0] == 1, "Full hold fires once");
        runOnMainSync(activity::finish);
    }
    private void touch(HoldButton button, int action, float x, float y) {
        runOnMainSync(() -> {
            long now = android.os.SystemClock.uptimeMillis();
            android.view.MotionEvent event = android.view.MotionEvent.obtain(now, now, action, x, y, 0);
            button.onTouchEvent(event); event.recycle();
        });
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
