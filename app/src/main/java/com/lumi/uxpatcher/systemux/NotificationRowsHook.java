package com.lumi.uxpatcher.systemux;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.Log;
import android.view.View;

import com.lumi.uxpatcher.Config;
import com.lumi.uxpatcher.Prefs;
import com.lumi.uxpatcher.amoled.Colors;
import com.lumi.uxpatcher.amoled.RecordingCanvasHook;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static com.lumi.uxpatcher.Config.TAG;

/** Themes the rows of the Notifications panel (grey #414141 cards) inside SystemUX. */
public final class NotificationRowsHook implements RecordingCanvasHook.Gate {

    private static final String FEED_PACKAGE = "com.oculus.panelapp.notifications.";
    private static final String OVERLAY_DRAWABLE = "com.oculus.ocui.drawable.OCInteractionOverlayDrawable";

    private static final ThreadLocal<int[]> DEPTH = new ThreadLocal<int[]>() {
        @Override protected int[] initialValue() { return new int[1]; }
    };
    private static int OPEN_LOGS = 0;
    private static int MAP_LOGS = 0;
    private static int PAINT_LOGS = 0;
    private static final java.util.Set<String> OPEN_SEEN =
            java.util.Collections.synchronizedSet(new java.util.HashSet<String>());

    // ── RecordingCanvasHook.Gate ──────────────────────────────────────────────────────────
    @Override public int mode() { return Prefs.bgEnabled() && DEPTH.get()[0] > 0 ? 1 : 0; }

    @Override public String label(int mode) { return "[nr] "; }

    @Override
    public int map(int mode, int color, float[] size) {
        int mapped = Colors.remap(color);
        if (mapped != color && MAP_LOGS++ < 12) {
            Log.i(TAG, "NOTIF: row fill #" + Integer.toHexString(color) + " -> #" + Integer.toHexString(mapped)
                    + (size == null ? "" : " " + size[0] + "x" + size[1]));
        }
        return mapped;
    }

    @Override public boolean flattenShader(int mode, float[] size) { return false; }

    // ── Install ───────────────────────────────────────────────────────────────────────────
    public static void install(LoadPackageParam lpparam) {
        if (!Config.NOTIFICATION_ROWS_BG) return;
        final XC_MethodHook h = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (!Prefs.bgEnabled()) return;
                    Drawable d = (Drawable) param.thisObject;
                    if (!inFeed(d)) return;
                    DEPTH.get()[0]++;
                    param.setObjectExtra("nr", Boolean.TRUE);
                    // Recolour the shape's own fill paint for the duration of this draw (restored below).
                    // Works whatever canvas the row is drawn on; the canvas gate above is a second net.
                    if (d instanceof GradientDrawable) {
                        Paint paint = fillPaint(d);
                        if (paint != null) {
                            int c = paint.getColor();
                            int m = Colors.remap(c);
                            m = (m & 0x00FFFFFF) | (c & 0xFF000000);   // keep the paint's (drawable) alpha
                            if (m != c) {
                                param.setObjectExtra("nrPaint", paint);
                                param.setObjectExtra("nrColor", c);
                                paint.setColor(m);
                                if (PAINT_LOGS++ < 12) {
                                    Log.i(TAG, "NOTIF: row paint #" + Integer.toHexString(c) + " -> #"
                                            + Integer.toHexString(m) + " (" + d.getBounds().width() + "x"
                                            + d.getBounds().height() + ")");
                                }
                            }
                        }
                    }
                } catch (Throwable ignored) { }
            }
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    Object paint = param.getObjectExtra("nrPaint");
                    Object color = param.getObjectExtra("nrColor");
                    if (paint instanceof Paint && color instanceof Integer) {
                        ((Paint) paint).setColor((Integer) color);
                    }
                } catch (Throwable ignored) { }
                if (param.getObjectExtra("nr") != null) {
                    int[] d = DEPTH.get();
                    if (d[0] > 0) d[0]--;
                }
            }
        };
        RecordingCanvasHook.install(new NotificationRowsHook());
        try {
            XposedHelpers.findAndHookMethod(GradientDrawable.class, "draw", Canvas.class, h);
            Log.i(TAG, "NOTIF: hooked GradientDrawable.draw (notification rows)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": NOTIF: could not hook GradientDrawable.draw: " + t);
        }
        try {
            Class<?> overlay = XposedHelpers.findClassIfExists(OVERLAY_DRAWABLE, lpparam.classLoader);
            if (overlay != null) {
                XposedHelpers.findAndHookMethod(overlay, "draw", Canvas.class, h);
                Log.i(TAG, "NOTIF: hooked OCInteractionOverlayDrawable.draw");
            }
        } catch (Throwable t) {
            // its children are GradientDrawables, already covered
            Log.d(TAG, "NOTIF: OCInteractionOverlayDrawable.draw not hookable: " + t);
        }
    }

    private static java.lang.reflect.Field FILL_PAINT;
    private static boolean fillPaintFailed;

    /** GradientDrawable's fill paint (hidden field mFillPaint), or null. */
    private static Paint fillPaint(Drawable d) {
        if (fillPaintFailed) return null;
        try {
            if (FILL_PAINT == null) {
                FILL_PAINT = GradientDrawable.class.getDeclaredField("mFillPaint");
                FILL_PAINT.setAccessible(true);
            }
            return (Paint) FILL_PAINT.get(d);
        } catch (Throwable t) {
            fillPaintFailed = true;
            Log.w(TAG, "NOTIF: GradientDrawable.mFillPaint not accessible, relying on the canvas gate: " + t);
            return null;
        }
    }

    /** True when this drawable belongs to a view inside the Notifications panel. */
    private static boolean inFeed(Drawable d) {
        Object cb = d.getCallback();
        for (int i = 0; i < 6 && cb instanceof Drawable; i++) cb = ((Drawable) cb).getCallback();
        if (!(cb instanceof View)) return false;
        View v = (View) cb;
        Object p = v;
        for (int i = 0; i < 24 && p != null; i++) {
            if (p.getClass().getName().startsWith(FEED_PACKAGE)) {
                if (OPEN_LOGS < 30) {
                    String col = "";
                    if (d instanceof GradientDrawable) {
                        try {
                            android.content.res.ColorStateList csl = ((GradientDrawable) d).getColor();
                            col = " color=" + (csl == null ? "none" : "#" + Integer.toHexString(csl.getDefaultColor()));
                        } catch (Throwable ignored) { }
                    }
                    String key = d.getClass().getSimpleName() + col + " on " + v.getClass().getName() + " in " + p.getClass().getSimpleName();
                    if (OPEN_SEEN.add(key)) {
                        OPEN_LOGS++;
                        Log.i(TAG, "NOTIF: gate open for " + key + " " + d.getBounds().width() + "x" + d.getBounds().height());
                    }
                }
                return true;
            }
            p = (p instanceof View) ? ((View) p).getParent() : null;
        }
        return false;
    }
}
