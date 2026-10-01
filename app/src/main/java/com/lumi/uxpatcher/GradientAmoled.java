package com.lumi.uxpatcher;

import android.graphics.Color;
import android.graphics.LinearGradient;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * AMOLED for Compose gradients: Compose ends up in android.graphics.LinearGradient, so dark colours
 * (R, G, B below the threshold) are rewritten there to the background colour, keeping alpha.
 * LOAD-BEARING: keep the 8-arg branch in rewrite(). Compose uses that constructor on current
 * firmware, without it the Settings sidebar fades show as grey bands.
 */
final class GradientAmoled {

    private static final String TAG = "UXPatcher";
    private static final int MAX_LOGS = 20;
    private static int logged = 0;

    private GradientAmoled() {}

    static void install(final LoadPackageParam lpparam, final int threshold) {
        try {
            XposedBridge.hookAllConstructors(LinearGradient.class, new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        probe(param.args);
                        rewrite(param.args, threshold);
                    } catch (Throwable ignored) {
                        // never break drawing
                    }
                }
            });
            Log.i(TAG, "GRADIENT: hooked LinearGradient constructors in " + lpparam.packageName);
        } catch (Throwable t) {
            Log.e(TAG, "GRADIENT: hook failed in " + lpparam.packageName, t);
        }
    }

    private static int probed = 0;

    /** Logs the first gradients (before rewrite) to see what draws fades */
    private static void probe(Object[] a) {
        if (probed >= 40) return;
        probed++;
        StringBuilder sb = new StringBuilder("args=").append(a.length).append(' ');
        for (Object o : a) {
            if (o instanceof int[]) {
                sb.append("int[");
                for (int c : (int[]) o) sb.append('#').append(Integer.toHexString(c)).append(' ');
                sb.append("] ");
            } else if (o instanceof long[]) {
                sb.append("long[");
                for (long l : (long[]) o) {
                    try { sb.append('#').append(Integer.toHexString(Color.valueOf(l).toArgb())).append(' '); }
                    catch (Throwable t) { sb.append(l).append(' '); }
                }
                sb.append("] ");
            } else if (o instanceof float[]) {
                sb.append(java.util.Arrays.toString((float[]) o)).append(' ');
            } else {
                sb.append(o).append(' ');
            }
        }
        Log.i("UXPatcher-GRAD", sb.toString());
    }

    private static void rewrite(Object[] a, int threshold) {
        // 8-arg private constructor (x0,y0,x1,y1, long[] colors, float[] pos, tile, ColorSpace):
        // repack all colours as sRGB and set the ColorSpace to match
        if (a.length == 8 && a[4] instanceof long[]
                && a[7] instanceof android.graphics.ColorSpace) {
            long[] src = (long[]) a[4];
            int[] orig = new int[src.length];
            int[] argb = new int[src.length];
            boolean changed = false;
            for (int i = 0; i < src.length; i++) {
                orig[i] = argb[i] = Color.valueOf(src[i]).toArgb();
                int m = remap(argb[i], threshold);
                if (m != argb[i]) {
                    argb[i] = m;
                    changed = true;
                }
            }
            if (changed) {
                long[] out = new long[src.length];
                for (int i = 0; i < out.length; i++) out[i] = Color.pack(argb[i]);
                a[4] = out;
                a[7] = android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.SRGB);
                note("8arg-long[]", orig, argb);
            }
            return;
        }

        if (a.length != 7) return;
        Object c4 = a[4];
        Object c5 = a[5];

        if (c4 instanceof int[]) {
            int[] src = (int[]) c4;
            int[] out = null;
            for (int i = 0; i < src.length; i++) {
                int m = remap(src[i], threshold);
                if (m != src[i]) {
                    if (out == null) out = src.clone();
                    out[i] = m;
                }
            }
            if (out != null) {
                a[4] = out;
                note("int[]", src, out);
            }

        } else if (c4 instanceof long[]) {
            // colour longs may be in different spaces: decode to ARGB, and on a change
            // repack the whole array as sRGB (one common space required)
            long[] src = (long[]) c4;
            int[] argb = new int[src.length];
            int[] orig = new int[src.length];
            boolean changed = false;
            for (int i = 0; i < src.length; i++) {
                orig[i] = argb[i] = Color.valueOf(src[i]).toArgb();
                int m = remap(argb[i], threshold);
                if (m != argb[i]) {
                    argb[i] = m;
                    changed = true;
                }
            }
            if (changed) {
                long[] out = new long[src.length];
                for (int i = 0; i < out.length; i++) out[i] = Color.pack(argb[i]);
                a[4] = out;
                note("long[]", orig, argb);
            }

        } else if (c4 instanceof Integer && c5 instanceof Integer) {
            int m0 = remap((Integer) c4, threshold);
            int m1 = remap((Integer) c5, threshold);
            if (m0 != (Integer) c4 || m1 != (Integer) c5) {
                a[4] = m0;
                a[5] = m1;
                note("2xint", new int[]{(Integer) c4, (Integer) c5}, new int[]{m0, m1});
            }

        } else if (c4 instanceof Long && c5 instanceof Long) {
            int s0 = Color.valueOf((Long) c4).toArgb();
            int s1 = Color.valueOf((Long) c5).toArgb();
            int m0 = remap(s0, threshold);
            int m1 = remap(s1, threshold);
            if (m0 != s0 || m1 != s1) {
                a[4] = Color.pack(m0);
                a[5] = Color.pack(m1);
                note("2xlong", new int[]{s0, s1}, new int[]{m0, m1});
            }
        }
    }

    /** Dark (all channels < threshold) and not already the background -> background colour, same alpha */
    private static int remap(int argb, int threshold) {
        int bgRgb = com.lumi.uxpatcher.Prefs.bg() & 0x00FFFFFF;
        if ((argb & 0x00FFFFFF) != bgRgb
                && Color.red(argb) < threshold
                && Color.green(argb) < threshold
                && Color.blue(argb) < threshold) {
            return (argb & 0xFF000000) | bgRgb;
        }
        return argb;
    }

    private static void note(String kind, int[] before, int[] after) {
        if (logged >= MAX_LOGS) return;
        logged++;
        StringBuilder sb = new StringBuilder("GRADIENT: ").append(kind).append(' ');
        for (int c : before) sb.append('#').append(Integer.toHexString(c)).append(' ');
        sb.append("-> black-ified");
        Log.d(TAG, sb.toString());
    }
}