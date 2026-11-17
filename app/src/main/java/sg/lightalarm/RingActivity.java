package sg.lightalarm;

import android.app.KeyguardManager;
import android.content.Intent;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.ArrayList;
import java.util.Comparator;

public final class RingActivity extends Page {
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String shown = "";
    private final Runnable updater = new Runnable() {
        @Override public void run() {
            update();
            if (!isFinishing()) handler.postDelayed(this, 350);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        setVolumeControlStream(android.media.AudioManager.STREAM_ALARM);
    }

    @Override protected void onResume() { super.onResume(); shown = ""; updater.run(); }
    @Override protected void onPause() { handler.removeCallbacksAndMessages(null); super.onPause(); }

    private void update() {
        AlarmStore data = new AlarmStore(this);
        AlarmReceiver.finishExpired(this, data);
        if (data.ringing.isEmpty()) { finish(); return; }
        ArrayList<AlarmStore.Event> events = new ArrayList<>(data.ringing);
        events.sort(Comparator.comparingInt((AlarmStore.Event e) -> e.type() == AlarmStore.PRE ? 1 : 0)
                .thenComparingLong(AlarmStore.Event::at));
        boolean locked = getSystemService(KeyguardManager.class).isKeyguardLocked();
        String signature = events.toString() + locked;
        if (signature.equals(shown)) return;
        shown = signature;
        page(events.size() == 1 ? "Light Alarm" : "Ringing alarms", null, null, null);
        for (AlarmStore.Event e : events) {
            AlarmStore.Alarm a = data.find(e.alarm());
            if (a == null) continue;
            LinearLayout box = card(root);
            label(box, !e.phase().isEmpty() ? e.phase() : a.reminder ? "Reminder" : "Alarm");
            box.addView(text(android.text.format.DateFormat.getTimeFormat(this).format(e.at()), 40, true, INK));
            boolean privateLabel = a.hide && locked;
            box.addView(text(privateLabel ? (a.reminder ? "Reminder" : "Alarm") : a.title(), 24, true, INK));
            if (!privateLabel && a.reminder && !a.note.isBlank()) {
                gap(box, 4);
                note(box, a.note);
            }
            gap(box, 17);
            if (e.type() != AlarmStore.PRE && a.snooze > 0 && e.snoozes() < a.limit) {
                String action = a.reminder ? "Remind me in " : "Snooze ";
                box.addView(button(action + a.snooze + " min", false, () -> respond(e, "snooze")));
                gap(box, 8);
            }
            String dismiss = a.reminder ? "Done" : "Dismiss";
            if (e.type() == AlarmStore.PRE || a.dismiss == AlarmStore.TAP)
                box.addView(button(dismiss, true, () -> respond(e, "dismiss")));
            else if (a.dismiss == AlarmStore.HOLD) {
                TextView hold = button("Hold to " + dismiss.toLowerCase(java.util.Locale.ROOT), true, () -> {});
                hold.setLongClickable(true);
                hold.setOnLongClickListener(v -> { respond(e, "dismiss"); return true; });
                box.addView(hold);
            } else {
                label(box, "Slide to " + dismiss.toLowerCase(java.util.Locale.ROOT));
                SeekBar slider = new SeekBar(this);
                slider.setContentDescription("Slide to " + dismiss.toLowerCase(java.util.Locale.ROOT));
                slider.setMax(100);
                slider.setMinHeight(dp(52));
                slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                    @Override public void onProgressChanged(SeekBar seek, int value, boolean fromUser) {
                        if (fromUser && value >= 95) { seek.setEnabled(false); respond(e, "dismiss"); }
                    }
                    @Override public void onStartTrackingTouch(SeekBar seek) {}
                    @Override public void onStopTrackingTouch(SeekBar seek) {
                        if (seek.isEnabled()) seek.setProgress(0);
                    }
                });
                box.addView(slider);
            }
            if (a.reminder && e.type() != AlarmStore.PRE) {
                gap(box, 8);
                box.addView(button("Skip today", false, () -> respond(e, "skip")));
            }
        }
    }

    private void respond(AlarmStore.Event event, String response) {
        sendBroadcast(new Intent(this, AlarmReceiver.class).setAction(AlarmReceiver.CHANGE)
                .putExtra("key", event.key()).putExtra("response", response));
        handler.postDelayed(this::update, 150);
    }
}
