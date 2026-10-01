package com.lumi.uxpatcher.systemux;

import android.util.Log;
import android.view.View;

import com.lumi.uxpatcher.Config;
import com.lumi.uxpatcher.firmware.FirmwareNames;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static com.lumi.uxpatcher.Config.TAG;

/** Widens the dock when icons would overlap the status area. Uses only non-obfuscated classes. */
public final class DockWidenHook {
    private DockWidenHook() {}

    // ── Dock widening ──
    // The panel has a fixed size and DynamicAppsView is anchored only to BarView's end, so too many
    // icons grow it left over SystemStatusView. After each BarView layout:
    // 1) widen the panel through vrshell (see DockResizer; Window.setLayout() alone gets cropped),
    // 2) clamp DynamicAppsView.onMeasure so extra icons scroll instead of overlapping.
    private static final java.util.WeakHashMap<View, Boolean> WIDEN_ATTACHED =
            new java.util.WeakHashMap<>();

    public static void install(final LoadPackageParam lpparam) {
        try {
            Class<?> bar = lpparam.classLoader.loadClass(FirmwareNames.BAR_VIEW);
            XposedBridge.hookAllConstructors(bar, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final View v = (View) param.thisObject;
                    synchronized (WIDEN_ATTACHED) {
                        if (WIDEN_ATTACHED.put(v, Boolean.TRUE) != null) return;
                    }
                    v.addOnLayoutChangeListener(new DockWidener());
                    Log.i(TAG, "DOCK-WIDEN: layout listener attached to BarView");
                    try { DockResizer.prebind(v.getContext()); } catch (Throwable ignored) { }
                }
            });
            Log.i(TAG, "DOCK-WIDEN: hooked BarView constructors");

            Class<?> appsCls = lpparam.classLoader.loadClass(FirmwareNames.DYNAMIC_APPS_VIEW);
            XposedHelpers.findAndHookMethod(appsCls, "onMeasure", int.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                View v = (View) param.thisObject;
                                if (!(v.getParent() instanceof android.view.ViewGroup)) return;
                                android.view.ViewGroup g = (android.view.ViewGroup) v.getParent();
                                View status = null;
                                for (int i = 0; i < g.getChildCount(); i++) {
                                    View c = g.getChildAt(i);
                                    if (c.getClass().getName().equals(FirmwareNames.SYSTEM_STATUS_VIEW)) status = c;
                                }
                                int rootW = v.getRootView().getWidth();
                                if (status == null || rootW <= 0) return;
                                int sw = status.getWidth() > 0 ? status.getWidth()
                                        : status.getMeasuredWidth();
                                int avail = rootW - sw;
                                if (avail > 0 && v.getMeasuredWidth() > avail) {
                                    XposedHelpers.callMethod(v, "setMeasuredDimension",
                                            avail, v.getMeasuredHeight());
                                }
                            } catch (Throwable ignored) { }
                        }
                    });
            Log.i(TAG, "DOCK-WIDEN: hooked DynamicAppsView.onMeasure (overlap clamp)");
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "DOCK-WIDEN: BarView not found — firmware may have moved it");
        } catch (Throwable t) {
            Log.e(TAG, "DOCK-WIDEN: install failed", t);
        }
    }

    private static final class DockWidener implements View.OnLayoutChangeListener {
        private String lastSig = "";
        private int originalWidth = -1;
        private int lastRequested = -1;
        private boolean pending = false;
        private static final long SHRINK_DELAY_MS = 800;
        private int shrinkTarget = -1;
        private long shrinkSince = 0;
        // Resize verification: vrshell ignores a resize sent while it restarts the panel, so re-send if not reached.
        private static final long VERIFY_MS = 700;
        private static final int MAX_RETRIES = 5;
        private long lastRequestAt = 0;
        private int retries = 0;
        private int notVisibleTries = 0;
        private int lastBarWidth = -1;

        @Override
        public void onLayoutChange(final View bar, int l, int t, int r, int b,
                                   int ol, int ot, int or, int ob) {
            if (pending || !(bar instanceof android.view.ViewGroup)) return;
            pending = true;
            bar.post(new Runnable() {
                @Override public void run() {
                    pending = false;
                    try { evaluate(bar); } catch (Throwable e) {
                        Log.e(TAG, "DOCK-WIDEN: evaluate failed", e);
                    }
                }
            });
        }

        private void evaluate(View bar) {
            android.view.ViewGroup g = (android.view.ViewGroup) bar;
            View apps = null, status = null;
            for (int i = 0; i < g.getChildCount(); i++) {
                View c = g.getChildAt(i);
                String n = c.getClass().getName();
                if (n.equals(FirmwareNames.DYNAMIC_APPS_VIEW)) apps = c;
                else if (n.equals(FirmwareNames.SYSTEM_STATUS_VIEW)) status = c;
            }
            if (apps == null || status == null || apps.getWidth() == 0) return;
            // Panel not shown yet (a resize now is ignored): poll until visible.
            if (bar.getRootView().getVisibility() != View.VISIBLE) {
                if (notVisibleTries++ < 60) repostEvaluate(bar, 250);
                return;
            }
            notVisibleTries = 0;
            if (originalWidth < 0) originalWidth = bar.getWidth();

            // The onMeasure clamp reads the root width, which is stale after a resize: relayout once per width.
            if (bar.getWidth() != lastBarWidth) {
                lastBarWidth = bar.getWidth();
                apps.requestLayout();
            }

            int range = DockScrollHook.contentRange(apps);
            int appsW = apps.getWidth();
            int overflow = range - appsW;
            boolean overlap = apps.getLeft() < status.getRight() - 1;

            String sig = bar.getWidth() + "/" + status.getWidth() + "@" + status.getLeft()
                    + " apps@" + apps.getLeft() + "w" + appsW + " range=" + range
                    + " overlap=" + overlap;
            if (!sig.equals(lastSig)) {
                lastSig = sig;
                Log.i(TAG, "DOCK-WIDEN: bar=" + sig + " decor=" + bar.getRootView().getWidth());
            }

            // Ask vrshell to resize the panel (the IResizeIPCService call ActiveTaskBarActivity uses).
            // Desired width = status + gap + list range, clamped to [original, original*factor]; hysteresis stops oscillation.
            int gap = 16;
            int desired = status.getWidth() + gap + range;
            int maxW = (int) (originalWidth * Config.DOCK_MAX_WIDEN_FACTOR);
            desired = Math.max(originalWidth, Math.min(desired, maxW));
            int current = bar.getWidth();
            // re-send if the previous request never took effect
            if (lastRequested > 0 && Math.abs(current - lastRequested) > 8
                    && android.os.SystemClock.uptimeMillis() - lastRequestAt > VERIFY_MS) {
                if (retries < MAX_RETRIES) {
                    retries++;
                    Log.i(TAG, "DOCK-WIDEN: bar is " + current + " but asked for " + lastRequested
                            + " -- re-sending (try " + retries + ")");
                    lastRequested = -1;
                }
            } else if (lastRequested > 0 && Math.abs(current - lastRequested) <= 8) {
                retries = 0;
            }
            boolean grow = overflow > 2 && desired > current + 8;
            boolean shrink = overflow <= 2 && current > originalWidth && desired < current - 24;
            // Debounce shrink: the range dips while icons animate, so wait SHRINK_DELAY_MS.
            if (shrink) {
                long now = android.os.SystemClock.uptimeMillis();
                if (desired != shrinkTarget) {
                    shrinkTarget = desired;
                    shrinkSince = now;
                }
                if (now - shrinkSince < SHRINK_DELAY_MS) {
                    final View b = bar;
                    b.postDelayed(new Runnable() {
                        @Override public void run() {
                            try { evaluate(b); } catch (Throwable ignored) { }
                        }
                    }, SHRINK_DELAY_MS);
                    return;
                }
            } else {
                shrinkTarget = -1;
            }
            if ((grow || shrink) && desired != lastRequested) {
                android.app.Activity act = unwrap(bar.getContext());
                if (act != null) {
                    lastRequested = desired;
                    lastRequestAt = android.os.SystemClock.uptimeMillis();
                    shrinkTarget = -1;
                    int h = bar.getRootView().getHeight();
                    if (h <= 0) h = bar.getHeight();
                    Log.i(TAG, "DOCK-WIDEN: resize request " + current + " -> " + desired
                            + "x" + h + " (original=" + originalWidth + ", range=" + range + ")");
                    DockResizer.request(act, desired, h);
                    repostEvaluate(bar, VERIFY_MS + 50);   // verify it took effect
                }
            }
        }

        private void repostEvaluate(final View bar, long delayMs) {
            bar.postDelayed(new Runnable() {
                @Override public void run() {
                    try { evaluate(bar); } catch (Throwable ignored) { }
                }
            }, delayMs);
        }

        private static android.app.Activity unwrap(android.content.Context c) {
            while (c instanceof android.content.ContextWrapper) {
                if (c instanceof android.app.Activity) return (android.app.Activity) c;
                c = ((android.content.ContextWrapper) c).getBaseContext();
            }
            return null;
        }
    }
}
