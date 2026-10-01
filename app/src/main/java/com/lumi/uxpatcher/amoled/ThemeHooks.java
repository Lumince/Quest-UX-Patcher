package com.lumi.uxpatcher.amoled;

import android.content.res.ColorStateList;
import android.graphics.BlendMode;
import android.graphics.BlendModeColorFilter;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.util.Log;

import com.lumi.uxpatcher.Prefs;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static com.lumi.uxpatcher.Config.TAG;

/** Recolours Meta's white text (TEXT) and white icons, slider parts and knobs (ACCENT). Hooks nothing while both are off. */
public final class ThemeHooks {
    private ThemeHooks() {}

    private static final String[] TEXT_CALLS = {"drawText", "drawTextRun"};
    private static final String[] SHAPE_CALLS = {
            "drawRect", "drawRoundRect", "drawPath", "drawCircle", "drawOval", "drawArc", "drawLine", "drawPoint"};
    private static final String[] BITMAP_CALLS = {"drawBitmap"};

    /** Shapes with a short side above this (px) count as surfaces and are left alone */
    private static final float SHAPE_MAX_SIDE = 100f;
    /** Translucent whites below this alpha (glass, dividers) are left alone */
    private static final int MIN_ALPHA = 0x90;

    private static final Set<String> SEEN = Collections.synchronizedSet(new HashSet<String>());
    private static final int MAX_LOGS_PER_KIND = 30;
    private static final Map<String, Integer> KIND_COUNT = new HashMap<>();
    private static final Map<Long, ColorFilter> FILTERS = new HashMap<>();
    private static final WeakHashMap<ColorStateList, ColorStateList> CSL_DONE = new WeakHashMap<>();

    private static int sAccent = 0;     // opaque ARGB, 0 = off
    private static int sText = 0;
    private static boolean sHooked = false;

    public static synchronized void install(LoadPackageParam lpparam) {
        sAccent = Prefs.accent();
        sText = Prefs.text();
        if (sAccent == 0 && sText == 0) {
            Log.i(TAG, "THEME: accent and text colours are off in " + lpparam.packageName + " (nothing hooked)");
            return;
        }
        if (sHooked) return;
        sHooked = true;

        final Class<?> rc;
        try {
            rc = Class.forName("android.graphics.BaseRecordingCanvas");
        } catch (Throwable t) {
            Log.w(TAG, "THEME: BaseRecordingCanvas not found: " + t);
            return;
        }

        final XC_MethodHook textHook = new CanvasHook(CanvasHook.TEXT);
        final XC_MethodHook shapeHook = new CanvasHook(CanvasHook.SHAPE);
        final XC_MethodHook bitmapHook = new CanvasHook(CanvasHook.BITMAP);

        int n = 0;
        if (sText != 0) for (String name : TEXT_CALLS) n += hookAll(rc, name, textHook);
        if (sAccent != 0) {
            for (String name : SHAPE_CALLS) n += hookAll(rc, name, shapeHook);
            for (String name : BITMAP_CALLS) n += hookAll(rc, name, bitmapHook);
            n += installTintHooks();
        }
        Log.i(TAG, "THEME: " + n + " methods hooked in " + lpparam.packageName
                + " (accent=" + (sAccent == 0 ? "off" : String.format("#%06X", sAccent & 0xFFFFFF))
                + ", text=" + (sText == 0 ? "off" : String.format("#%06X", sText & 0xFFFFFF)) + ")");
    }

    private static int hookAll(Class<?> rc, String name, XC_MethodHook h) {
        try {
            return XposedBridge.hookAllMethods(rc, name, h).size();
        } catch (Throwable t) {
            Log.w(TAG, "THEME: could not hook " + name + ": " + t);
            return 0;
        }
    }

    // ── canvas ────────────────────────────────────────────────────────────────────────────

    // Swaps the Paint colour / filter for the call, restores it after (Compose re-uses one Paint)
    private static final class CanvasHook extends XC_MethodHook {
        static final int TEXT = 0, SHAPE = 1, BITMAP = 2;
        private final int kind;
        CanvasHook(int kind) { this.kind = kind; }

        @Override protected void beforeHookedMethod(MethodHookParam param) {
            try {
                Object last = param.args[param.args.length - 1];
                if (!(last instanceof Paint)) return;
                Paint paint = (Paint) last;
                String call = param.method.getName();

                if (kind != BITMAP && paint.getShader() == null) {
                    int color = paint.getColor();
                    int mapped = kind == TEXT ? mapText(color, call) : mapShape(color, call, param.args);
                    if (mapped != color) {
                        param.setObjectExtra("themeColor", color);
                        paint.setColor(mapped);
                    }
                }
                if (kind != TEXT) {
                    ColorFilter f = paint.getColorFilter();
                    if (f != null) {
                        ColorFilter mf = mapFilter(f, call);
                        if (mf != f) {
                            param.setObjectExtra("themeFilter", f);
                            paint.setColorFilter(mf);
                        }
                    }
                }
            } catch (Throwable ignored) { }
        }

        @Override protected void afterHookedMethod(MethodHookParam param) {
            try {
                Paint p = (Paint) param.args[param.args.length - 1];
                Object saved = param.getObjectExtra("themeColor");
                if (saved instanceof Integer) p.setColor((Integer) saved);
                Object f = param.getObjectExtra("themeFilter");
                if (f instanceof ColorFilter) p.setColorFilter((ColorFilter) f);
            } catch (Throwable ignored) { }
        }
    }

    // ── colour rules ──────────────────────────────────────────────────────────────────────

    /** Brightness 0..1 of a white / light grey of at least minV (of 255), else -1 */
    private static float neutralBrightness(int color, int minV) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        float v = mx / 255f;
        float s = mx == 0 ? 0f : (mx - mn) / (float) mx;
        return (s <= 0.20f && v * 255f >= minV) ? v : -1f;
    }

    private static boolean isBackground(int color) {
        return (color & 0xFFFFFF) == (Prefs.bg() & 0xFFFFFF);
    }

    /** White / light-grey text -> the text colour (greys stay dimmer) */
    public static int mapText(int color, String call) {
        if (sText == 0) return color;
        int a = color >>> 24;
        if (a == 0 || isBackground(color)) return color;
        float v = neutralBrightness(color, 128);
        if (v < 0) {
            probe("text-kept", call, color);
            return color;
        }
        int out = blend(Prefs.bg(), sText, v, a);
        log("text", call, color, out);
        return out;
    }

    /** Small white-ish shape -> accent. */
    public static int mapShape(int color, String call, Object[] args) {
        return mapShape(color, call, args, SHAPE_MAX_SIDE);
    }

    /** @param maxSide short side above which a shape counts as a surface */
    public static int mapShape(int color, String call, Object[] args, float maxSide) {
        if (sAccent == 0) return color;
        int a = color >>> 24;
        if (a < MIN_ALPHA || isBackground(color)) return color;
        float v = neutralBrightness(color, 153);
        if (v < 0) return color;
        float[] wh = RecordingCanvasHook.drawSize(args);
        if (wh != null) {
            float mn = Math.min(wh[0], wh[1]);
            if (mn > maxSide) {
                probe("shape-big-kept", call + " " + Math.round(wh[0]) + "x" + Math.round(wh[1]), color);
                return color;
            }
        }
        int out = blend(Prefs.bg(), sAccent, v, a);
        log("accent", call + (wh == null ? "" : " " + Math.round(wh[0]) + "x" + Math.round(wh[1])), color, out);
        return out;
    }

    /** White-ish tint colour -> accent */
    public static int mapTint(int color) {
        if (sAccent == 0) return color;
        int a = color >>> 24;
        if (a < MIN_ALPHA || isBackground(color)) return color;
        float v = neutralBrightness(color, 153);
        if (v < 0) return color;
        return blend(Prefs.bg(), sAccent, v, a);
    }

    /** White tint filter (SRC_IN / SRC_ATOP) -> same filter in the accent colour */
    public static ColorFilter mapFilter(ColorFilter f, String call) {
        return mapFilter(f, call, false);
    }

    /** @param cyanToo also replace Meta's cyan (keyboard icons) */
    public static ColorFilter mapFilter(ColorFilter f, String call, boolean cyanToo) {
        try {
            int color;
            boolean blendMode = f instanceof BlendModeColorFilter;
            Object mode;
            if (blendMode) {
                color = ((BlendModeColorFilter) f).getColor();
                mode = ((BlendModeColorFilter) f).getMode();
                if (mode != BlendMode.SRC_IN && mode != BlendMode.SRC_ATOP) return f;
            } else if (f instanceof PorterDuffColorFilter) {
                color = (Integer) XposedHelpers.callMethod(f, "getColor");
                mode = XposedHelpers.callMethod(f, "getMode");
                if (mode != PorterDuff.Mode.SRC_IN && mode != PorterDuff.Mode.SRC_ATOP) return f;
            } else {
                return f;
            }
            int out = cyanToo ? mapTintCyan(color) : mapTint(color);
            if (out == color) return f;
            long key = ((long) out << 8) | (blendMode ? 0x80 : 0) | ((Enum<?>) mode).ordinal();
            synchronized (FILTERS) {
                ColorFilter cached = FILTERS.get(key);
                if (cached == null) {
                    cached = blendMode ? new BlendModeColorFilter(out, (BlendMode) mode)
                                       : new PorterDuffColorFilter(out, (PorterDuff.Mode) mode);
                    FILTERS.put(key, cached);
                }
                log("accent-filter", call, color, out);
                return cached;
            }
        } catch (Throwable t) {
            return f;
        }
    }

    static float hue(int r, int g, int b, int mx, int mn) {
        float d = mx - mn;
        if (d == 0) return 0;
        float h;
        if (mx == r) h = ((g - b) / d) % 6f;
        else if (mx == g) h = (b - r) / d + 2f;
        else h = (r - g) / d + 4f;
        h *= 60f;
        return h < 0 ? h + 360f : h;
    }

    /** Meta's cyan (hue 175..235, saturated, bright): keyboard icons and labels */
    public static boolean isCyan(int color) {
        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
        int mx = Math.max(r, Math.max(g, b)), mn = Math.min(r, Math.min(g, b));
        if (mx == 0) return false;
        float s = (mx - mn) / (float) mx, v = mx / 255f;
        if (s < 0.5f || v < 0.5f) return false;
        float h = hue(r, g, b, mx, mn);
        return h >= 175f && h <= 235f;
    }

    /** {@link #mapTint} that also maps cyan to the accent */
    public static int mapTintCyan(int color) {
        int acc = Prefs.accent();
        if (acc != 0 && (color >>> 24) >= MIN_ALPHA && isCyan(color)) {
            return (color & 0xFF000000) | (acc & 0x00FFFFFF);
        }
        return mapTint(color);
    }

    public static int blend(int from, int to, float f, int alpha) {
        int fr = (from >> 16) & 0xFF, fg = (from >> 8) & 0xFF, fb = from & 0xFF;
        int tr = (to >> 16) & 0xFF, tg = (to >> 8) & 0xFF, tb = to & 0xFF;
        int r = Math.round(fr + (tr - fr) * f);
        int g = Math.round(fg + (tg - fg) * f);
        int b = Math.round(fb + (tb - fb) * f);
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }

    // ── set-time tints (ImageView / Drawable) ─────────────────────────────────────────────

    private static int installTintHooks() {
        int n = 0;
        // ImageView.setColorFilter(int) / (int, Mode)
        try { n += XposedBridge.hookAllMethods(android.widget.ImageView.class, "setColorFilter", intColorHook()).size(); }
        catch (Throwable t) { Log.w(TAG, "THEME: ImageView.setColorFilter: " + t); }

        // ColorStateList tints
        XC_MethodHook cslHook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!(param.args[0] instanceof ColorStateList)) return;
                    ColorStateList in = (ColorStateList) param.args[0];
                    ColorStateList out = mapCsl(in, param.method.getDeclaringClass().getSimpleName());
                    if (out != in) param.args[0] = out;
                } catch (Throwable ignored) { }
            }
        };
        Object[][] targets = {
                {android.widget.ImageView.class, "setImageTintList"},
                {android.graphics.drawable.Drawable.class, "setTintList"},
                {android.graphics.drawable.VectorDrawable.class, "setTintList"},
                {android.graphics.drawable.BitmapDrawable.class, "setTintList"},
                {android.graphics.drawable.GradientDrawable.class, "setTintList"},
                {android.graphics.drawable.LayerDrawable.class, "setTintList"},
        };
        for (Object[] t : targets) {
            try {
                n += XposedBridge.hookAllMethods((Class<?>) t[0], (String) t[1], cslHook).size();
            } catch (Throwable e) {
                Log.w(TAG, "THEME: " + ((Class<?>) t[0]).getSimpleName() + "." + t[1] + ": " + e);
            }
        }
        // Drawable.setColorFilter(ColorFilter)
        XC_MethodHook cfHook = new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!(param.args[0] instanceof ColorFilter)) return;
                    ColorFilter in = (ColorFilter) param.args[0];
                    ColorFilter out = mapFilter(in, "Drawable.setColorFilter");
                    if (out != in) param.args[0] = out;
                } catch (Throwable ignored) { }
            }
        };
        for (Class<?> c : new Class<?>[]{android.graphics.drawable.Drawable.class,
                android.graphics.drawable.VectorDrawable.class, android.graphics.drawable.BitmapDrawable.class,
                android.graphics.drawable.GradientDrawable.class, android.widget.ImageView.class}) {
            try {
                for (java.lang.reflect.Method m : c.getDeclaredMethods()) {
                    if (m.getName().equals("setColorFilter") && !java.lang.reflect.Modifier.isAbstract(m.getModifiers())
                            && m.getParameterTypes().length == 1
                            && m.getParameterTypes()[0] == ColorFilter.class) {
                        XposedBridge.hookMethod(m, cfHook);
                        n++;
                    }
                }
            } catch (Throwable e) {
                Log.w(TAG, "THEME: " + c.getSimpleName() + ".setColorFilter(ColorFilter): " + e);
            }
        }
        // log which vector icons are drawn and their tint
        try {
            XposedHelpers.findAndHookMethod(android.graphics.drawable.VectorDrawable.class, "draw",
                    android.graphics.Canvas.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        synchronized (KIND_COUNT) {
                            Integer cnt = KIND_COUNT.get("vector-icon");
                            if (cnt != null && cnt >= MAX_LOGS_PER_KIND) return;
                        }
                        android.graphics.drawable.Drawable d = (android.graphics.drawable.Drawable) param.thisObject;
                        Object cb = d.getCallback();
                        String owner = cb == null ? "null" : cb.getClass().getName();
                        ColorStateList tint = null;
                        try {
                            Object st = XposedHelpers.getObjectField(d, "mVectorState");
                            tint = (ColorStateList) XposedHelpers.getObjectField(st, "mTint");
                        } catch (Throwable ignored) { }
                        String t = tint == null ? "no-tint" : "tint=#" + Integer.toHexString(tint.getDefaultColor());
                        String f = d.getColorFilter() == null ? "" : " filter=" + d.getColorFilter().getClass().getSimpleName();
                        if (room("vector-icon", owner + t + f)) {
                            Log.i("UXPatcher-DRAW", "THEME vector icon: owner=" + owner + " " + t + f);
                        }
                    } catch (Throwable ignored) { }
                }
            });
            n++;
        } catch (Throwable e) {
            Log.w(TAG, "THEME: VectorDrawable.draw probe: " + e);
        }
        return n;
    }

    /** ImageView.setColorFilter(int) and (int, Mode): maps the colour */
    private static XC_MethodHook intColorHook() {
        return new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args[0] instanceof Integer) {
                        int c = (Integer) param.args[0];
                        int m = mapTint(c);
                        if (m != c) { log("accent-imageview-filter", "setColorFilter", c, m); param.args[0] = m; }
                    }
                } catch (Throwable ignored) { }
            }
        };
    }

    private static ColorStateList mapCsl(ColorStateList in, String where) {
        synchronized (CSL_DONE) {
            ColorStateList known = CSL_DONE.get(in);
            if (known != null) return known;
        }
        ColorStateList out = in;
        try {
            int[] colors = (int[]) XposedHelpers.getObjectField(in, "mColors");
            int[][] specs = (int[][]) XposedHelpers.getObjectField(in, "mStateSpecs");
            int[] mapped = new int[colors.length];
            boolean changed = false;
            for (int i = 0; i < colors.length; i++) {
                mapped[i] = mapTint(colors[i]);
                if (mapped[i] != colors[i]) changed = true;
            }
            if (changed) {
                out = new ColorStateList(specs, mapped);
                log("accent-tint", where + ".setTintList", in.getDefaultColor(), out.getDefaultColor());
            }
        } catch (Throwable ignored) { }
        synchronized (CSL_DONE) { CSL_DONE.put(in, out); CSL_DONE.put(out, out); }
        return out;
    }

    // ── diagnostics ───────────────────────────────────────────────────────────────────────

    /** True (and counted) while a new distinct line of this kind may still be logged */
    private static boolean room(String kind, String key) {
        synchronized (KIND_COUNT) {
            Integer n = KIND_COUNT.get(kind);
            if (n != null && n >= MAX_LOGS_PER_KIND) return false;
            if (!SEEN.add(kind + "|" + key)) return false;
            KIND_COUNT.put(kind, n == null ? 1 : n + 1);
            return true;
        }
    }

    /** Logs a mapping to logcat (tag UXPatcher-DRAW) */
    private static void log(String kind, String call, int from, int to) {
        if (room(kind, call + from)) {
            Log.i("UXPatcher-DRAW", "THEME " + kind + ": " + call + " #" + Integer.toHexString(from)
                    + " -> #" + Integer.toHexString(to));
        }
    }

    /** Logs colours that matched no rule, to show what else a screen uses */
    private static void probe(String kind, String call, int color) {
        if (room(kind, call + color)) {
            Log.i("UXPatcher-DRAW", "THEME " + kind + ": " + call + " #" + Integer.toHexString(color));
        }
    }
}
