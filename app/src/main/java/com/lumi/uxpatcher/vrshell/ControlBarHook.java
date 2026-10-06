package com.lumi.uxpatcher.vrshell;

import android.graphics.Color;
import android.util.Log;
import android.view.View;

import com.lumi.uxpatcher.amoled.Colors;
import com.lumi.uxpatcher.amoled.PanelBackgroundHook;
import com.lumi.uxpatcher.amoled.RecordingCanvasHook;
import com.lumi.uxpatcher.firmware.FirmwareNames;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static com.lumi.uxpatcher.Config.CONTROL_HANDLE_COLOR;
import static com.lumi.uxpatcher.Config.TAG;

/**
 * Recolours the window title pill and the white drag handle under every panel (VrShell Compose UI).
 * Only draws inside a control-bar AndroidComposeView are touched. Logs: UXPatcher-DRAW [cb1]/[cb2], UXPatcher CONTROLBAR.
 */
public final class ControlBarHook implements RecordingCanvasHook.Gate {

    private static final int CB_MODE_PILL = 1;
    private static final int CB_MODE_HANDLE = 2;

    private static final ThreadLocal<Integer> MODE = new ThreadLocal<Integer>() {
        @Override protected Integer initialValue() { return 0; }
    };
    private static final java.util.WeakHashMap<View, Integer> CB_VIEW_MODE =
            new java.util.WeakHashMap<>();
    private static final java.util.WeakHashMap<View, String> CB_PRESENTATIONS =
            new java.util.WeakHashMap<>();
    private static int CB_VIEW_LOGS = 0;

    // ── RecordingCanvasHook.Gate ──────────────────────────────────────────────────────────
    @Override public int mode() { return MODE.get(); }

    @Override public String label(int mode) { return "[cb" + mode + "] "; }

    @Override
    public int map(int mode, int color, float[] size) {
        if (mode == CB_MODE_HANDLE) return remapHandle(color);
        int mapped = Colors.remap(color);
        if (mapped == color && mode == CB_MODE_PILL) mapped = remapThinHandle(color, size);
        return mapped;
    }

    /** The pill is drawn with shaded paths, so flatten large shaded shapes to black (icons, ripples and the handle are too small). */
    @Override
    public boolean flattenShader(int mode, float[] size) {
        if (mode != CB_MODE_PILL || size == null) return false;
        float mn = Math.min(size[0], size[1]), mx = Math.max(size[0], size[1]);
        return mn >= 20 && mx >= 100;
    }

    /** A thin, elongated bright bar is the drag handle (for views we cannot tell apart). */
    private static int remapThinHandle(int color, float[] wh) {
        if (wh == null) return color;
        float mn = Math.min(wh[0], wh[1]), mx = Math.max(wh[0], wh[1]);
        if (mn > 0 && mn <= 16 && mx >= mn * 4) return remapHandle(color);
        return color;
    }

    /** Near-white, non-faint fill -> CONTROL_HANDLE_COLOR (alpha preserved). */
    private static int remapHandle(int color) {
        if (Color.alpha(color) >= 0x40
                && Color.red(color) >= 180 && Color.green(color) >= 180 && Color.blue(color) >= 180) {
            return (color & 0xFF000000) | CONTROL_HANDLE_COLOR;
        }
        return color;
    }

    private static boolean isBarActivity(java.util.List<Class<?>> bars, Object ctx) {
        for (Class<?> c : bars) if (c.isInstance(ctx)) return true;
        return false;
    }

    /** True if the drawable is the background of a plain-view window bar (or of something inside one) */
    private static boolean inBar(android.graphics.drawable.Drawable d, Class<?> barView) {
        Object c = d.getCallback();
        for (int i = 0; i < 4 && c instanceof android.graphics.drawable.Drawable; i++) {
            c = ((android.graphics.drawable.Drawable) c).getCallback();
        }
        View v = c instanceof View ? (View) c : null;
        android.view.ViewParent p;
        for (int i = 0; v != null && i < 4; i++) {
            if (barView.isInstance(v)) return true;
            p = v.getParent();
            v = p instanceof View ? (View) p : null;
        }
        return false;
    }

    public static void install(final LoadPackageParam lpparam) {
        // v78 draws the bar with plain views and an OCPanelBackgroundDrawable: fill that with the background colour
        try {
            final Class<?> barView = lpparam.classLoader.loadClass(FirmwareNames.CONTROL_BAR_VIEW);
            PanelBackgroundHook.install(lpparam, d -> inBar(d, barView));
            Log.i(TAG, "CONTROLBAR: plain-view bar found, its panel background is themed");
        } catch (ClassNotFoundException ignored) {
            // the bar is Compose here
        } catch (Throwable t) {
            Log.w(TAG, "CONTROLBAR: plain-view bar hook failed: " + t);
        }
        try {
            // the activity classes are optional; the Presentation / display paths cover their absence
            final java.util.List<Class<?>> bars = new java.util.ArrayList<>();
            for (String name : FirmwareNames.CONTROL_BAR_ACTIVITIES) {
                try {
                    bars.add(lpparam.classLoader.loadClass(name));
                } catch (ClassNotFoundException ignored) { }
            }
            Class<?> handle = null;
            try {
                handle = lpparam.classLoader.loadClass(
                        FirmwareNames.CONTROL_BAR_HANDLE_ACTIVITY);
            } catch (ClassNotFoundException ignored) { }
            final Class<?> handleCls = handle;
            Class<?> acv = lpparam.classLoader.loadClass(
                    FirmwareNames.COMPOSE_ANDROID_VIEW);

            RecordingCanvasHook.install(new ControlBarHook());

            // the bars can live in a Presentation, not an Activity: remember its decor views
            try {
                XposedHelpers.findAndHookMethod(android.app.Dialog.class, "setContentView",
                        View.class, android.view.ViewGroup.LayoutParams.class, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (!(param.thisObject instanceof android.app.Presentation)) return;
                            android.app.Dialog d = (android.app.Dialog) param.thisObject;
                            if (d.getWindow() == null) return;
                            synchronized (CB_PRESENTATIONS) {
                                CB_PRESENTATIONS.put(d.getWindow().getDecorView(), String.valueOf(
                                        d.getWindow().getAttributes().getTitle()));
                            }
                            Log.i(TAG, "CONTROLBAR: Presentation content set, title="
                                    + d.getWindow().getAttributes().getTitle()
                                    + " display=" + d.getWindow().getDecorView().getDisplay());
                        } catch (Throwable ignored) { }
                    }
                });
            } catch (Throwable t) {
                Log.w(TAG, "CONTROLBAR: Dialog.setContentView hook failed: " + t);
            }

            XposedBridge.hookAllMethods(acv, "dispatchDraw", new XC_MethodHook() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    try {
                        View v = (View) param.thisObject;
                        int m;
                        synchronized (CB_VIEW_MODE) {
                            Integer c = CB_VIEW_MODE.get(v);
                            if (c == null) {
                                c = 0;
                                String why = "none";
                                android.content.Context ctx = v.getContext();
                                while (ctx instanceof android.content.ContextWrapper) {
                                    if (ctx instanceof android.app.Activity) {
                                        if (isBarActivity(bars, ctx)) {
                                            c = (handleCls != null && handleCls.isInstance(ctx))
                                                    ? CB_MODE_HANDLE : CB_MODE_PILL;
                                            why = "activity " + ctx.getClass().getSimpleName();
                                        }
                                        break;
                                    }
                                    ctx = ((android.content.ContextWrapper) ctx).getBaseContext();
                                }
                                String title = null;
                                if (c == 0) {
                                    synchronized (CB_PRESENTATIONS) {
                                        title = CB_PRESENTATIONS.get(v.getRootView());
                                    }
                                    if (title != null) { c = CB_MODE_PILL; why = "presentation " + title; }
                                }
                                if (c == 0) {
                                    android.view.Display dsp = v.getDisplay();
                                    if (dsp != null && dsp.getDisplayId() != android.view.Display.DEFAULT_DISPLAY) {
                                        c = CB_MODE_PILL;
                                        why = "virtual display " + dsp.getDisplayId() + " " + dsp.getName();
                                    }
                                }
                                if (v.isAttachedToWindow()) {
                                    CB_VIEW_MODE.put(v, c);
                                    if (CB_VIEW_LOGS++ < 40) {
                                        Log.i(TAG, "CONTROLBAR: compose view " + v.getWidth() + "x"
                                                + v.getHeight() + " ctx=" + v.getContext().getClass().getName()
                                                + " root=" + v.getRootView().getClass().getSimpleName()
                                                + " display=" + v.getDisplay()
                                                + " -> mode " + c + " (" + why + ")");
                                    }
                                }
                            }
                            m = c;
                        }
                        if (m != 0) {
                            param.setObjectExtra("cbPrev", MODE.get());
                            MODE.set(m);
                        }
                    } catch (Throwable t) {
                        if (CB_VIEW_LOGS++ < 40) Log.w(TAG, "CONTROLBAR: dispatchDraw hook error " + t);
                    }
                }
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Object prev = param.getObjectExtra("cbPrev");
                    if (prev instanceof Integer) MODE.set((Integer) prev);
                }
            });
            Log.i(TAG, "CONTROLBAR: hooked AndroidComposeView.dispatchDraw in " + lpparam.packageName
                    + " (pill->black, handle->#" + Integer.toHexString(CONTROL_HANDLE_COLOR) + ")");
        } catch (ClassNotFoundException e) {
            Log.w(TAG, "CONTROLBAR: control bar / compose class not found: " + e.getMessage());
        } catch (Throwable t) {
            Log.e(TAG, "CONTROLBAR: install failed", t);
        }
    }
}
