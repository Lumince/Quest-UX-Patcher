package com.lumi.uxpatcher.systemux;

import android.util.Log;

import com.lumi.uxpatcher.Config;
import com.lumi.uxpatcher.firmware.StructuralResolvers;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static com.lumi.uxpatcher.Config.TAG;

/** Makes the dock's app list scrollable. Also lets the widener measure the list width when scrolling is off. */
public final class DockScrollHook {
    private DockScrollHook() {}

    private static final ThreadLocal<Boolean> PROBING = new ThreadLocal<Boolean>() {
        @Override protected Boolean initialValue() { return Boolean.FALSE; }
    };

    /** Width of the whole app list. Needs canScrollHorizontally() true, or RecyclerView reports 0. */
    public static int contentRange(android.view.View appsList) {
        PROBING.set(Boolean.TRUE);
        try {
            return (Integer) de.robv.android.xposed.XposedHelpers.callMethod(appsList,
                    "computeHorizontalScrollRange");
        } finally {
            PROBING.set(Boolean.FALSE);
        }
    }

    public static void install(final LoadPackageParam lpparam) {
        try {
            Class<?> lm = StructuralResolvers.findDockLayoutManager(lpparam.classLoader);
            if (lm == null) {
                Log.e(TAG, "SCROLL: dock LayoutManager class not found -- scroll hook not installed");
                return;
            }
            Method scroll = StructuralResolvers.findCanScrollMethod(lm);
            if (scroll == null) {
                Log.e(TAG, "SCROLL: canScrollHorizontally override not found in " + lm.getName()
                        + " -- scroll hook not installed");
                return;
            }
            XposedBridge.hookMethod(scroll, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // true when scrolling is on, or while contentRange() measures
                    if (Config.DOCK_SCROLL || PROBING.get()) param.setResult(Boolean.TRUE);
                }
            });
            Log.i(TAG, "SCROLL: hooked " + lm.getName() + "." + scroll.getName() + "() -- scrolling "
                    + (Config.DOCK_SCROLL ? "ON" : "OFF (content-width probe only)"));
        } catch (Throwable t) {
            Log.e(TAG, "SCROLL: hook installation failed", t);
        }
    }
}
