package com.lumi.uxpatcher;

import android.util.Log;

import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/** Runs each hook installer on its own so one failure cannot stop the others */
public final class Hooks {
    private Hooks() {}

    public interface Installer {
        void install(LoadPackageParam lpparam) throws Throwable;
    }

    /** Debug: hook names listed in the property debug.uxpatcher.skip (e.g. "AMOLED,RN") are skipped */
    private static boolean skipped(String name) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String v = (String) sp.getMethod("get", String.class, String.class).invoke(null, "debug.uxpatcher.skip", "");
            if (v == null || v.isEmpty()) return false;
            for (String part : v.split(",")) {
                if (part.trim().equalsIgnoreCase(name)) return true;
            }
        } catch (Throwable ignored) {
            // property unreadable: install normally
        }
        return false;
    }

    public static void run(String name, LoadPackageParam lpparam, Installer installer) {
        if (skipped(name)) {
            Log.w(Config.TAG, name + ": SKIPPED by debug.uxpatcher.skip in " + lpparam.packageName);
            return;
        }
        try {
            installer.install(lpparam);
        } catch (Throwable t) {
            Log.e(Config.TAG, name + ": install failed in " + lpparam.packageName, t);
        }
    }
}
