package io.github.mineapp.ext.hook;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.mineapp.ext.Const;

/**
 * 桌面「冻结应用」入口点开的弹窗：已冻结的应用 + 本模块临时解冻过的应用，
 * 带搜索框；点一项就解冻并打开；底部「全部冻结」把临时解冻的应用一次冻回去。
 *
 * 全部用代码画原生控件：模块的资源在 Mine App 进程里取不到，也不想为此拉起 WebView。
 */
final class Picker {
    private Picker() { }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final class Item {
        String pkg, label, search;
        Bitmap icon;
        boolean frozen;
    }

    private static boolean launching;

    static void show(final Activity a) {
        launching = false;
        final boolean zh = ShortcutLauncher.zh();
        final boolean night = (a.getResources().getConfiguration().uiMode
                & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        final int cBg = night ? 0xFF2A2A2D : 0xFFFFFFFF;
        final int cFg = night ? 0xFFEDEDEF : 0xFF1C1C1E;
        final int cSub = night ? 0xFF9C9CA3 : 0xFF6E6E73;
        final int cField = night ? 0xFF3A3A3E : 0xFFF0F0F3;
        final int cAccent = 0xFF3478F6;

        final Dialog dlg = new Dialog(a, night ? android.R.style.Theme_DeviceDefault_Dialog
                : android.R.style.Theme_DeviceDefault_Light_Dialog);
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dlg.setCanceledOnTouchOutside(true);

        final LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(rounded(cBg, dp(a, 22)));
        root.setPadding(dp(a, 16), dp(a, 16), dp(a, 16), dp(a, 12));

        final TextView title = text(a, zh ? "冻结的应用" : "Frozen apps", 18, cFg, true);
        root.addView(title);

        final EditText search = new EditText(a);
        search.setHint(zh ? "搜索应用名或包名" : "Search by name or package");
        search.setHintTextColor(cSub);
        search.setTextColor(cFg);
        search.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        search.setSingleLine(true);
        search.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        search.setBackground(rounded(cField, dp(a, 12)));
        search.setPadding(dp(a, 14), dp(a, 10), dp(a, 14), dp(a, 10));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sp.topMargin = dp(a, 10);
        root.addView(search, sp);

        final ListView list = new ListView(a);
        list.setDivider(null);
        list.setVerticalScrollBarEnabled(false);
        final TextView empty = text(a, zh ? "加载中…" : "Loading…", 14, cSub, false);
        empty.setGravity(Gravity.CENTER);
        FrameHolder body = new FrameHolder(a, list, empty);
        root.addView(body.view, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout bar = new LinearLayout(a);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        final TextView freezeAll = button(a, "", cAccent, 0xFFFFFFFF, true);
        final TextView close = button(a, zh ? "关闭" : "Close", cField, cFg, false);
        LinearLayout.LayoutParams fp = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        fp.rightMargin = dp(a, 10);
        bar.addView(freezeAll, fp);
        bar.addView(close, new LinearLayout.LayoutParams(dp(a, 88), ViewGroup.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(a, 10);
        root.addView(bar, bp);

        final List<Item> all = new ArrayList<>();
        final List<Item> shown = new ArrayList<>();
        final boolean[] busy = {false};

        final BaseAdapter adapter = new BaseAdapter() {
            @Override public int getCount() { return shown.size(); }
            @Override public Object getItem(int i) { return shown.get(i); }
            @Override public long getItemId(int i) { return i; }

            @Override
            public View getView(int i, View v, ViewGroup parent) {
                Item it = shown.get(i);
                LinearLayout row;
                if (v instanceof LinearLayout) {
                    row = (LinearLayout) v;
                } else {
                    row = new LinearLayout(a);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(a, 4), dp(a, 8), dp(a, 4), dp(a, 8));
                    ImageView iv = new ImageView(a);
                    row.addView(iv, new LinearLayout.LayoutParams(dp(a, 42), dp(a, 42)));
                    LinearLayout col = new LinearLayout(a);
                    col.setOrientation(LinearLayout.VERTICAL);
                    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0,
                            ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                    cp.leftMargin = dp(a, 12);
                    cp.rightMargin = dp(a, 8);
                    row.addView(col, cp);
                    TextView t1 = text(a, "", 16, cFg, false);
                    t1.setSingleLine(true);
                    t1.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    TextView t2 = text(a, "", 12, cSub, false);
                    t2.setSingleLine(true);
                    t2.setEllipsize(android.text.TextUtils.TruncateAt.END);
                    col.addView(t1);
                    col.addView(t2);
                    row.addView(text(a, "", 12, cAccent, false));
                }
                ImageView iv = (ImageView) row.getChildAt(0);
                LinearLayout col = (LinearLayout) row.getChildAt(1);
                TextView tag = (TextView) row.getChildAt(2);
                iv.setImageBitmap(it.icon);
                if (it.frozen) {
                    ColorMatrix cm = new ColorMatrix();
                    cm.setSaturation(0.15f);
                    iv.setColorFilter(new ColorMatrixColorFilter(cm));
                    iv.setAlpha(0.85f);
                } else {
                    iv.clearColorFilter();
                    iv.setAlpha(1f);
                }
                ((TextView) col.getChildAt(0)).setText(it.label);
                ((TextView) col.getChildAt(1)).setText(it.pkg);
                tag.setText(it.frozen ? "" : (zh ? "已解冻" : "Unfrozen"));
                return row;
            }
        };
        list.setAdapter(adapter);

        final Runnable refreshButton = () -> {
            int n = AutoFreeze.tempUnfrozen().size();
            freezeAll.setText((zh ? "全部冻结" : "Freeze all") + " (" + n + ")");
            freezeAll.setAlpha(n > 0 && !busy[0] ? 1f : 0.45f);
        };
        final Runnable applyFilter = () -> {
            String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
            shown.clear();
            for (Item it : all) if (q.isEmpty() || it.search.contains(q)) shown.add(it);
            adapter.notifyDataSetChanged();
            empty.setText(all.isEmpty() ? (zh ? "没有已冻结的应用" : "No frozen apps")
                    : (zh ? "没有匹配的应用" : "No matches"));
            empty.setVisibility(shown.isEmpty() ? View.VISIBLE : View.GONE);
            title.setText((zh ? "冻结的应用" : "Frozen apps") + (all.isEmpty() ? "" : " (" + all.size() + ")"));
            refreshButton.run();
        };
        // 重新读一遍（图标解码放后台线程）
        final Runnable reload = () -> new Thread(() -> {
            final List<Item> fresh = collect(a);
            MAIN.post(() -> {
                all.clear();
                all.addAll(fresh);
                applyFilter.run();
            });
        }, "mineext-picker").start();

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { applyFilter.run(); }
        });

        list.setOnItemClickListener((p, v, pos, id) -> {
            if (busy[0]) return;
            final Item it = shown.get(pos);
            launching = true;
            dlg.dismiss();
            ShortcutLauncher.open(a, it.pkg);
        });

        close.setOnClickListener(v -> dlg.dismiss());

        freezeAll.setOnClickListener(v -> {
            if (busy[0]) return;
            if (AutoFreeze.tempUnfrozen().isEmpty()) {
                Toast.makeText(a, zh ? "没有需要冻结的应用" : "Nothing to freeze", Toast.LENGTH_SHORT).show();
                return;
            }
            busy[0] = true;
            refreshButton.run();
            Toast.makeText(a, zh ? "正在冻结…" : "Freezing…", Toast.LENGTH_SHORT).show();
            new Thread(() -> {
                String msg;
                try {
                    List<String> ok = AutoFreeze.freezeTemp(a);
                    msg = zh ? "已冻结 " + ok.size() + " 个应用" : "Froze " + ok.size() + " app(s)";
                    int left = AutoFreeze.tempUnfrozen().size();
                    if (left > 0) msg += zh ? "，" + left + " 个失败" : ", " + left + " failed";
                } catch (Throwable t) {
                    Env.log("freeze all failed", t);
                    msg = (zh ? "冻结失败：" : "Freeze failed: ") + t.getMessage();
                }
                final String m = msg;
                MAIN.post(() -> {
                    busy[0] = false;
                    Toast.makeText(a, m, Toast.LENGTH_LONG).show();
                    reload.run();
                });
            }, "mineext-freezeall").start();
        });

        dlg.setOnDismissListener(d -> {
            if (!launching) ShortcutLauncher.done(a, null);
        });

        dlg.setContentView(root);
        Window w = dlg.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
            DisplayMetrics dm = a.getResources().getDisplayMetrics();
            int width = Math.min(dm.widthPixels - dp(a, 32), dp(a, 440));
            w.setLayout(width, (int) (dm.heightPixels * 0.78f));
        }
        applyFilter.run();
        dlg.show();
        reload.run();
    }

    /** 已冻结的应用，加上临时解冻名单里（还没冻回去）的应用，按名称排序。 */
    private static List<Item> collect(Context ctx) {
        PackageManager pm = ctx.getPackageManager();
        Set<String> temp = AutoFreeze.tempUnfrozen();
        List<Item> out = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (ApplicationInfo ai : pm.getInstalledApplications(PackageManager.MATCH_UNINSTALLED_PACKAGES
                | PackageManager.MATCH_DISABLED_COMPONENTS)) {
            if (Const.TARGET_PKG.equals(ai.packageName)) continue;
            boolean frozen = FrozenApps.isFrozen(pm, ai);
            if (!frozen && !temp.contains(ai.packageName)) continue;
            if (!seen.add(ai.packageName)) continue;
            Item it = new Item();
            it.pkg = ai.packageName;
            it.label = FrozenApps.label(pm, ai);
            it.search = (it.label + "\n" + it.pkg).toLowerCase(Locale.ROOT);
            it.frozen = frozen;
            it.icon = FrozenApps.icon(pm, ai, 96);
            out.add(it);
        }
        final Collator col = Collator.getInstance(Locale.CHINA);
        Collections.sort(out, (x, y) -> col.compare(x.label, y.label));
        return out;
    }

    /** 列表与「空 / 无匹配」提示叠在一起的容器 */
    private static final class FrameHolder {
        final android.widget.FrameLayout view;

        FrameHolder(Context c, View list, View empty) {
            view = new android.widget.FrameLayout(c);
            view.addView(list, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            view.addView(empty, new android.widget.FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    private static TextView text(Context c, String s, int sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(t.getTypeface(), android.graphics.Typeface.BOLD);
        return t;
    }

    private static TextView button(Context c, String s, int bg, int fg, boolean bold) {
        TextView t = text(c, s, 15, fg, bold);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(bg, dp(c, 12)));
        t.setPadding(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        t.setClickable(true);
        return t;
    }

    private static GradientDrawable rounded(int color, int radiusPx) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radiusPx);
        return g;
    }

    private static int dp(Context c, float v) {
        return (int) (v * c.getResources().getDisplayMetrics().density + 0.5f);
    }
}
