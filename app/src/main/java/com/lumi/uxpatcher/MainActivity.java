package com.lumi.uxpatcher;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Settings screen: colour pickers, dock and library switches, and a button that force-stops the patched apps. Saved settings are mirrored to Settings.Global through root. */
public class MainActivity extends Activity {

    private static final String TAG = "UXPatcher";
    private static final String VRSHELL = "com.oculus.vrshell";
    private static final String SYSTEMUX = "com.oculus.systemux";

    /** Force-stop order: apps first, VrShell last (it relaunches everything). Quick Settings dies with Settings. */
    private static final List<String> KILL_ORDER = Arrays.asList(
            "com.oculus.panelapp.settings",
            "com.oculus.browser",
            "com.oculus.metacam",
            "com.oculus.hzosgallery",
            "com.oculus.store",
            "com.oculus.tv",
            "com.oculus.helpcenter",
            "com.oculus.systemutilities",
            "com.oculus.socialplatform",
            "com.oculus.horizon",
            SYSTEMUX,
            "com.oculus.panelapp.library",
            VRSHELL
    );

    // Same palette as Settings Patcher
    private static final int COLOR_BG      = Color.parseColor("#0F0F1A");
    private static final int COLOR_CARD    = Color.parseColor("#1A1A2E");
    private static final int COLOR_ACCENT  = Color.parseColor("#7B68EE");
    private static final int COLOR_TEXT    = Color.WHITE;
    private static final int COLOR_DIM     = Color.parseColor("#9090B0");
    private static final int COLOR_KILL    = Color.parseColor("#C62828");
    private static final int COLOR_BUTTON2 = Color.parseColor("#2A2A44");

    private SharedPreferences prefs;
    private Button killBtn;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = openPrefs();

        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        getWindow().setStatusBarColor(COLOR_BG);
        getWindow().setNavigationBarColor(COLOR_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(COLOR_BG);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(20), dp(16), dp(20), dp(8));
        scroll.addView(content, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

        // background always has a colour; accent and text can be off (Meta's own)
        new ColorCard(content, "BACKGROUND COLOR", Prefs.KEY_BG, false, 0x000000, "Reset");
        new ColorCard(content, "ACCENT COLOR (icons, sliders)", Prefs.KEY_ACCENT, true, 0xFFFFFF, "Default");
        new ColorCard(content, "TEXT COLOR", Prefs.KEY_TEXT, true, 0xFFFFFF, "Default");
        buildDockCard(content);

        killBtn = new Button(this);
        killBtn.setText("Kill All Processes");
        killBtn.setTextColor(COLOR_TEXT);
        killBtn.setAllCaps(false);
        killBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        killBtn.setTypeface(Typeface.DEFAULT_BOLD);
        killBtn.setBackground(rounded(COLOR_KILL, 8));
        killBtn.setPadding(dp(16), dp(12), dp(16), dp(12));
        killBtn.setOnClickListener(v -> forceStopAllProcesses());
        LinearLayout.LayoutParams killLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        killLp.setMargins(dp(20), dp(8), dp(20), dp(16));
        root.addView(killBtn, killLp);

        setContentView(root);
        requestRoot();
    }

    // ── Cards ─────────────────────────────────────────────────────────────────────────────

    private void buildDockCard(LinearLayout parent) {
        LinearLayout card = card(parent);
        sectionLabel(card, "DOCK");

        prefSwitch(card, "Hide profile icon", Prefs.KEY_HIDE_PROFILE, false);
        prefSwitch(card, "Raise pin limit to " + Config.DOCK_PIN_LIMIT, Prefs.KEY_PIN_LIMIT, true);

        LinearLayout lib = card(parent);
        sectionLabel(lib, "LIBRARY");
        prefSwitch(lib, "Force Unknown Sources tab visible", Prefs.KEY_UNKNOWN_TAB, true);
    }

    /** A saved on/off switch, showing {@code def} until the user touches it */
    private void prefSwitch(LinearLayout card, String label, String key, boolean def) {
        Switch sw = new Switch(this);
        sw.setText(label);
        sw.setTextColor(COLOR_TEXT);
        sw.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new ColorStateList(states, new int[]{COLOR_ACCENT, COLOR_DIM}));
        sw.setTrackTintList(new ColorStateList(states,
                new int[]{Color.parseColor("#557B68EE"), Color.parseColor("#33FFFFFF")}));
        sw.setChecked(prefs.getBoolean(key, def));
        sw.setOnCheckedChangeListener((b, checked) -> {
            prefs.edit().putBoolean(key, checked).apply();
            pushToGlobal();
            toastSaved();
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(6), 0, 0);
        card.addView(sw, lp);
    }

    // ── Colour handling ───────────────────────────────────────────────────────────────────

    /** "#RRGGBB", "RRGGBB", "#RGB", "0xRRGGBB" -> RGB int, or null when it is not a colour. */
    private static Integer parseHex(String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.startsWith("#")) t = t.substring(1);
        else if (t.toLowerCase().startsWith("0x")) t = t.substring(2);
        if (t.length() == 3) {
            t = "" + t.charAt(0) + t.charAt(0) + t.charAt(1) + t.charAt(1) + t.charAt(2) + t.charAt(2);
        }
        if (t.length() != 6) return null;
        try {
            return Integer.parseInt(t, 16);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Collapsible colour picker: preview, hex field, R/G/B sliders, reset and Set Color buttons.
     * The left button resets to {@code defRgb} (optional == false) or turns the setting off (stores Prefs.OFF).
     */
    private final class ColorCard {
        final String key;
        final boolean optional;
        final int defRgb;
        int red, green, blue;
        boolean off;
        boolean updating;                  // set while the code itself changes the sliders / hex field
        View preview, swatch;
        EditText hexEdit;
        TextView summary, chevron, status;
        LinearLayout body;
        final SeekBar[] bars = new SeekBar[3];

        ColorCard(LinearLayout parent, String title, String key, boolean optional, int defRgb, String resetText) {
            this.key = key;
            this.optional = optional;
            this.defRgb = defRgb;

            int saved = prefs.getInt(key, optional ? Prefs.OFF : (Prefs.DEFAULT_BG & 0xFFFFFF));
            off = optional && saved == Prefs.OFF;
            int rgb = off ? defRgb : (saved & 0xFFFFFF);
            red = Color.red(rgb);
            green = Color.green(rgb);
            blue = Color.blue(rgb);

            LinearLayout card = card(parent);

            // ── header: title, colour, chevron ────────────────────────────────────────────
            LinearLayout header = new LinearLayout(MainActivity.this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);
            TextView tv = new TextView(MainActivity.this);
            tv.setText(title);
            tv.setTextColor(COLOR_ACCENT);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
            tv.setTypeface(Typeface.DEFAULT_BOLD);
            header.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

            swatch = new View(MainActivity.this);
            LinearLayout.LayoutParams swLp = new LinearLayout.LayoutParams(dp(18), dp(18));
            swLp.rightMargin = dp(8);
            header.addView(swatch, swLp);

            summary = new TextView(MainActivity.this);
            summary.setTextColor(COLOR_DIM);
            summary.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            summary.setTypeface(Typeface.MONOSPACE);
            header.addView(summary);

            chevron = new TextView(MainActivity.this);
            chevron.setTextColor(COLOR_DIM);
            chevron.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            chevron.setPadding(dp(10), 0, 0, 0);
            header.addView(chevron);
            card.addView(header, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

            // ── body (collapsed by default) ───────────────────────────────────────────────
            body = new LinearLayout(MainActivity.this);
            body.setOrientation(LinearLayout.VERTICAL);
            body.setVisibility(View.GONE);
            LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bodyLp.topMargin = dp(12);
            card.addView(body, bodyLp);
            header.setOnClickListener(v -> {
                body.setVisibility(body.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
                refresh();
            });

            LinearLayout head = new LinearLayout(MainActivity.this);
            head.setOrientation(LinearLayout.VERTICAL);
            head.setGravity(Gravity.CENTER_HORIZONTAL);

            preview = new View(MainActivity.this);
            head.addView(preview, new LinearLayout.LayoutParams(dp(72), dp(72)));

            hexEdit = new EditText(MainActivity.this);
            hexEdit.setTextColor(COLOR_TEXT);
            hexEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            hexEdit.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            hexEdit.setGravity(Gravity.CENTER);
            hexEdit.setSingleLine(true);
            hexEdit.setSelectAllOnFocus(true);
            hexEdit.setBackground(rounded(Color.parseColor("#22FFFFFF"), 10));
            hexEdit.setPadding(dp(14), dp(8), dp(14), dp(8));
            hexEdit.setInputType(android.text.InputType.TYPE_CLASS_TEXT
                    | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    | android.text.InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
            hexEdit.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
            hexEdit.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(10)});
            hexEdit.addTextChangedListener(new android.text.TextWatcher() {
                @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) { }
                @Override public void onTextChanged(CharSequence c, int a, int b, int d) { }
                @Override public void afterTextChanged(android.text.Editable e) {
                    if (updating) return;
                    Integer v = parseHex(e.toString());
                    if (v == null) return;                  // still typing
                    setRgb(v);
                    off = false;
                    refresh(false);
                }
            });
            hexEdit.setOnEditorActionListener((v, action, ev) -> {
                hexEdit.clearFocus();
                refresh();                                   // rewrite as #RRGGBB
                return false;
            });
            hexEdit.setOnFocusChangeListener((v, focus) -> { if (!focus) refresh(); });
            LinearLayout.LayoutParams hexLp = new LinearLayout.LayoutParams(dp(170),
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            hexLp.topMargin = dp(10);
            head.addView(hexEdit, hexLp);

            status = new TextView(MainActivity.this);
            status.setTextColor(COLOR_DIM);
            status.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            LinearLayout.LayoutParams stLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            stLp.topMargin = dp(6);
            head.addView(status, stLp);

            LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            headLp.bottomMargin = dp(8);
            body.addView(head, headLp);

            bars[0] = slider(body, "Red", red, Color.parseColor("#EF5350"), v -> { if (!updating) { red = v; touched(); } });
            bars[1] = slider(body, "Green", green, Color.parseColor("#66BB6A"), v -> { if (!updating) { green = v; touched(); } });
            bars[2] = slider(body, "Blue", blue, Color.parseColor("#42A5F5"), v -> { if (!updating) { blue = v; touched(); } });

            LinearLayout buttons = new LinearLayout(MainActivity.this);
            buttons.setOrientation(LinearLayout.HORIZONTAL);

            Button reset = flatButton(resetText, COLOR_BUTTON2);
            reset.setOnClickListener(v -> {
                setRgb(defRgb);
                off = optional;
                refresh();
                save();
            });
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            rl.rightMargin = dp(6);
            buttons.addView(reset, rl);

            Button set = flatButton("Set Color", COLOR_ACCENT);
            set.setOnClickListener(v -> {
                // use the hex field as typed
                Integer typed = parseHex(hexEdit.getText().toString());
                if (typed != null) setRgb(typed);
                off = false;
                hexEdit.clearFocus();
                refresh();
                save();
            });
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            sl.leftMargin = dp(6);
            buttons.addView(set, sl);

            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            bl.topMargin = dp(8);
            body.addView(buttons, bl);

            refresh();
        }

        /** Moves the sliders to {@code rgb} without firing their listeners */
        void setRgb(int rgb) {
            red = Color.red(rgb); green = Color.green(rgb); blue = Color.blue(rgb);
            updating = true;
            bars[0].setProgress(red); bars[1].setProgress(green); bars[2].setProgress(blue);
            updating = false;
        }

        /** A slider moved: custom colour preview, saved on "Set Color" */
        void touched() {
            off = false;
            refresh();
        }

        void refresh() { refresh(true); }

        /** @param rewriteHex false while the user types in the hex field */
        void refresh(boolean rewriteHex) {
            int rgb = Color.rgb(red, green, blue);
            String hex = String.format("#%02X%02X%02X", red, green, blue);

            GradientDrawable d = new GradientDrawable();
            d.setShape(GradientDrawable.OVAL);
            d.setColor(off ? (rgb & 0x00FFFFFF) | 0x66000000 : rgb);
            d.setStroke(dp(2), COLOR_DIM);
            preview.setBackground(d);

            GradientDrawable sw = new GradientDrawable();
            sw.setShape(GradientDrawable.OVAL);
            sw.setColor(off ? (rgb & 0x00FFFFFF) | 0x66000000 : rgb);
            sw.setStroke(dp(1), COLOR_DIM);
            swatch.setBackground(sw);

            summary.setText(off ? "Meta default" : hex);
            chevron.setText(body.getVisibility() == View.VISIBLE ? "\u25BE" : "\u25B8");
            status.setText(off ? "Not applied: Meta's own colour is used" : "Custom colour");

            if (rewriteHex) {
                String now = hexEdit.getText().toString();
                if (!now.equals(hex)) {
                    updating = true;
                    hexEdit.setText(hex);
                    updating = false;
                }
            }
        }

        void save() {
            prefs.edit().putInt(key, off ? Prefs.OFF : Color.rgb(red, green, blue) & 0xFFFFFF).apply();
            pushToGlobal();
            toastSaved();
        }
    }

    private void toastSaved() {
        Toast.makeText(this, "Saved — tap Kill Processes to apply", Toast.LENGTH_SHORT).show();
    }

    private interface IntConsumer { void accept(int v); }

    /** One colour channel: label, value and a 0..255 SeekBar */
    private SeekBar slider(LinearLayout parent, String label, int start, int tint, IntConsumer onChange) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(rounded(Color.parseColor("#22FFFFFF"), 10));
        box.setPadding(dp(14), dp(10), dp(14), dp(6));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(COLOR_TEXT);
        name.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        final TextView value = new TextView(this);
        value.setText(String.valueOf(start));
        value.setTextColor(COLOR_DIM);
        value.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        row.addView(value);
        box.addView(row);

        SeekBar bar = new SeekBar(this);
        bar.setMax(255);
        bar.setProgress(start);
        ColorStateList tl = ColorStateList.valueOf(tint);
        bar.setProgressTintList(tl);
        bar.setThumbTintList(tl);
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#44FFFFFF")));
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                value.setText(String.valueOf(p));
                onChange.accept(p);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
        box.addView(bar, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        parent.addView(box, lp);
        return bar;
    }

    // ── Settings storage ──────────────────────────────────────────────────────────────────

    /** World-readable so hooked processes can read it; private if refused */
    @SuppressWarnings("deprecation")
    private SharedPreferences openPrefs() {
        try {
            return getSharedPreferences(Prefs.FILE, Context.MODE_WORLD_READABLE);
        } catch (Throwable t) {
            Log.w(TAG, "MODE_WORLD_READABLE refused (" + t + "), using private prefs + Settings.Global only");
            return getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE);
        }
    }

    /** Mirrors the settings to Settings.Global using root */
    private void pushToGlobal() {
        final String[] cmds = globalCommands();
        new Thread(() -> { for (String c : cmds) runAsRoot(c); }, "UXPatcher-push").start();
    }

    /** `settings put global ...` for every setting */
    private String[] globalCommands() {
        int bg = prefs.getInt(Prefs.KEY_BG, Prefs.DEFAULT_BG) & 0xFFFFFF;
        int accentV = prefs.getInt(Prefs.KEY_ACCENT, Prefs.OFF);
        int textV = prefs.getInt(Prefs.KEY_TEXT, Prefs.OFF);
        String accent = accentV == Prefs.OFF ? "off" : String.valueOf(accentV);
        String text = textV == Prefs.OFF ? "off" : String.valueOf(textV);
        boolean hide = prefs.getBoolean(Prefs.KEY_HIDE_PROFILE, false);
        boolean pinLimit = prefs.getBoolean(Prefs.KEY_PIN_LIMIT, true);
        boolean unknownTab = prefs.getBoolean(Prefs.KEY_UNKNOWN_TAB, true);
        return new String[]{
                "settings put global " + Prefs.G_PIN_LIMIT + " " + (pinLimit ? 1 : 0),
                "settings put global " + Prefs.G_UNKNOWN_TAB + " " + (unknownTab ? 1 : 0),
                "settings put global " + Prefs.G_BG + " " + bg,
                "settings put global " + Prefs.G_ACCENT + " " + accent,
                "settings put global " + Prefs.G_TEXT + " " + text,
                "settings put global " + Prefs.G_HIDE + " " + (hide ? 1 : 0)};
    }

    // ── Root / kill ───────────────────────────────────────────────────────────────────────

    /** Asked on open so the root prompt shows now, not on the first kill */
    private void requestRoot() {
        new Thread(() -> runAsRoot("echo ok"), "UXPatcher-root").start();
    }

    private static List<String> killOrder() {
        return KILL_ORDER;
    }

    private void forceStopAllProcesses() {
        killBtn.setEnabled(false);
        killBtn.setText("Stopping…");
        // latest settings into Settings.Global before the restart
        final String[] cmds = globalCommands();

        new Thread(() -> {
            for (String c : cmds) runAsRoot(c);

            // one root shell for all force-stops, so the processes die together
            List<String> order = killOrder();
            StringBuilder all = new StringBuilder();
            for (String pkg : order) {
                if (all.length() > 0) all.append("; ");
                all.append("am force-stop ").append(pkg);
            }
            boolean success = runAsRoot(all.toString());
            int stopped = success ? order.size() : 0, failed = success ? 0 : order.size();
            final int ok = stopped, bad = failed;
            runOnUiThread(() -> {
                killBtn.setEnabled(true);
                killBtn.setText("Kill Processes");
                Toast.makeText(this, bad == 0 ? "Done"
                        : ok + " stopped, " + bad + " failed — is root granted?",
                        Toast.LENGTH_LONG).show();
            });
        }, "UXPatcher-kill").start();
    }

    /** Runs `su -c cmd`. @return true if it exited with 0 */
    private static boolean runAsRoot(String cmd) {
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader stdout = new BufferedReader(new InputStreamReader(proc.getInputStream()));
            BufferedReader stderr = new BufferedReader(new InputStreamReader(proc.getErrorStream()));
            String line;
            while ((line = stdout.readLine()) != null) Log.d(TAG, "su stdout: " + line);
            while ((line = stderr.readLine()) != null) Log.w(TAG, "su stderr: " + line);
            int exit = proc.waitFor();
            Log.i(TAG, "su cmd=[" + cmd + "] exit=" + exit);
            return exit == 0;
        } catch (Exception e) {
            Log.e(TAG, "runAsRoot failed: " + cmd, e);
            return false;
        }
    }

    // ── View helpers ──────────────────────────────────────────────────────────────────────

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        card.setPadding(p, p, p, p);
        card.setBackground(rounded(COLOR_CARD, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        parent.addView(card, lp);
        return card;
    }

    private void sectionLabel(LinearLayout parent, String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(COLOR_ACCENT);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, 0, 0, dp(10));
        parent.addView(tv, lp);
    }

    private Button flatButton(String text, int color) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextColor(COLOR_TEXT);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setBackground(rounded(color, 8));
        b.setPadding(dp(16), dp(10), dp(16), dp(10));
        return b;
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
