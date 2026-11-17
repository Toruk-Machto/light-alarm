package sg.lightalarm;

import android.app.NotificationManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioManager;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Temporarily applies the selected alarm volume without changing ringer or DND settings. */
final class AlarmVolume implements AutoCloseable {
    private static final Map<String, Integer> owners = new HashMap<>();
    private final String owner = UUID.randomUUID().toString();
    private final Context context;
    private final AudioManager audio;
    private final SharedPreferences prefs;

    AlarmVolume(Context context) {
        this.context = context.getApplicationContext();
        audio = context.getSystemService(AudioManager.class);
        prefs = context.getSharedPreferences("alarm_volume_restore", Context.MODE_PRIVATE);
    }

    static boolean dndBlocksAlarms(Context context) {
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        int filter = manager.getCurrentInterruptionFilter();
        if (filter == NotificationManager.INTERRUPTION_FILTER_NONE) return true;
        if (filter == NotificationManager.INTERRUPTION_FILTER_PRIORITY) {
            NotificationManager.Policy policy = manager.getConsolidatedNotificationPolicy();
            return policy == null || (policy.priorityCategories & NotificationManager.Policy.PRIORITY_CATEGORY_ALARMS) == 0;
        }
        return filter != NotificationManager.INTERRUPTION_FILTER_ALL
                && filter != NotificationManager.INTERRUPTION_FILTER_ALARMS;
    }

    boolean apply(int percent) {
        synchronized (owners) {
            if (dndBlocksAlarms(context) || audio.isVolumeFixed()) return false;
            int current = audio.getStreamVolume(AudioManager.STREAM_ALARM);
            if (!prefs.getBoolean("active", false) || (owners.isEmpty() && current != prefs.getInt("applied", -1))) {
                if (!prefs.edit().putBoolean("active", true).putInt("original", current)
                        .putInt("applied", current).commit()) return false;
            }
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_ALARM);
            int value = Math.max(1, Math.round(max * Math.max(10, Math.min(100, percent)) / 100f));
            owners.put(owner, value);
            return set(owners.values().stream().mapToInt(Integer::intValue).max().orElse(value));
        }
    }

    private boolean set(int level) {
        try {
            audio.setStreamVolume(AudioManager.STREAM_ALARM, level, 0);
            int actual = audio.getStreamVolume(AudioManager.STREAM_ALARM);
            prefs.edit().putInt("applied", actual).commit();
            return actual > 0;
        } catch (SecurityException | IllegalArgumentException blocked) {
            return false;
        }
    }

    @Override public void close() {
        synchronized (owners) {
            if (owners.remove(owner) == null) return;
            if (!owners.isEmpty()) {
                if (!dndBlocksAlarms(context)) set(owners.values().stream().mapToInt(Integer::intValue).max().orElse(1));
                return;
            }
            restoreIfUnused(context);
        }
    }

    static void restoreIfUnused(Context context) {
        synchronized (owners) {
            if (!owners.isEmpty()) return;
            SharedPreferences prefs = context.getSharedPreferences("alarm_volume_restore", Context.MODE_PRIVATE);
            if (!prefs.getBoolean("active", false)) return;
            AudioManager audio = context.getSystemService(AudioManager.class);
            int current = audio.getStreamVolume(AudioManager.STREAM_ALARM);
            // A deliberate hardware-volume change takes precedence over restoring the old level.
            if (current == prefs.getInt("applied", -1)) {
                if (dndBlocksAlarms(context)) return; // Restore later without overriding DND.
                try { audio.setStreamVolume(AudioManager.STREAM_ALARM, prefs.getInt("original", current), 0); }
                catch (SecurityException | IllegalArgumentException blocked) { return; }
            }
            prefs.edit().clear().commit();
        }
    }
}
