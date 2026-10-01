package com.lumi.uxpatcher.library;

import android.util.Log;

import com.lumi.uxpatcher.Config;
import com.lumi.uxpatcher.Prefs;
import com.lumi.uxpatcher.firmware.DexStringRefs;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static com.lumi.uxpatcher.Config.TAG;

/** Keeps the Library's "Unknown Sources" tab always visible. Logs: UNKNOWN-TAB: ... */
public final class UnknownSourcesTabHook {
    private UnknownSourcesTabHook() {}

    private static final String TAB = "UNKNOWN_SOURCES";

    private static boolean isTab(Object o) {
        return o instanceof Enum && TAB.equals(((Enum<?>) o).name());
    }

    public static void install(final LoadPackageParam lp) {
        if (!Config.LIBRARY_UNKNOWN_TAB_ALWAYS) return;
        long t0 = System.currentTimeMillis();
        // tab enum = superclass of the UNKNOWN_SOURCES constant body; synchronous because the view model reads the flag once at creation
        Class<?> tabBase = null;
        try {
            for (String cn : DexStringRefs.classesReferencing(lp.appInfo, TAB)) {
                Class<?> c;
                try { c = Class.forName(cn, false, lp.classLoader); } catch (Throwable e) { continue; }
                Class<?> sup = c.getSuperclass();
                if (sup != null && sup != Enum.class && Enum.class.isAssignableFrom(sup) && sup.getSuperclass() == Enum.class
                        && c.getSuperclass() == sup && !c.isEnum()) {
                    tabBase = sup;
                    break;
                }
            }
        } catch (Throwable t) {
            Log.e(TAG, "UNKNOWN-TAB: scan failed", t);
        }
        if (tabBase == null) {
            Log.w(TAG, "UNKNOWN-TAB: tab enum not found, nothing patched");
            return;
        }

        final Class<?> base = tabBase;
        int hooked = 0;
        for (Method m : base.getDeclaredMethods()) {
            if (Modifier.isStatic(m.getModifiers()) || m.getReturnType() != boolean.class) continue;
            Class<?>[] p = m.getParameterTypes();
            if (p.length != 1 || p[0].isPrimitive() || p[0] == String.class) continue;
            try {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    private boolean logged;
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (!isTab(param.thisObject) || !Prefs.unknownTab()) return;
                        if (!Boolean.TRUE.equals(param.getResult())) {
                            param.setResult(Boolean.TRUE);
                            if (!logged) { logged = true; Log.i(TAG, "UNKNOWN-TAB: tab made available from the start"); }
                        }
                    }
                });
                hooked++;
            } catch (Throwable t) {
                Log.w(TAG, "UNKNOWN-TAB: could not hook " + m + ": " + t);
            }
        }

        // skip the stock hide-on-background: static void (tabEnum, viewModel) in the "GridViewModel" class
        int hideHooks = 0;
        try {
            List<String> vms = DexStringRefs.classesReferencing(lp.appInfo, "GridViewModel");
            for (String cn : vms) {
                Class<?> c;
                try { c = Class.forName(cn, false, lp.classLoader); } catch (Throwable e) { continue; }
                for (Method m : c.getDeclaredMethods()) {
                    Class<?>[] p = m.getParameterTypes();
                    if (!Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class || p.length != 2
                            || p[0] != base || p[1] != c) continue;
                    try {
                        XposedBridge.hookMethod(m, new XC_MethodHook() {
                            private boolean logged;
                            @Override protected void beforeHookedMethod(MethodHookParam param) {
                                if (!isTab(param.args[0]) || !Prefs.unknownTab()) return;
                                param.setResult(null);
                                if (!logged) { logged = true; Log.i(TAG, "UNKNOWN-TAB: stock hide of the tab skipped"); }
                            }
                        });
                        hideHooks++;
                    } catch (Throwable t) {
                        Log.w(TAG, "UNKNOWN-TAB: could not hook " + m + ": " + t);
                    }
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "UNKNOWN-TAB: hide-method scan failed: " + t);
        }

        Log.i(TAG, "UNKNOWN-TAB: tab enum " + base.getName() + ", " + hooked + " availability hook(s), " + hideHooks
                + " hide hook(s), " + (System.currentTimeMillis() - t0) + " ms in " + lp.packageName);
    }
}
