package io.github.mineapp.ext.hook;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import io.github.mineapp.ext.Const;

/**
 * 桌面快捷方式点进来的 MainActivity 实例：不加载 WebView，透明窗口，
 * 解冻 → 拉起目标应用 → 自己退出。
 */
final class ShortcutLauncher {
    private ShortcutLauncher() { }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    static boolean isOurs(Intent it) {
        return it != null && Const.ACTION_LAUNCH_FROZEN.equals(it.getAction());
    }

    /** 在 Activity.onCreate（父类）已执行之后调用。 */
    static void handle(final Activity a, Intent it) {
        if (Build.VERSION.SDK_INT >= 30) a.setTranslucent(true);
        a.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        a.getWindow().setDimAmount(0f);
        Env.setApp(a);
        AutoFreeze.ensure(a);

        final String pkg = it.getStringExtra(Const.EXTRA_PKG);
        final PackageManager pm = a.getPackageManager();
        final ApplicationInfo ai = pkg == null ? null : FrozenApps.info(pm, pkg);
        if (ai == null) {
            done(a, zh() ? "应用已不存在" : "App not found");
            return;
        }
        final String label = FrozenApps.label(pm, ai);
        if (!FrozenApps.isFrozen(pm, ai)) {
            launch(a, pkg, label, 0);
            return;
        }
        Toast.makeText(a, (zh() ? "正在解冻 " : "Unfreezing ") + label, Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            String err = null;
            try {
                List<String> ok = AppOps.batch(a, "unfreeze", Collections.singletonList(pkg));
                if (ok.contains(pkg)) AutoFreeze.markTemp(pkg);
                else err = zh() ? "解冻失败" : "Unfreeze failed";
            } catch (Throwable t) {
                Env.log("shortcut unfreeze " + pkg, t);
                err = (zh() ? "解冻失败：执行通道不可用，请打开 Mine App 检查" : "Unfreeze failed: shell channel unavailable")
                        + " (" + t.getMessage() + ")";
            }
            final String e = err;
            MAIN.post(() -> {
                if (e != null) done(a, e);
                else launch(a, pkg, label, 0);
            });
        }, "mineext-unfreeze").start();
    }

    /** 刚解冻时 PackageManager 可能还没刷新出启动入口，最多重试 3 秒。 */
    private static void launch(Activity a, String pkg, String label, int attempt) {
        if (a.isFinishing() || a.isDestroyed()) return;
        Intent li = a.getPackageManager().getLaunchIntentForPackage(pkg);
        if (li == null) {
            if (attempt < 15) {
                MAIN.postDelayed(() -> launch(a, pkg, label, attempt + 1), 200);
            } else {
                done(a, label + (zh() ? " 已解冻，但没有可打开的界面" : " is unfrozen but has no launcher activity"));
            }
            return;
        }
        try {
            a.startActivity(li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) {
            done(a, (zh() ? "无法打开 " : "Cannot open ") + label);
            return;
        }
        done(a, null);
    }

    private static void done(Activity a, String msg) {
        if (msg != null) Toast.makeText(a, msg, Toast.LENGTH_LONG).show();
        a.finishAndRemoveTask();
        a.overridePendingTransition(0, 0);
    }

    private static boolean zh() {
        return Locale.getDefault().getLanguage().startsWith("zh");
    }
}
