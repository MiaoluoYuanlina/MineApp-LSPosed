package io.github.mineapp.ext.hook;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.github.mineapp.ext.Const;

/**
 * 「通过快捷方式临时解冻」的应用，在关屏满 N 分钟后自动重新冻结。
 *
 * 全部跑在 Mine App 进程里：它的 KeepAliveService 是前台服务，进程常驻。
 * 延时用 AlarmManager（elapsedRealtime + allowWhileIdle），关屏深睡时 Handler 的计时会停。
 * 只管通过快捷方式解冻的应用；在 Mine App 里手动解冻的不动。
 */
final class AutoFreeze {
    private AutoFreeze() { }

    private static final String PREFS = "mineapp_ext";
    private static final String KEY_TEMP = "temp_unfrozen";
    private static final String KEY_DELAY = "autofreeze_min";
    private static final String ACTION_ALARM = Const.TARGET_PKG + ".mineext.AUTO_FREEZE";

    /** -1 = 不自动冻结；0 = 关屏立即；其余为分钟 */
    static final int OFF = -1;
    private static final int DEFAULT_DELAY = 5;

    private static volatile boolean registered;

    private static SharedPreferences prefs() {
        Context c = Env.app();
        return c == null ? null : c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static int delayMinutes() {
        SharedPreferences p = prefs();
        return p == null ? DEFAULT_DELAY : p.getInt(KEY_DELAY, DEFAULT_DELAY);
    }

    static void setDelayMinutes(int m) {
        SharedPreferences p = prefs();
        if (p != null) p.edit().putInt(KEY_DELAY, m < 0 ? OFF : m).apply();
    }

    static synchronized Set<String> tempUnfrozen() {
        SharedPreferences p = prefs();
        return p == null ? new HashSet<>() : new HashSet<>(p.getStringSet(KEY_TEMP, new HashSet<>()));
    }

    static synchronized void markTemp(String pkg) {
        Set<String> s = tempUnfrozen();
        if (s.add(pkg)) prefs().edit().putStringSet(KEY_TEMP, s).apply();
    }

    static synchronized void unmark(List<String> pkgs) {
        Set<String> s = tempUnfrozen();
        if (s.removeAll(pkgs)) prefs().edit().putStringSet(KEY_TEMP, s).apply();
    }

    /** 幂等：Mine App 界面、常驻服务、快捷方式启动三处都会调。 */
    static void ensure(Context ctx) {
        if (registered || ctx == null) return;
        synchronized (AutoFreeze.class) {
            if (registered) return;
            Env.setApp(ctx);
            IntentFilter f = new IntentFilter();
            f.addAction(Intent.ACTION_SCREEN_OFF);
            f.addAction(Intent.ACTION_SCREEN_ON);
            f.addAction(ACTION_ALARM);
            Context app = ctx.getApplicationContext();
            BroadcastReceiver r = new BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    String a = i.getAction();
                    if (Intent.ACTION_SCREEN_OFF.equals(a)) onScreenOff(app);
                    else if (Intent.ACTION_SCREEN_ON.equals(a)) cancel(app);
                    else if (ACTION_ALARM.equals(a)) onAlarm(app);
                }
            };
            // 闹钟的 PendingIntent 以本应用身份发出，NOT_EXPORTED 照样收得到；系统亮灭屏广播也不受影响
            if (Build.VERSION.SDK_INT >= 33) {
                app.registerReceiver(r, f, Context.RECEIVER_NOT_EXPORTED);
            } else {
                app.registerReceiver(r, f);
            }
            registered = true;
        }
    }

    private static PendingIntent alarmIntent(Context ctx) {
        Intent i = new Intent(ACTION_ALARM).setPackage(ctx.getPackageName());
        return PendingIntent.getBroadcast(ctx, 0x5e1, i,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private static void onScreenOff(Context ctx) {
        int m = delayMinutes();
        if (m == OFF || tempUnfrozen().isEmpty()) return;
        if (m == 0) {
            freezeNow(ctx);
            return;
        }
        AlarmManager am = ctx.getSystemService(AlarmManager.class);
        // 没有 SCHEDULE_EXACT_ALARM 权限，只能用非精确闹钟；Doze 下可能晚几分钟
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + m * 60_000L, alarmIntent(ctx));
    }

    private static void cancel(Context ctx) {
        ctx.getSystemService(AlarmManager.class).cancel(alarmIntent(ctx));
    }

    private static void onAlarm(Context ctx) {
        PowerManager pm = ctx.getSystemService(PowerManager.class);
        if (pm != null && pm.isInteractive()) return;   // 期间亮过屏，SCREEN_ON 没赶上取消
        freezeNow(ctx);
    }

    private static void freezeNow(final Context ctx) {
        final List<String> pkgs = new ArrayList<>(tempUnfrozen());
        if (pkgs.isEmpty()) return;
        // 广播接收器里 goAsync 也只有 10 秒，shell 可能更久，另起线程；进程有前台服务撑着
        new Thread(() -> {
            try {
                List<String> ok = AppOps.batch(ctx, "freeze", pkgs);
                unmark(ok);
                Env.log("auto-frozen " + ok);
            } catch (Throwable t) {
                Env.log("auto-freeze failed", t);
            }
        }, "mineext-autofreeze").start();
    }
}
