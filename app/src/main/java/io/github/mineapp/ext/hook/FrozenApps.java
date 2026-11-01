package io.github.mineapp.ext.hook;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.mineapp.ext.Const;

/**
 * 已冻结应用列表 + 桌面快捷方式。
 *
 * 冻结状态直接问 PackageManager，不跑 shell（Mine App 有 QUERY_ALL_PACKAGES）：
 *   - disable-user 归档：enabledSetting 为 DISABLED / DISABLED_USER；
 *   - uninstall --user 0 归档（Mine App 只对系统应用这么做）：本用户未安装但 APK 仍在。
 */
final class FrozenApps {
    private FrozenApps() { }

    private static final int MATCH = PackageManager.MATCH_UNINSTALLED_PACKAGES
            | PackageManager.MATCH_DISABLED_COMPONENTS;

    static ApplicationInfo info(PackageManager pm, String pkg) {
        try {
            return pm.getApplicationInfo(pkg, MATCH);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    static boolean isFrozen(PackageManager pm, ApplicationInfo ai) {
        if (ai == null) return false;
        if ((ai.flags & ApplicationInfo.FLAG_INSTALLED) == 0) {
            return (ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        }
        try {
            int st = pm.getApplicationEnabledSetting(ai.packageName);
            return st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    || st == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        } catch (Throwable t) {
            return !ai.enabled;
        }
    }

    static String label(PackageManager pm, ApplicationInfo ai) {
        try {
            CharSequence l = ai.loadLabel(pm);
            if (l != null && l.length() > 0) return l.toString();
        } catch (Throwable ignored) { }
        return ai.packageName;
    }

    static Bitmap icon(PackageManager pm, ApplicationInfo ai, int px) {
        Drawable d;
        try {
            d = ai.loadIcon(pm);
        } catch (Throwable t) {
            d = pm.getDefaultActivityIcon();
        }
        Bitmap b = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        d.setBounds(0, 0, px, px);
        d.draw(c);
        return b;
    }

    private static String dataUri(Bitmap b) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.PNG, 100, out);
        return "data:image/png;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private static Set<String> pinnedIds(Context ctx) {
        Set<String> ids = new HashSet<>();
        try {
            ShortcutManager sm = ctx.getSystemService(ShortcutManager.class);
            for (ShortcutInfo s : sm.getPinnedShortcuts()) ids.add(s.getId());
        } catch (Throwable ignored) { }
        return ids;
    }

    static JSONObject list(Context ctx) throws Exception {
        PackageManager pm = ctx.getPackageManager();
        Set<String> pinned = pinnedIds(ctx);
        List<ApplicationInfo> frozen = new ArrayList<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(MATCH)) {
            if (Const.TARGET_PKG.equals(ai.packageName)) continue;
            if (isFrozen(pm, ai)) frozen.add(ai);
        }
        final Collator col = Collator.getInstance(Locale.CHINA);
        List<Object[]> rows = new ArrayList<>();
        for (ApplicationInfo ai : frozen) rows.add(new Object[]{label(pm, ai), ai});
        Collections.sort(rows, (a, b) -> col.compare((String) a[0], (String) b[0]));

        JSONArray apps = new JSONArray();
        for (Object[] r : rows) {
            ApplicationInfo ai = (ApplicationInfo) r[1];
            JSONObject o = new JSONObject();
            o.put("pkg", ai.packageName);
            o.put("name", r[0]);
            o.put("icon", dataUri(icon(pm, ai, 96)));
            o.put("pinned", pinned.contains(Const.SHORTCUT_PREFIX + ai.packageName));
            apps.put(o);
        }

        JSONArray temp = new JSONArray();
        for (String p : AutoFreeze.tempUnfrozen()) {
            ApplicationInfo ai = info(pm, p);
            temp.put(ai == null ? p : label(pm, ai));
        }

        JSONObject out = new JSONObject();
        out.put("apps", apps);
        out.put("temp", temp);
        out.put("autofreeze", AutoFreeze.delayMinutes());
        boolean supported = false;
        try {
            supported = ctx.getSystemService(ShortcutManager.class).isRequestPinShortcutSupported();
        } catch (Throwable ignored) { }
        out.put("pinSupported", supported);
        return out;
    }

    /** 请求把快捷方式钉到桌面。返回 "requested" / "updated" / "unsupported" / "missing"。 */
    static String pin(Context ctx, String pkg) {
        PackageManager pm = ctx.getPackageManager();
        ApplicationInfo ai = info(pm, pkg);
        if (ai == null) return "missing";
        ShortcutManager sm = ctx.getSystemService(ShortcutManager.class);
        String label = label(pm, ai);
        Intent it = new Intent(Const.ACTION_LAUNCH_FROZEN)
                .setClassName(Const.TARGET_PKG, Const.TARGET_MAIN)
                // data 各不相同：多个快捷方式各自成一个 document task，互不覆盖
                .setData(Uri.parse("mineext://launch/" + pkg))
                .putExtra(Const.EXTRA_PKG, pkg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_DOCUMENT | Intent.FLAG_ACTIVITY_MULTIPLE_TASK
                        | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS | Intent.FLAG_ACTIVITY_NO_ANIMATION);
        ShortcutInfo info = new ShortcutInfo.Builder(ctx, Const.SHORTCUT_PREFIX + pkg)
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(Icon.createWithBitmap(icon(pm, ai, 192)))
                .setIntent(it)
                .build();
        if (pinnedIds(ctx).contains(info.getId())) {
            sm.updateShortcuts(Collections.singletonList(info));
            return "updated";
        }
        if (!sm.isRequestPinShortcutSupported()) return "unsupported";
        return sm.requestPinShortcut(info, null) ? "requested" : "unsupported";
    }
}
