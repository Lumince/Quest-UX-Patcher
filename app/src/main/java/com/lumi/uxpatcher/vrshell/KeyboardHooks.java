package com.lumi.uxpatcher.vrshell;

import android.graphics.BlendModeColorFilter;
import android.graphics.ColorFilter;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.GradientDrawable;
import android.content.res.ColorStateList;
import android.graphics.Paint;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.lumi.uxpatcher.Prefs;
import com.lumi.uxpatcher.amoled.PanelBackgroundHook;
import com.lumi.uxpatcher.amoled.RecordingCanvasHook;
import com.lumi.uxpatcher.amoled.ThemeHooks;
import com.lumi.uxpatcher.firmware.FirmwareNames;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static com.lumi.uxpatcher.Config.TAG;

/** Recolours the VrShell on-screen keyboard. Its keys are drawn into a bitmap, so everything is gated on "a keyboard view is drawing". */
public final class KeyboardHooks {
    private KeyboardHooks() {}

    /** How far keys are lightened towards white from the background (0..1). */
    private static final float KEY_LIGHTEN = 0.12f;
    /** Fills with every channel below this are dark grey (panel or key). */
    private static final int DARK_MAX = 100;
    /** Dark grey up to this brightness is the panel, brighter is a key. */
    private static final int PANEL_MAX = 48;

    /** Key shapes are ~100-130 px tall, bigger than the usual icon size gate. */
    private static final float KEY_SHAPE_MAX_SIDE = 400f;

    /** true: icons and shapes use the text colour instead of the accent (accent if text is off). */
    private static final boolean ICONS_USE_TEXT = true;

    private static final String[] COLOR_CALLS = {
            "drawRect", "drawRoundRect", "drawPath", "drawCircle", "drawOval", "drawArc"};
    private static final String[] TEXT_CALLS = {"drawText", "drawTextRun"};

    private static final ThreadLocal<int[]> DEPTH = new ThreadLocal<int[]>() {
        @Override protected int[] initialValue() { return new int[1]; }
    };
    /** Keyboard views / methods drawing on this thread (innermost last), for the log. */
    private static final ThreadLocal<java.util.ArrayList<String>> WHO = new ThreadLocal<java.util.ArrayList<String>>() {
        @Override protected java.util.ArrayList<String> initialValue() { return new java.util.ArrayList<>(); }
    };
    private static final ConcurrentHashMap<Class<?>, Boolean> IS_KEYBOARD = new ConcurrentHashMap<>();
    private static final Set<String> SEEN = Collections.synchronizedSet(new HashSet<String>());
    private static final Map<String, Integer> KIND_COUNT = new HashMap<>();
    private static final int MAX_LOGS_PER_KIND = 60;

    /** True while a keyboard view is drawing on this thread. */
    public static boolean active() {
        return DEPTH.get()[0] > 0;
    }

    public static void install(LoadPackageParam lpparam) {
        int n = 0;

        // ── the gate ──────────────────────────────────────────────────────────────────────
        final XC_MethodHook enter = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (isKeyboard(param.thisObject.getClass())) {
                        logView(param.thisObject);
                        if (param.thisObject instanceof ViewGroup && "dispatchDraw".equals(param.method.getName())
                                && param.thisObject.getClass().getSimpleName().equals("KeyboardPanelView")) {
                            onPanelPass((ViewGroup) param.thisObject);
                        }
                        DEPTH.get()[0]++;
                        WHO.get().add(param.thisObject.getClass().getSimpleName() + "." + param.method.getName());
                        param.setObjectExtra("kbGate", Boolean.TRUE);
                    }
                } catch (Throwable ignored) { }
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.getObjectExtra("kbGate") != null) {
                    DEPTH.get()[0]--;
                    java.util.ArrayList<String> w = WHO.get();
                    if (!w.isEmpty()) w.remove(w.size() - 1);
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(View.class, "draw", android.graphics.Canvas.class, enter);
            XposedHelpers.findAndHookMethod(ViewGroup.class, "dispatchDraw", android.graphics.Canvas.class, enter);
            n += 2;
        } catch (Throwable t) {
            Log.w(TAG, "KEYBOARD: View.draw / dispatchDraw hook failed: " + t);
        }

        // gate every method of the key views, so the obfuscated bitmap renderer is covered without its name
        final XC_MethodHook always = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                DEPTH.get()[0]++;
                WHO.get().add(param.method.getDeclaringClass().getSimpleName() + "." + param.method.getName());
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                DEPTH.get()[0]--;
                java.util.ArrayList<String> w = WHO.get();
                if (!w.isEmpty()) w.remove(w.size() - 1);
            }
        };
        for (String name : FirmwareNames.KEYBOARD_KEY_VIEWS) {
            try {
                Class<?> c = lpparam.classLoader.loadClass(name);
                int m = 0;
                for (Method meth : c.getDeclaredMethods()) {
                    if (Modifier.isAbstract(meth.getModifiers()) || Modifier.isNative(meth.getModifiers())) continue;
                    String mn = meth.getName();
                    if (mn.equals("draw") || mn.equals("dispatchDraw")) continue;      // already gated above
                    try { XposedBridge.hookMethod(meth, always); m++; } catch (Throwable ignored) { }
                }
                n += m;
                Log.i(TAG, "KEYBOARD: gated " + m + " methods of " + name);
            } catch (ClassNotFoundException e) {
                Log.i(TAG, "KEYBOARD: " + name + " not found (no key renderer in this build)");
            } catch (Throwable t) {
                Log.w(TAG, "KEYBOARD: could not gate " + name + ": " + t);
            }
        }

        // other keyboard views: methods and constructors, since their drawables are tinted during setup
        for (String name : FirmwareNames.KEYBOARD_GATE_CLASSES) {
            try {
                Class<?> c = lpparam.classLoader.loadClass(name);
                int m = 0;
                for (Method meth : c.getDeclaredMethods()) {
                    if (Modifier.isAbstract(meth.getModifiers()) || Modifier.isNative(meth.getModifiers())) continue;
                    String mn = meth.getName();
                    if (mn.equals("draw") || mn.equals("dispatchDraw")) continue;
                    try { XposedBridge.hookMethod(meth, always); m++; } catch (Throwable ignored) { }
                }
                for (java.lang.reflect.Constructor<?> k : c.getDeclaredConstructors()) {
                    try { XposedBridge.hookMethod(k, always); m++; } catch (Throwable ignored) { }
                }
                n += m;
                Log.i(TAG, "KEYBOARD: gated " + m + " methods/constructors of " + name);
            } catch (ClassNotFoundException e) {
                Log.i(TAG, "KEYBOARD: " + name + " not found");
            } catch (Throwable t) {
                Log.w(TAG, "KEYBOARD: could not gate " + name + ": " + t);
            }
        }

        // ── canvas re-colouring while the gate is open (software + hardware canvases) ──────
        // Canvas overrides the draw calls, so it is hooked too; CanvasHook has a re-entrancy guard
        for (String cls : new String[]{"android.graphics.Canvas", "android.graphics.BaseCanvas",
                "android.graphics.BaseRecordingCanvas"}) {
            Class<?> c;
            try {
                c = Class.forName(cls);
            } catch (Throwable t) {
                Log.w(TAG, "KEYBOARD: " + cls + " not found: " + t);
                continue;
            }
            for (String call : COLOR_CALLS) n += hookAll(c, call, new CanvasHook(false));
            for (String call : TEXT_CALLS) n += hookAll(c, call, new CanvasHook(true));
            n += hookAll(c, "drawBitmap", new BitmapHook());
            n += hookAll(c, "drawColor", new DrawColorHook());
        }

        // ── drawable tints ────────────────────────────────────────────────────────────────
        n += installTintHooks();
        n += installDrawableHooks();

        // ── the glass panel behind the keys ───────────────────────────────────────────────
        PanelBackgroundHook.install(lpparam, KeyboardHooks::panelGate);

        Log.i(TAG, "KEYBOARD: " + n + " methods hooked in " + lpparam.packageName
                + " (bg=#" + String.format("%06X", Prefs.bg() & 0xFFFFFF)
                + ", accent=" + (Prefs.accent() == 0 ? "off" : String.format("#%06X", Prefs.accent() & 0xFFFFFF))
                + ", text=" + (Prefs.text() == 0 ? "off" : String.format("#%06X", Prefs.text() & 0xFFFFFF)) + ")");
    }


    // ── "themed only after the first tap" safety net ──────────────────────────────────────
    private static long sLastPass, sFirstPass;
    private static int sPassCount;

    /** Per KeyboardPanelView draw: logs the first passes and records the time for {@link #panelGate}. */
    private static void onPanelPass(final ViewGroup panel) {
        try {
            long now = android.os.SystemClock.uptimeMillis();
            boolean reopened = now - sLastPass > 3000;
            sLastPass = now;
            if (reopened) { sFirstPass = now; sPassCount = 0; }
            sPassCount++;
            if (sPassCount <= 12) {
                Log.i("UXPatcher-DRAW", "KB pass #" + sPassCount + " KeyboardPanelView t=+" + (now - sFirstPass) + "ms"
                        + (reopened ? " (open)" : "") + " alpha=" + panel.getAlpha() + " layer=" + panel.getLayerType()
                        + " hw=" + panel.isHardwareAccelerated() + " vis=" + panel.getVisibility()
                        + " " + panel.getWidth() + "x" + panel.getHeight());
            }
        } catch (Throwable ignored) { }
    }

    // ── glass panels drawn OUTSIDE the keyboard's own draw (wrapper / host panel) ─────────
    private static final long RECENT_MS = 20000;
    private static int sPanelLogs;

    /** True while the keyboard draws, or for large panel backgrounds within 20 s of a keyboard draw. */
    private static boolean panelGate(android.graphics.drawable.Drawable d) {
        if (active()) return true;
        try {
            if (android.os.SystemClock.uptimeMillis() - sLastPass > RECENT_MS || sLastPass == 0) return false;
            android.graphics.Rect b = d.getBounds();
            boolean big = b.width() >= 300 && b.height() >= 150;
            if (sPanelLogs++ < 20) {
                Log.i("UXPatcher-DRAW", "KB panel background drawn outside the keyboard gate: " + b.width() + "x" + b.height()
                        + " callback=" + (d.getCallback() == null ? "null" : d.getCallback().getClass().getName())
                        + (big ? " -> black" : " (too small, kept)"));
            }
            return big;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isKeyboard(Class<?> c) {
        Boolean b = IS_KEYBOARD.get(c);
        if (b == null) {
            String name = c.getName();
            boolean is = false;
            for (String p : FirmwareNames.KEYBOARD_VIEW_PREFIXES) {
                if (name.startsWith(p)) { is = true; break; }
            }
            b = is;
            IS_KEYBOARD.put(c, b);
        }
        return b;
    }

    private static int hookAll(Class<?> c, String name, XC_MethodHook h) {
        try {
            return XposedBridge.hookAllMethods(c, name, h).size();
        } catch (Throwable t) {
            Log.w(TAG, "KEYBOARD: could not hook " + c.getSimpleName() + "." + name + ": " + t);
            return 0;
        }
    }

    // ── canvas hooks ──────────────────────────────────────────────────────────────────────

    private static final ThreadLocal<int[]> INNER = new ThreadLocal<int[]>() {
        @Override protected int[] initialValue() { return new int[1]; }
    };

    private static final class CanvasHook extends XC_MethodHook {
        private final boolean text;
        CanvasHook(boolean text) { this.text = text; }

        @Override protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (!gateOpen(param.thisObject)) return;
                int[] inner = INNER.get();
                if (inner[0] > 0) return;                       // Canvas.drawX calls BaseCanvas.drawX: map once
                Object last = param.args[param.args.length - 1];
                if (!(last instanceof Paint)) return;
                inner[0]++;
                param.setObjectExtra("kbOuter", Boolean.TRUE);
                Paint paint = (Paint) last;
                String call = param.method.getName();
                firstCall(param.thisObject, call);
                if (paint.getShader() == null) {
                    int color = paint.getColor();
                    int mapped = text ? mapText(color, call, param.args) : mapFill(color, call, param.args);
                    if (mapped != color) {
                        param.setObjectExtra("kbColor", color);
                        paint.setColor(mapped);
                    }
                } else {
                    log("shaded-kept", call + " " + sizeOf(param.args) + " " + paint.getShader().getClass().getSimpleName(),
                            paint.getColor(), paint.getColor());
                }
                ColorFilter f = paint.getColorFilter();
                if (f != null) {
                    ColorFilter mf = iconFilter(f, call);
                    if (mf != f) {
                        param.setObjectExtra("kbFilter", f);
                        paint.setColorFilter(mf);
                    }
                }
            } catch (Throwable t) {
                logOnce("canvas hook threw " + t);
            }
        }

        @Override protected void afterHookedMethod(MethodHookParam param) {
            if (param.getObjectExtra("kbOuter") == null) return;
            INNER.get()[0]--;
            restore(param);
        }
    }

    /** Only the paint's colour filter matters here (tinted icon bitmaps). */
    private static final class BitmapHook extends XC_MethodHook {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (!active()) return;
                Object last = param.args[param.args.length - 1];
                if (!(last instanceof Paint)) return;
                Paint paint = (Paint) last;
                ColorFilter f = paint.getColorFilter();
                if (f != null) {
                    ColorFilter mf = iconFilter(f, "drawBitmap");
                    if (mf != f) {
                        param.setObjectExtra("kbFilter", f);
                        paint.setColorFilter(mf);
                    }
                }
            } catch (Throwable ignored) { }
        }
        @Override protected void afterHookedMethod(MethodHookParam param) {
            restore(param);
        }
    }

    /** The key renderer clears / fills with drawColor. */
    private static final class DrawColorHook extends XC_MethodHook {
        @Override protected void beforeHookedMethod(MethodHookParam param) {
            try {
                if (!active() || !(param.args[0] instanceof Integer)) return;
                int color = (Integer) param.args[0];
                int mapped = mapFill(color, "drawColor", param.args);
                if (mapped != color) param.args[0] = mapped;
            } catch (Throwable ignored) { }
        }
    }

    private static void restore(XC_MethodHook.MethodHookParam param) {
        try {
            Paint p = (Paint) param.args[param.args.length - 1];
            Object c = param.getObjectExtra("kbColor");
            if (c instanceof Integer) p.setColor((Integer) c);
            Object f = param.getObjectExtra("kbFilter");
            if (f instanceof ColorFilter) p.setColorFilter((ColorFilter) f);
        } catch (Throwable ignored) { }
    }

    // ── fallback gate ─────────────────────────────────────────────────────────────────────

    private static int sFallbackChecks, sFallbackHits;

    /** The gate, plus a stack-check fallback for software canvases only. Gives up after 3000 misses. */
    private static boolean gateOpen(Object canvas) {
        if (active()) return true;
        if (sFallbackChecks > 3000 && sFallbackHits == 0) return false;
        if (canvas.getClass().getName().contains("Recording")) return false;
        sFallbackChecks++;
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            if (isKeyboardName(e.getClassName())) {
                if (sFallbackHits++ < 3) {
                    logOnce("software canvas drew from the keyboard but the gate was closed (" + e.getClassName()
                            + "." + e.getMethodName() + ") - using stack fallback");
                }
                return true;
            }
        }
        return false;
    }

    private static boolean isKeyboardName(String name) {
        for (String p : FirmwareNames.KEYBOARD_VIEW_PREFIXES) {
            if (name.startsWith(p)) return true;
        }
        return false;
    }

    private static void logOnce(String msg) {
        if (SEEN.add("once|" + msg)) Log.i("UXPatcher-DRAW", "KB " + msg);
    }

    // ── colour rules ──────────────────────────────────────────────────────────────────────

    private static int mapText(int color, String call, Object[] args) {
        int out = color;
        if (Prefs.text() != 0 && (color & 0xFFFFFF) == (Prefs.text() & 0xFFFFFF)) {
            out = color;                      // already the text colour
        } else if ((color >>> 24) >= 0x90 && ThemeHooks.isCyan(color) && iconRgb() != 0) {
            out = (color & 0xFF000000) | (iconRgb() & 0x00FFFFFF);               // cyan label -> icon colour
        } else {
            out = ThemeHooks.mapText(color, call);                               // white / grey -> text colour
        }
        log("text", call + " " + sizeOf(args), color, out);
        return out;
    }

    private static int mapFill(int color, String call, Object[] args) {
        int a = color >>> 24;
        if (a == 0) return color;
        int bg = Prefs.bg();
        if ((color & 0xFFFFFF) == (bg & 0xFFFFFF)) return color;
        if ((color & 0xFFFFFF) == (keyLight(bg) & 0xFFFFFF)) return color;      // already a mapped key colour

        int out = color;
        int icon = iconRgb();
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        float s = mx == 0 ? 0f : (mx - mn) / (float) mx;
        float[] wh = RecordingCanvasHook.drawSize(args);
        boolean big = wh != null && Math.min(wh[0], wh[1]) >= 40f && Math.max(wh[0], wh[1]) >= 300f;   // top bar / panel sized

        if (icon != 0 && a >= 0x90 && ThemeHooks.isCyan(color)) {
            out = (color & 0xFF000000) | (icon & 0x00FFFFFF);                      // cyan -> icon colour
        } else if (icon != 0 && a >= 0x90 && s <= 0.20f && mx >= 153
                && (wh == null || Math.min(wh[0], wh[1]) <= KEY_SHAPE_MAX_SIDE)) {
            float v = mx / 255f;                                                    // white shape (enter key, mic circle)
            out = ThemeHooks.blend(bg, icon, v, a);
        } else if (a >= 0x10 && a < 0xA0 && s <= 0.25f && mx >= 160 && !inKeyBitmap() && big) {
            out = 0xFF000000 | (bg & 0x00FFFFFF);                                  // translucent white bar -> background
        } else if (mx < DARK_MAX && s <= 0.25f && a >= 0x60) {                   // dark grey: panel, bar or key
            int rgb = (mx <= PANEL_MAX || (big && !inKeyBitmap())) ? bg : keyLight(bg);
            out = (a << 24) | (rgb & 0x00FFFFFF);
        }
        log("fill", call + " " + sizeOf(args), color, out);
        return out;
    }

    private static int keyLight(int bg) {
        return ThemeHooks.blend(bg, 0xFFFFFFFF, KEY_LIGHTEN, 0xFF);
    }

    /** True while the key bitmap itself is rendered. */
    private static boolean inKeyBitmap() {
        java.util.ArrayList<String> w = WHO.get();
        for (int i = w.size() - 1; i >= 0; i--) {
            String x = w.get(i);
            if (x.startsWith("KeyboardView.") || x.startsWith("KeyboardPopupView.")) return true;
        }
        return false;
    }

    /** RGB for icons and shapes: text colour (ICONS_USE_TEXT) or accent, 0 = none. */
    private static int iconRgb() {
        int text = Prefs.text(), acc = Prefs.accent();
        if (ICONS_USE_TEXT && text != 0) return text & 0x00FFFFFF;
        return acc != 0 ? (acc & 0x00FFFFFF) : 0;
    }

    /** Swaps a tint ThemeHooks made accent for iconRgb. */
    private static int keyIconColor(int orig, int mapped) {
        if (mapped == orig) return orig;
        int rgb = iconRgb();
        return rgb == 0 ? mapped : (orig & 0xFF000000) | rgb;
    }

    /** Gate open, or a keyboard class is on the call stack (slow path). */
    private static boolean inKeyboardNow() {
        if (active()) return true;
        for (StackTraceElement e : new Throwable().getStackTrace()) {
            if (isKeyboardName(e.getClassName())) return true;
        }
        return false;
    }

    private static final Map<Long, ColorFilter> ICON_FILTERS = new HashMap<>();

    /** Icon colour filter: ThemeHooks first, then iconRgb inside the keyboard. */
    private static ColorFilter iconFilter(ColorFilter f, String call) {
        ColorFilter m = ThemeHooks.mapFilter(f, call, true);
        if (m == f || !inKeyboardNow()) return f;           // not recoloured, or not the keyboard
        int rgb = iconRgb();
        if (rgb == 0) return m;
        try {
            int c;
            Object mode;
            boolean blend = m instanceof BlendModeColorFilter;
            if (blend) {
                c = ((BlendModeColorFilter) m).getColor();
                mode = ((BlendModeColorFilter) m).getMode();
            } else {
                c = (Integer) XposedHelpers.callMethod(m, "getColor");
                mode = XposedHelpers.callMethod(m, "getMode");
            }
            int nc = (c & 0xFF000000) | rgb;
            long key = ((long) nc << 8) | (blend ? 0x80 : 0) | ((Enum<?>) mode).ordinal();
            synchronized (ICON_FILTERS) {
                ColorFilter cached = ICON_FILTERS.get(key);
                if (cached == null) {
                    cached = blend ? new BlendModeColorFilter(nc, (android.graphics.BlendMode) mode)
                                   : new PorterDuffColorFilter(nc, (android.graphics.PorterDuff.Mode) mode);
                    ICON_FILTERS.put(key, cached);
                }
                return cached;
            }
        } catch (Throwable t) {
            return m;
        }
    }

    /** Colour and mode of a filter, for the log. */
    private static String describeFilter(ColorFilter f) {
        try {
            if (f instanceof BlendModeColorFilter) {
                return "#" + Integer.toHexString(((BlendModeColorFilter) f).getColor()) + " " + ((BlendModeColorFilter) f).getMode();
            }
            if (f instanceof PorterDuffColorFilter) {
                return "#" + Integer.toHexString((Integer) XposedHelpers.callMethod(f, "getColor")) + " " + XposedHelpers.callMethod(f, "getMode");
            }
        } catch (Throwable ignored) { }
        return String.valueOf(f == null ? null : f.getClass().getSimpleName());
    }

    /** A wide, tall-enough shape: the top bar, not a key highlight or divider. */
    private static boolean isBarSized(Object[] args) {
        float[] wh = RecordingCanvasHook.drawSize(args);
        return wh != null && Math.min(wh[0], wh[1]) >= 40f && Math.max(wh[0], wh[1]) >= 300f;
    }

    private static String sizeOf(Object[] args) {
        float[] wh = RecordingCanvasHook.drawSize(args);
        return wh == null ? "" : Math.round(wh[0]) + "x" + Math.round(wh[1]);
    }

    // ── tints ─────────────────────────────────────────────────────────────────────────────

    private static int installTintHooks() {
        int n = 0;
        XC_MethodHook csl = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!(param.args[0] instanceof ColorStateList)) return;
                    ColorStateList in = (ColorStateList) param.args[0];
                    int[] colors = (int[]) XposedHelpers.getObjectField(in, "mColors");
                    int[][] specs = (int[][]) XposedHelpers.getObjectField(in, "mStateSpecs");
                    int[] mapped = new int[colors.length];
                    boolean changed = false;
                    boolean wouldChange = false;
                    for (int i = 0; i < colors.length; i++) {
                        mapped[i] = ThemeHooks.mapTintCyan(colors[i]);
                        if (mapped[i] != colors[i]) wouldChange = true;
                    }
                    if (!wouldChange || !inKeyboardNow()) return;     // everything else is left to the general theme hook
                    for (int i = 0; i < colors.length; i++) {
                        mapped[i] = keyIconColor(colors[i], mapped[i]);
                        if (mapped[i] != colors[i]) {
                            changed = true;
                            log("tint", param.method.getDeclaringClass().getSimpleName(), colors[i], mapped[i]);
                        }
                    }
                    if (changed) param.args[0] = new ColorStateList(specs, mapped);
                } catch (Throwable ignored) { }
            }
        };
        Class<?>[] drawables = {android.graphics.drawable.Drawable.class,
                android.graphics.drawable.VectorDrawable.class, android.graphics.drawable.BitmapDrawable.class,
                android.graphics.drawable.GradientDrawable.class, android.graphics.drawable.LayerDrawable.class,
                android.graphics.drawable.DrawableContainer.class, android.graphics.drawable.StateListDrawable.class,
                android.graphics.drawable.InsetDrawable.class, android.graphics.drawable.RippleDrawable.class,
                android.graphics.drawable.ShapeDrawable.class};
        for (Class<?> c : drawables) n += hookAll(c, "setTintList", csl);
        try { n += hookAll(Class.forName("android.graphics.drawable.DrawableWrapper"), "setTintList", csl); }
        catch (Throwable ignored) { }

        XC_MethodHook cf = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!(param.args[0] instanceof ColorFilter)) return;
                    ColorFilter in = (ColorFilter) param.args[0];
                    ColorFilter out = iconFilter(in, "Drawable.setColorFilter");
                    if (out != in) param.args[0] = out;
                    if (out != in || active()) logFilter(param.method.getDeclaringClass().getSimpleName() + ".setColorFilter", in, out);
                } catch (Throwable ignored) { }
            }
        };
        for (Class<?> c : drawables) {
            try {
                for (Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals("setColorFilter") && !Modifier.isAbstract(m.getModifiers())
                            && m.getParameterTypes().length == 1
                            && m.getParameterTypes()[0] == ColorFilter.class) {
                        XposedBridge.hookMethod(m, cf);
                        n++;
                    }
                }
            } catch (Throwable ignored) { }
        }
        return n;
    }

    // ── drawable-level fallback ───────────────────────────────────────────────────────────

    private static java.lang.reflect.Field sFillPaint;

    /** Recolours GradientDrawable fills (top bar, input field, mic circle) directly, as they skip the canvas hooks. */
    private static int installDrawableHooks() {
        try {
            sFillPaint = GradientDrawable.class.getDeclaredField("mFillPaint");
            sFillPaint.setAccessible(true);
        } catch (Throwable t) {
            Log.w(TAG, "KEYBOARD: GradientDrawable.mFillPaint not accessible: " + t);
            return 0;
        }
        return hookAll(GradientDrawable.class, "draw", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!active()) return;
                    GradientDrawable d = (GradientDrawable) param.thisObject;
                    Paint p = (Paint) sFillPaint.get(d);
                    if (p == null || p.getShader() != null) return;
                    int c = p.getColor();
                    android.graphics.Rect b = d.getBounds();
                    Object[] size = {0f, 0f, (float) b.width(), (float) b.height(), null};
                    int mapped = mapFill(c, "GradientDrawable", size);
                    if (mapped != c) {
                        param.setObjectExtra("kbPaint", p);
                        param.setObjectExtra("kbPaintColor", c);
                        p.setColor(mapped);
                    }
                } catch (Throwable ignored) { }
            }
            @Override protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object p = param.getObjectExtra("kbPaint");
                    Object c = param.getObjectExtra("kbPaintColor");
                    if (p instanceof Paint && c instanceof Integer) ((Paint) p).setColor((Integer) c);
                } catch (Throwable ignored) { }
            }
        });
    }

    // ── diagnostics ───────────────────────────────────────────────────────────────────────

    private static String who() {
        java.util.ArrayList<String> w = WHO.get();
        int n = w.size();
        if (n == 0) return "?";
        return n == 1 ? w.get(0) : w.get(n - 2) + " > " + w.get(n - 1);
    }

    /** Logs the first time each (canvas class, call) is reached inside the gate. */
    private static void firstCall(Object canvas, String call) {
        String key = canvas.getClass().getSimpleName() + "." + call;
        synchronized (KIND_COUNT) {
            if (!SEEN.add("first|" + key)) return;
        }
        Log.i("UXPatcher-DRAW", "KB canvas call reached inside keyboard: " + key + "  in " + who());
    }

    private static void log(String kind, String call, int from, int to) {
        synchronized (KIND_COUNT) {
            Integer n = KIND_COUNT.get(kind);
            if (n != null && n >= MAX_LOGS_PER_KIND) return;
            if (!SEEN.add(kind + "|" + call + "|" + from)) return;
            KIND_COUNT.put(kind, n == null ? 1 : n + 1);
        }
        Log.i("UXPatcher-DRAW", "KB " + kind + ": " + call + " #" + Integer.toHexString(from)
                + (to == from ? " (kept)" : " -> #" + Integer.toHexString(to)) + "  in " + who());
    }

    /** Logs size and background of each keyboard view class on its first draw. */
    private static void logView(Object v) {
        try {
            String key = "view|" + v.getClass().getName();
            if (!SEEN.add(key)) return;
            View view = (View) v;
            if (SEEN.add("firstdraw")) {
                String ver = "?";
                try {
                    android.content.Context cx = view.getContext();
                    ver = cx.getPackageManager().getPackageInfo(cx.getPackageName(), 0).versionName;
                } catch (Throwable ignored) { }
                Log.i("UXPatcher-DRAW", "KB first keyboard draw: vrshell=" + ver + " config loaded=" + Prefs.isLoaded()
                        + " bg=#" + Integer.toHexString(Prefs.bg() & 0xFFFFFF)
                        + " accent=" + (Prefs.accent() == 0 ? "off" : "#" + Integer.toHexString(Prefs.accent() & 0xFFFFFF))
                        + " text=" + (Prefs.text() == 0 ? "off" : "#" + Integer.toHexString(Prefs.text() & 0xFFFFFF)));
            }
            Object bg = view.getBackground();
            Log.i("UXPatcher-DRAW", "KB view: " + v.getClass().getSimpleName() + " " + view.getWidth() + "x" + view.getHeight()
                    + " bg=" + (bg == null ? "none" : bg.getClass().getSimpleName()));
        } catch (Throwable ignored) { }
    }

    private static void logFilter(String where, ColorFilter in, ColorFilter out) {
        int key = in == null ? 0 : in.hashCode();
        if (!SEEN.add("filter|" + where + "|" + key)) return;
        Log.i("UXPatcher-DRAW", "KB filter: " + where + " " + describeFilter(in)
                + (out == in ? " (kept)" : " -> " + describeFilter(out)) + "  in " + who());
    }
}
