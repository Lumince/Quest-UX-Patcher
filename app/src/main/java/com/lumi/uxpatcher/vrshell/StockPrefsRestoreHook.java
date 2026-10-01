package com.lumi.uxpatcher.vrshell;

import android.content.Context;
import android.util.Log;

import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static com.lumi.uxpatcher.Config.TAG;

/** Puts two of Meta's developer settings back to their defaults (only if changed). Logs: POINTER-RESTORE. */
public final class StockPrefsRestoreHook {
    private StockPrefsRestoreHook() {}

    private static final String KEY_COLOR = "debug_grab_handle_highlight_color";
    private static final String STOCK_COLOR = "1.0,1.0,1.0,1.0";
    private static final String KEY_STATE = "debug_panel_movement_coloring_handle_state";

    public static void install(final LoadPackageParam lp) {
        Thread t = new Thread(new Runnable() { @Override public void run() { restore(); } }, "uxp-restore-prefs");
        t.setDaemon(true);
        t.start();
    }

    private static void restore() {
        try {
            Context ctx = null;
            for (int i = 0; i < 120 && ctx == null; i++) {
                try {
                    ctx = (Context) Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null);
                } catch (Throwable ignored) { }
                if (ctx == null) Thread.sleep(500);
            }
            if (ctx == null) { Log.w(TAG, "POINTER-RESTORE: no application context"); return; }
            Class<?> pm = Class.forName("horizonos.os.preferences.PreferencesManager");
            Object mgr = ctx.getSystemService(pm);
            if (mgr == null) { Log.w(TAG, "POINTER-RESTORE: PreferencesManager service not available"); return; }
            int changed = 0;
            try {
                Object cur = pm.getMethod("getString", String.class).invoke(mgr, KEY_COLOR);
                if (!STOCK_COLOR.equals(cur)) {
                    pm.getMethod("setString", String.class, String.class).invoke(mgr, KEY_COLOR, STOCK_COLOR);
                    Log.i(TAG, "POINTER-RESTORE: " + KEY_COLOR + " " + cur + " -> " + STOCK_COLOR);
                    changed++;
                }
            } catch (Throwable e) { Log.w(TAG, "POINTER-RESTORE: colour failed: " + cause(e)); }
            try {
                Object cur = pm.getMethod("getBoolean", String.class).invoke(mgr, KEY_STATE);
                if (Boolean.TRUE.equals(cur)) {
                    pm.getMethod("setBoolean", String.class, boolean.class).invoke(mgr, KEY_STATE, false);
                    Log.i(TAG, "POINTER-RESTORE: " + KEY_STATE + " true -> false");
                    changed++;
                }
            } catch (Throwable e) { Log.w(TAG, "POINTER-RESTORE: handle-state failed: " + cause(e)); }
            if (changed == 0) Log.i(TAG, "POINTER-RESTORE: nothing to restore, all stock");
        } catch (Throwable e) {
            Log.w(TAG, "POINTER-RESTORE: failed: " + e);
        }
    }

    private static Throwable cause(Throwable e) {
        return e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
    }
}
