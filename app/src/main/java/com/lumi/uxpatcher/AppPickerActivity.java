package com.lumi.uxpatcher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** App picker for the gamepad-mode whitelist. */
public class AppPickerActivity extends Activity {

    private static final String TAG = "UXPatcher-Picker";

    private static final int COLOR_BG      = Color.parseColor("#0F0F1A");
    private static final int COLOR_CARD    = Color.parseColor("#1A1A2E");
    private static final int COLOR_ACCENT  = Color.parseColor("#7B68EE");
    private static final int COLOR_TEXT    = Color.WHITE;
    private static final int COLOR_DIM     = Color.parseColor("#9090B0");
    private static final int COLOR_WARNING = Color.parseColor("#7A3800");
    private static final int COLOR_SAVE    = Color.parseColor("#2E7D32");
    private static final int COLOR_TOOLBAR = Color.parseColor("#14142A");

    /** Default allowed list */
    private static final Set<String> ALWAYS_ALLOWED = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList(
                    "com.valvesoftware.steamlink",
                    "com.xbox.pwa"
            )));

    /** System app list */
    private static final List<String> SYSTEM_PREFIXES = Arrays.asList(
            "com.oculus.",
            "com.meta.",
            "com.facebook.",
            "com.android."
    );

    private SharedPreferences prefs;
    private final List<AppEntry> entries = new ArrayList<>();

    private static final class AppEntry {
        String pkg;
        String label;
        CheckBox cb;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = openPrefs();

        if (getActionBar() != null) getActionBar().hide();
        getWindow().setStatusBarColor(COLOR_BG);
        getWindow().setNavigationBarColor(COLOR_BG);

        // ── Root layout ───────────────────────────────────────────────────────────────────
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        // ── Toolbar ───────────────────────────────────────────────────────────────────────
        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setOrientation(LinearLayout.HORIZONTAL);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setBackgroundColor(COLOR_TOOLBAR);
        toolbar.setPadding(dp(4), dp(4), dp(16), dp(4));

        ImageButton backBtn = new ImageButton(this);
        backBtn.setBackground(null);
        int iconSize = dp(24);
        int btnSize  = dp(48);
        backBtn.setImageBitmap(drawArrowBack(iconSize, COLOR_TEXT));
        backBtn.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams backLp = new LinearLayout.LayoutParams(btnSize, btnSize);
        backBtn.setLayoutParams(backLp);
        backBtn.setOnClickListener(v -> finish());
        toolbar.addView(backBtn, backLp);

        TextView titleTv = new TextView(this);
        titleTv.setText("Gamepad Allowed Apps");
        titleTv.setTextColor(COLOR_TEXT);
        titleTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        titleTv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = dp(4);
        toolbar.addView(titleTv, titleLp);

        root.addView(toolbar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── Warning banner ────────────────────────────────────────────────────────────────
        LinearLayout banner = new LinearLayout(this);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setBackgroundColor(COLOR_WARNING);
        banner.setPadding(dp(16), dp(12), dp(16), dp(12));

        TextView warnTitle = new TextView(this);
        warnTitle.setText("⚠  Warning");
        warnTitle.setTextColor(Color.parseColor("#FFB74D"));
        warnTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        warnTitle.setTypeface(Typeface.DEFAULT_BOLD);
        banner.addView(warnTitle);

        TextView warnBody = new TextView(this);
        warnBody.setText("Enabling gamepad mode in apps may break native navigation.");
        warnBody.setTextColor(Color.parseColor("#FFCC80"));
        warnBody.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        LinearLayout.LayoutParams wbLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        wbLp.topMargin = dp(4);
        banner.addView(warnBody, wbLp);

        TextView alwaysNote = new TextView(this);
        alwaysNote.setText("Steam Link and Xbox are enabled by default");
        alwaysNote.setTextColor(Color.parseColor("#9090B0"));
        alwaysNote.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        LinearLayout.LayoutParams anLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        anLp.topMargin = dp(8);
        banner.addView(alwaysNote, anLp);

        root.addView(banner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        // ── Scrollable app list ───────────────────────────────────────────────────────────
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(16), dp(8), dp(16), dp(8));
        scroll.addView(list, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));

        // Load current whitelist
        Set<String> saved = loadSavedSet();

        // Query the launcher for all user-facing launchable apps
        PackageManager pm = getPackageManager();
        Intent launchIntent = new Intent(Intent.ACTION_MAIN, null);
        launchIntent.addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> resolved = pm.queryIntentActivities(launchIntent, 0);

        // De-duplicate by package, apply exclusions, sort by label
        Set<String> seen = new HashSet<>();
        // Store as {label, pkg, resolveInfo} triples
        List<Object[]> appList = new ArrayList<>();
        for (ResolveInfo ri : resolved) {
            String pkg = ri.activityInfo.packageName;
            if (!seen.add(pkg)) continue;
            if (pkg.equals("com.lumi.uxpatcher")) continue;
            if (ALWAYS_ALLOWED.contains(pkg)) continue;
            if (isSystemPrefix(pkg)) continue;
            String label = ri.loadLabel(pm).toString();
            appList.add(new Object[]{label, pkg, ri});
        }
        appList.sort((a, b) -> ((String) a[0]).compareToIgnoreCase((String) b[0]));

        if (appList.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("No user-installed apps found.");
            empty.setTextColor(COLOR_DIM);
            empty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            empty.setPadding(dp(4), dp(16), dp(4), dp(16));
            list.addView(empty);
        }

        int iconDp = dp(40);
        for (Object[] app : appList) {
            String label = (String) app[0];
            String pkg   = (String) app[1];
            ResolveInfo ri = (ResolveInfo) app[2];

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(rounded(COLOR_CARD, 10));
            row.setPadding(dp(12), dp(10), dp(12), dp(10));
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.setMargins(0, 0, 0, dp(6));
            list.addView(row, rowLp);

            // App icon
            ImageView iconView = new ImageView(this);
            iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
            try {
                Drawable icon = ri.loadIcon(pm);
                iconView.setImageDrawable(icon);
            } catch (Exception e) {
                iconView.setImageDrawable(null);
            }
            LinearLayout.LayoutParams iconLp = new LinearLayout.LayoutParams(iconDp, iconDp);
            iconLp.rightMargin = dp(12);
            row.addView(iconView, iconLp);

            // Label + package name
            LinearLayout textCol = new LinearLayout(this);
            textCol.setOrientation(LinearLayout.VERTICAL);

            TextView nameTv = new TextView(this);
            nameTv.setText(label);
            nameTv.setTextColor(COLOR_TEXT);
            nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            nameTv.setTypeface(Typeface.DEFAULT_BOLD);
            textCol.addView(nameTv);

            TextView pkgTv = new TextView(this);
            pkgTv.setText(pkg);
            pkgTv.setTextColor(COLOR_DIM);
            pkgTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            textCol.addView(pkgTv);

            row.addView(textCol, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            CheckBox cb = new CheckBox(this);
            cb.setChecked(saved.contains(pkg));
            int[][] states = {{android.R.attr.state_checked}, {}};
            cb.setButtonTintList(new android.content.res.ColorStateList(states,
                    new int[]{COLOR_ACCENT, COLOR_DIM}));
            row.addView(cb);

            row.setOnClickListener(v -> cb.setChecked(!cb.isChecked()));

            AppEntry entry = new AppEntry();
            entry.pkg   = pkg;
            entry.label = label;
            entry.cb    = cb;
            entries.add(entry);
        }

        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // ── Save button — saves then returns to MainActivity ──────────────────────────────
        Button saveBtn = new Button(this);
        saveBtn.setText("Save");
        saveBtn.setTextColor(COLOR_TEXT);
        saveBtn.setAllCaps(false);
        saveBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        saveBtn.setTypeface(Typeface.DEFAULT_BOLD);
        saveBtn.setBackground(rounded(COLOR_SAVE, 8));
        saveBtn.setPadding(dp(16), dp(12), dp(16), dp(12));
        saveBtn.setOnClickListener(v -> saveAndReturn(saveBtn));
        LinearLayout.LayoutParams saveLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        saveLp.setMargins(dp(16), dp(8), dp(16), dp(16));
        root.addView(saveBtn, saveLp);

        setContentView(root);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────────────

    private Bitmap drawArrowBack(int sizePx, int color) {
        Bitmap bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setColor(color);
        float stroke = sizePx / 11f;
        paint.setStrokeWidth(stroke);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStyle(Paint.Style.STROKE);

        float mid  = sizePx * 0.5f;
        float left = sizePx * 0.18f;
        float right= sizePx * 0.82f;
        float head = sizePx * 0.28f;   // length of each arrowhead arm

        // Horizontal shaft
        canvas.drawLine(left, mid, right, mid, paint);
        // Arrowhead (upper arm)
        canvas.drawLine(left, mid, left + head, mid - head, paint);
        // Arrowhead (lower arm)
        canvas.drawLine(left, mid, left + head, mid + head, paint);

        return bmp;
    }

    private static boolean isSystemPrefix(String pkg) {
        for (String prefix : SYSTEM_PREFIXES) {
            if (pkg.startsWith(prefix)) return true;
        }
        return false;
    }

    private Set<String> loadSavedSet() {
        String csv = prefs.getString(Prefs.KEY_GAMEPAD_WHITELIST, "");
        Set<String> s = new HashSet<>();
        if (csv != null && !csv.isEmpty()) {
            for (String p : csv.split(",")) {
                p = p.trim();
                if (!p.isEmpty()) s.add(p);
            }
        }
        return s;
    }

    private void saveAndReturn(Button saveBtn) {
        saveBtn.setEnabled(false);
        saveBtn.setText("Saving…");

        StringBuilder sb = new StringBuilder();
        for (AppEntry e : entries) {
            if (e.cb.isChecked()) {
                if (sb.length() > 0) sb.append(',');
                sb.append(e.pkg);
            }
        }
        String csv = sb.toString();

        prefs.edit().putString(Prefs.KEY_GAMEPAD_WHITELIST, csv).apply();

        final String cmd = "settings put global " + Prefs.G_GAMEPAD_WHITELIST + " " + csv;
        new Thread(() -> {
            runAsRoot(cmd);
            runOnUiThread(() -> {
                saveBtn.setText("Saved ✓");
                // Brief flash of confirmation, then go back
                saveBtn.postDelayed(() -> finish(), 700);
            });
        }, "UXPatcher-picker-save").start();
    }

    @SuppressWarnings("deprecation")
    private SharedPreferences openPrefs() {
        try {
            return getSharedPreferences(Prefs.FILE, Context.MODE_WORLD_READABLE);
        } catch (Throwable t) {
            return getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE);
        }
    }

    private static boolean runAsRoot(String cmd) {
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            new BufferedReader(new InputStreamReader(proc.getInputStream())).lines().forEach(
                    l -> Log.d(TAG, "su: " + l));
            new BufferedReader(new InputStreamReader(proc.getErrorStream())).lines().forEach(
                    l -> Log.w(TAG, "su err: " + l));
            int exit = proc.waitFor();
            Log.i(TAG, "su cmd=[" + cmd + "] exit=" + exit);
            return exit == 0;
        } catch (Exception e) {
            Log.e(TAG, "runAsRoot failed", e);
            return false;
        }
    }

    private GradientDrawable rounded(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        return d;
    }

    private int dp(int dp) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp,
                getResources().getDisplayMetrics()));
    }
}
