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
    private String shown = "", selected = "";
    private HoldButton snooze, dismiss, skip;
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
    private void cancelHolds() {
        if (snooze != null) snooze.cancelHold();
        if (dismiss != null) dismiss.cancelHold();
        if (skip != null) skip.cancelHold();
    }
    @Override protected void onPause() { cancelHolds(); handler.removeCallbacksAndMessages(null); super.onPause(); }

    private void update() {
        AlarmStore data = new AlarmStore(this);
        AlarmReceiver.finishExpired(this, data);
        if (data.ringing.isEmpty()) { finish(); return; }
        ArrayList<AlarmStore.Event> events = new ArrayList<>(data.ringing);
        events.sort(Comparator.comparingInt((AlarmStore.Event e) -> e.type() == AlarmStore.PRE ? 1 : 0)
                .thenComparingLong(AlarmStore.Event::at));
        boolean locked = getSystemService(KeyguardManager.class).isKeyguardLocked();
        String signature = events.toString() + locked + selected;
        if (signature.equals(shown)) return;
        shown = signature;
        cancelHolds();
        AlarmStore.Event e = events.stream().filter(x -> x.key().equals(selected)).findFirst().orElse(events.get(0));
        selected = e.key();
        shown = events.toString() + locked + selected;
        AlarmStore.Alarm a = data.find(e.alarm());
        if (a == null) return;
        LinearLayout frame = column();
        frame.setBackgroundColor(BG);
        frame.setPadding(dp(20), dp(16), dp(20), dp(20));
        if (events.size() > 1) {
            int next = (events.indexOf(e) + 1) % events.size();
            frame.addView(button("Alarm " + (events.indexOf(e) + 1) + " of " + events.size() + " · Next", false,
                    () -> { selected = events.get(next).key(); shown = ""; update(); }));
            gap(frame, 8);
        }
        snooze = null;
        if (e.type() != AlarmStore.PRE && e.snoozes() < a.followupCount) {
            snooze = new HoldButton(this, "Hold to snooze · " + a.followupGap + " min", false, () -> respond(e, "snooze"));
            frame.addView(snooze, new LinearLayout.LayoutParams(-1, dp(76)));
        } else {
            note(frame, e.type() == AlarmStore.PRE ? "The main alarm remains scheduled." : "No follow-ups remaining");
        }
        ScrollView middle = new ScrollView(this);
        middle.setFillViewport(true);
        LinearLayout box = column();
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setPadding(0, dp(8), 0, dp(8));
        label(box, !e.phase().isEmpty() ? e.phase() : a.reminder ? "Reminder" : "Alarm");
        box.addView(text(android.text.format.DateFormat.getTimeFormat(this).format(e.at()), 36, true, INK));
        boolean privateLabel = a.hide && locked;
        box.addView(text(privateLabel ? (a.reminder ? "Reminder" : "Alarm") : a.title(), 20, true, INK));
        if (!privateLabel && a.reminder && !a.note.isBlank()) { gap(box, 8); note(box, a.note); }
        gap(box, 6);
        if (e.type() != AlarmStore.PRE && e.snoozes() < a.followupCount)
            note(box, (a.followupCount - e.snoozes()) + " follow-ups remaining · " + a.followupGap + " min apart");
        skip = null;
        if (a.reminder && e.type() != AlarmStore.PRE) {
            gap(box, 8);
            skip = new HoldButton(this, "Hold to skip today", false, () -> respond(e, "skip"));
            box.addView(skip, new LinearLayout.LayoutParams(-1, dp(48)));
        }
        middle.addView(box, new ScrollView.LayoutParams(-1, -1));
        frame.addView(middle, new LinearLayout.LayoutParams(-1, 0, 1));
        dismiss = new HoldButton(this, "Hold to dismiss", true, () -> respond(e, "dismiss"));
        frame.addView(dismiss, new LinearLayout.LayoutParams(-1, dp(76)));
        setContentView(frame);
    }

    private void respond(AlarmStore.Event event, String response) {
        cancelHolds();
        sendBroadcast(new Intent(this, AlarmReceiver.class).setAction(AlarmReceiver.CHANGE)
                .putExtra("key", event.key()).putExtra("response", response));
        handler.postDelayed(this::update, 150);
    }
}
