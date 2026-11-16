package sg.lightalarm;

import android.app.*;
import android.content.*;
import android.os.PowerManager;

public final class AlarmReceiver extends BroadcastReceiver {
    static final String FIRE = "sg.lightalarm.FIRE", EXPIRE = "sg.lightalarm.EXPIRE",
            CHANGE = "sg.lightalarm.CHANGE", PRUNE = "sg.lightalarm.PRUNE";

    @Override public void onReceive(Context context, Intent intent) {
        AlarmStore data = new AlarmStore(context);
        long now = System.currentTimeMillis();
        String action = intent.getAction();
        if (Intent.ACTION_BOOT_COMPLETED.equals(action)
                || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                || Intent.ACTION_TIME_CHANGED.equals(action)
                || Intent.ACTION_TIMEZONE_CHANGED.equals(action)) {
            boolean wall = Intent.ACTION_TIME_CHANGED.equals(action) || Intent.ACTION_TIMEZONE_CHANGED.equals(action);
            data.recover(now, Intent.ACTION_BOOT_COMPLETED.equals(action)
                    || Intent.ACTION_MY_PACKAGE_REPLACED.equals(action), wall);
        } else if (CHANGE.equals(action)) {
            data.respond(intent.getStringExtra("key"), intent.getStringExtra("response"), now);
        } else if (!PRUNE.equals(action)) {
            data.tick(now);
        }
        data.purge(now);
        data.save();
        schedule(context, data);
        refresh(context, data);
    }

    static void schedule(Context context, AlarmStore data) {
        AlarmManager mgr = context.getSystemService(AlarmManager.class);
        PendingIntent fire = request(context, FIRE), expire = request(context, EXPIRE), prune = request(context, PRUNE);
        mgr.cancel(fire);
        mgr.cancel(expire);
        mgr.cancel(prune);
        long at = Long.MAX_VALUE, end = Long.MAX_VALUE, oldest = Long.MAX_VALUE;
        for (AlarmStore.Event e : data.pending) at = Math.min(at, e.at());
        for (AlarmStore.Event e : data.ringing) end = Math.min(end, e.deadline());
        for (AlarmStore.Log log : data.history) oldest = Math.min(oldest, log.at());
        if (mgr.canScheduleExactAlarms()) {
            if (at != Long.MAX_VALUE)
                mgr.setAlarmClock(new AlarmManager.AlarmClockInfo(at, home(context)), fire);
            if (end != Long.MAX_VALUE) mgr.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, end, expire);
        }
        if (oldest != Long.MAX_VALUE) mgr.set(AlarmManager.RTC, oldest + AlarmStore.DAY, prune);
    }

    static void refresh(Context ctx, AlarmStore data) {
        Intent service = new Intent(ctx, AlarmService.class);
        if (data.ringing.isEmpty()) ctx.stopService(service);
        else {
            try { ctx.startForegroundService(service); }
            catch (ForegroundServiceStartNotAllowedException blocked) {
                android.util.Log.w("LightAlarm", "Background update could not reopen the alarm service", blocked);
            }
        }
    }

    static PendingIntent home(Context ctx) {
        return PendingIntent.getActivity(ctx, 10, new Intent(ctx, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static PendingIntent request(Context ctx, String action) {
        return PendingIntent.getBroadcast(ctx, 0, new Intent(ctx, AlarmReceiver.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE);
    }

    static PowerManager.WakeLock wake(Context ctx) {
        return ctx.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "LightAlarm:ring");
    }
}
