package com.lumi.uxpatcher.amoled;

import android.util.Log;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

import com.lumi.uxpatcher.Config;
import com.lumi.uxpatcher.Prefs;

import static com.lumi.uxpatcher.Config.TAG;
import static com.lumi.uxpatcher.amoled.Colors.*;

/** Recolours fills on BaseRecordingCanvas, where Compose and hardware-accelerated views draw */
public final class RecordingCanvasHook {
    private RecordingCanvasHook() {}

    /** Restricts and customises the recolouring (used by VrShell's control bars). */
    public interface Gate {
        /** 0 = leave this draw alone; any other value is passed back to {@link #map}. */
        int mode();
        /** New ARGB for {@code color} in {@code mode}; {@code size} = rect w/h or null. */
        int map(int mode, int color, float[] size);
        /** Prefix for the UXPatcher-DRAW diagnostic lines. */
        String label(int mode);
        /** True to draw this shaded fill (gradient) as flat black, since remapping its colour does nothing */
        boolean flattenShader(int mode, float[] size);
    }

    private static final Set<String> DRAW_SEEN =
            Collections.synchronizedSet(new HashSet<String>());
    private static final int MAX_DRAW_PROBES = 60;

    private static int FLATTEN_LOGS = 0;
    private static int TILE_LOGS = 0;
    private static int HOVER_LOGS = 0;
    /** Which grey-tile rule applies in this process (see Colors.tileMode), set by AmoledHooks */
    public static volatile int tileMode = Colors.TILES_OFF;
    private static boolean hooked = false;
    private static Gate gate = null;

    /** Once per process. A null gate recolours every fill. */
    public static synchronized void install(Gate g) {
        if (hooked) return;
        hooked = true;
        gate = g;

        // LOAD-BEARING: this turns Quick Settings and every Compose panel black.
        // BaseRecordingCanvas overrides drawRect / drawRoundRect / drawPath, so hooks on Canvas are bypassed.
        Class<?> rc;
        try {
            rc = Class.forName("android.graphics.BaseRecordingCanvas");
        } catch (Throwable t) {
            Log.w(TAG, "DRAW: BaseRecordingCanvas not found: " + t);
            return;
        }
        final XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    Object last = param.args[param.args.length - 1];
                    if (!(last instanceof android.graphics.Paint)) return;
                    android.graphics.Paint paint = (android.graphics.Paint) last;
                    int color = paint.getColor();
                    int mode = 0;
                    if (gate != null) {
                        mode = gate.mode();
                        if (mode == 0) return;          // not inside a gated view
                    }
                    probeDraw(param, paint, color, mode);
                    if (paint.getShader() != null) {
                        // colour is irrelevant while a shader is set; only a gate may flatten it
                        if (gate != null && gate.flattenShader(mode, drawSize(param.args))) {
                            param.setObjectExtra("savedShader", paint.getShader());
                            param.setObjectExtra("savedColor", color);
                            paint.setShader(null);
                            paint.setColor(Prefs.bg());
                            if (FLATTEN_LOGS++ < 6) {
                                float[] wh = drawSize(param.args);
                                Log.i(TAG, "DRAW: flattened " + param.method.getName() + " shader to black "
                                        + (wh == null ? "" : wh[0] + "x" + wh[1]));
                            }
                        }
                        return;
                    }
                    int mapped = gate != null
                            ? gate.map(mode, color, drawSize(param.args))
                            : remap(color);
                    if (mapped == color && tileMode != Colors.TILES_OFF && gate == null) {
                        float[] wh = drawSize(param.args);
                        if (isTileFill(tileMode, param.method.getName(), color, wh)) {
                            mapped = Prefs.bg();
                            if (TILE_LOGS++ < 16) {
                                Log.i("UXPatcher-DRAW", "TILE: " + param.method.getName() + " #" + Integer.toHexString(color)
                                        + " " + wh[0] + "x" + wh[1] + " -> background");
                            }
                        }
                    }
                    if (mapped == color && tileMode != Colors.TILES_OFF && gate == null
                            && Config.TILE_HOVER_BG) {
                        float[] wh = drawSize(param.args);
                        if (Colors.isTileHover(tileMode, param.method.getName(), color, wh)) {
                            mapped = (color & 0xFF000000) | (Prefs.bg() & 0x00FFFFFF);
                            if (HOVER_LOGS++ < 16) {
                                Log.i("UXPatcher-DRAW", "HOVER: " + param.method.getName() + " #" + Integer.toHexString(color)
                                        + " " + wh[0] + "x" + wh[1] + " -> background");
                            }
                        }
                    }
                    if (mapped != color) {
                        param.setObjectExtra("savedColor", color);
                        paint.setColor(mapped);
                    }
                } catch (Throwable ignored) { }
            }
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                // restore the shared Paint
                try {
                    android.graphics.Paint p = (android.graphics.Paint) param.args[param.args.length - 1];
                    Object sh = param.getObjectExtra("savedShader");
                    if (sh instanceof android.graphics.Shader) p.setShader((android.graphics.Shader) sh);
                    Integer saved = (Integer) param.getObjectExtra("savedColor");
                    if (saved != null) p.setColor(saved);
                } catch (Throwable ignored) { }
            }
        };
        Class<?> rectF = android.graphics.RectF.class;
        Class<?> paintC = android.graphics.Paint.class;
        Object[][] sigs = {
                {"drawRect", float.class, float.class, float.class, float.class, paintC},
                {"drawRect", rectF, paintC},
                {"drawRoundRect", float.class, float.class, float.class, float.class,
                        float.class, float.class, paintC},
                {"drawRoundRect", rectF, float.class, float.class, paintC},
                {"drawPath", android.graphics.Path.class, paintC},
        };
        for (Object[] sig : sigs) {
            try {
                Class<?>[] types = new Class<?>[sig.length - 1];
                for (int i = 1; i < sig.length; i++) types[i - 1] = (Class<?>) sig[i];
                XposedHelpers.findAndHookMethod(rc, (String) sig[0], appendHook(types, h));
                Log.d(TAG, "DRAW: hooked BaseRecordingCanvas." + sig[0] + "/" + (sig.length - 1));
            } catch (Throwable t) {
                Log.w(TAG, "DRAW: could not hook " + sig[0] + ": " + t);
            }
        }
    }

    private static Object[] appendHook(Class<?>[] types, XC_MethodHook h) {
        Object[] out = new Object[types.length + 1];
        System.arraycopy(types, 0, out, 0, types.length);
        out[types.length] = h;
        return out;
    }

    /** Logs each distinct (call, colour, shader) once (tag UXPatcher-DRAW) */
    private static void probeDraw(XC_MethodHook.MethodHookParam param,
                                  android.graphics.Paint paint, int color, int mode) {
        if (DRAW_SEEN.size() >= MAX_DRAW_PROBES) return;
        String shader = paint.getShader() == null ? "-" : paint.getShader().getClass().getSimpleName();
        float[] wh = drawSize(param.args);
        String size = wh == null ? "" : wh[0] + "x" + wh[1];
        String key = (gate != null ? gate.label(mode) : "")
                + param.method.getName() + " #" + Integer.toHexString(color) + " shader=" + shader
                + " style=" + paint.getStyle() + (paint.getShader() != null ? " alpha=" + paint.getAlpha() : "");
        if (DRAW_SEEN.add(key)) {
            Log.i("UXPatcher-DRAW", key + " size=" + size);
        }
    }

    /** Width/height of a rect-like draw call, or null */
    public static float[] drawSize(Object[] a) {
        try {
            if (a.length >= 5 && a[0] instanceof Float) {
                return new float[]{(float) a[2] - (float) a[0], (float) a[3] - (float) a[1]};
            }
            if (a[0] instanceof android.graphics.RectF) {
                android.graphics.RectF r = (android.graphics.RectF) a[0];
                return new float[]{r.width(), r.height()};
            }
            if (a[0] instanceof android.graphics.Path) {
                android.graphics.RectF r = new android.graphics.RectF();
                ((android.graphics.Path) a[0]).computeBounds(r, true);
                return new float[]{r.width(), r.height()};
            }
        } catch (Throwable ignored) { }
        return null;
    }
}
