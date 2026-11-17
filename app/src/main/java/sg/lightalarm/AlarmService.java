package sg.lightalarm;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.util.Comparator;

public final class AlarmService extends Service {
    private static final String CHANNEL = "ringing_v1";
    private static final int NOTIFICATION = 122;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Tone tone = new Tone();
    private PowerManager.WakeLock wake;
    private String playing = "";
    private AlarmVolume volume;

    @Override public IBinder onBind(Intent intent) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        AlarmStore data = new AlarmStore(this);
        data.tick(System.currentTimeMillis());
        data.save();
        AlarmReceiver.schedule(this, data);
        AlarmStore.Event event = data.ringing.stream().min(Comparator
                .comparingInt((AlarmStore.Event e) -> e.type() == AlarmStore.PRE ? 1 : 0)
                .thenComparingLong(AlarmStore.Event::at)).orElse(null);
        if (event == null) {
            AlarmVolume.restoreIfUnused(this);
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        AlarmStore.Alarm alarm = data.find(event.alarm());
        if (alarm == null) { stopSelf(startId); return START_NOT_STICKY; }
        NotificationManager notifications = getSystemService(NotificationManager.class);
        NotificationChannel channel = new NotificationChannel(CHANNEL, "Ringing alarms", NotificationManager.IMPORTANCE_HIGH);
        channel.setSound(null, null);
        channel.enableVibration(false);
        channel.setLockscreenVisibility(Notification.VISIBILITY_PUBLIC);
        notifications.createNotificationChannel(channel);
        boolean changed = !event.key().equals(playing);
        startForeground(NOTIFICATION, notification(data, event, alarm, changed),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
        if (changed) {
            playing = event.key();
            long duration = (event.type() == AlarmStore.PRE ? 8 : alarm.seconds) * 1000L;
            long elapsed = Math.max(0, duration - (event.elapsedDeadline() - SystemClock.elapsedRealtime()));
            if (volume == null) volume = new AlarmVolume(this);
            if (alarm.tone > 0) volume.apply(alarm.volume);
            else volume.close();
            tone.play(alarm.tone, event.type() == AlarmStore.PRE ? 14 : alarm.fade,
                    elapsed);
            Vibrator vibrator = getSystemService(VibratorManager.class).getDefaultVibrator();
            vibrator.cancel();
            if (alarm.vibration > 0 && event.type() != AlarmStore.PRE && elapsed < duration)
                vibrator.vibrate(Tone.timedPattern(alarm.vibration, elapsed, duration),
                        new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_ALARM).build());
        }
        long delay = data.ringing.stream().mapToLong(AlarmStore.Event::elapsedDeadline).min()
                .orElse(SystemClock.elapsedRealtime()) - SystemClock.elapsedRealtime();
        if (wake == null) wake = AlarmReceiver.wake(this);
        if (wake.isHeld()) wake.release();
        // Audio needs the CPU, while the vibrator runs its finite waveform in the system.
        if (alarm.tone > 0) wake.acquire(Math.max(1000, delay + 5000));
        handler.removeCallbacksAndMessages(null);
        handler.postDelayed(() -> {
            AlarmStore live = new AlarmStore(this);
            live.tick(System.currentTimeMillis());
            live.save();
            AlarmReceiver.schedule(this, live);
            if (live.ringing.isEmpty()) stopSelf();
            else onStartCommand(null, 0, startId);
        }, Math.max(20, delay));
        return START_STICKY;
    }

    private Notification notification(AlarmStore data, AlarmStore.Event event, AlarmStore.Alarm alarm, boolean changed) {
        Intent open = new Intent(this, RingActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent show = PendingIntent.getActivity(this, 12, open, PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_alarm).setCategory(Notification.CATEGORY_ALARM)
                .setContentTitle(alarm.hide ? "Light Alarm" : alarm.title())
                .setContentText(data.ringing.size() > 1 ? data.ringing.size() + " alarms ringing" :
                        event.phase().isEmpty() ? "Alarm ringing" : event.phase())
                .setContentIntent(show).setOngoing(true).setOnlyAlertOnce(!changed);
        if (event.type() != AlarmStore.PRE) builder.setFullScreenIntent(show, true);
        // Notification actions open the protected controls instead of bypassing the hold.
        builder.addAction(new Notification.Action.Builder(null, "Open alarm", show).build());
        return builder.build();
    }

    @Override public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        tone.close();
        if (volume != null) volume.close();
        getSystemService(VibratorManager.class).getDefaultVibrator().cancel();
        if (wake != null && wake.isHeld()) wake.release();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }
}
