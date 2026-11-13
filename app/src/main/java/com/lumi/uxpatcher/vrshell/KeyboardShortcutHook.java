package com.lumi.uxpatcher.vrshell;

import android.util.Log;
import android.view.KeyEvent;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;
import java.io.BufferedReader;
import java.io.FileReader;
import java.lang.reflect.Method;
import static com.lumi.uxpatcher.Config.TAG;

/**
 * Prevents the SIGSEGV crash that occurs when the physical Meta/Windows key is
 * pressed while in dock mode.
 */
public final class KeyboardShortcutHook {
    private KeyboardShortcutHook() {}

    private static final String DEVICE_CONFIG_FILE =
            "/data/data/com.oculus.vrshell/files/deviceconfig_cache_v2.txt";
    private static final String NAVIGATOR_KEY =
            "oculus_systemshell:oculus_vrshell_is_navigator_enabled,";
    private static final String SHELL_INPUT_FILTER =
            "com.oculus.vrshell.input.ShellInputFilter";

    private static volatile boolean sNavigatorEnabled = true;

    public static void install(LoadPackageParam lpparam) {
        Class<?> filterClass;
        try {
            filterClass = lpparam.classLoader.loadClass(SHELL_INPUT_FILTER);
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "SHORTCUT-GUARD: ShellInputFilter not found", e);
            return;
        }

        // Read dock-mode flag once at install time — not on every key event.
        sNavigatorEnabled = readNavigatorEnabled();
        Log.i(TAG, "SHORTCUT-GUARD: navigatorEnabled=" + sNavigatorEnabled);

        int hooked = XposedBridge.hookAllMethods(filterClass,
                "interceptKeyEventPreFocusCheck", new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Object evObj = param.args[0];
                        if (!(evObj instanceof KeyEvent)) return;
                        KeyEvent ev = (KeyEvent) evObj;
                        int code = ev.getKeyCode();
                        if (code != KeyEvent.KEYCODE_META_LEFT
                                && code != KeyEvent.KEYCODE_META_RIGHT) {
                            return;
                        }

                        // VR mode: navigator enabled — let the original method run.
                        if (sNavigatorEnabled) return;

                        // Dock mode: consume the event so it never reaches
                        // nativeFrameCommand unconditionally.
                        param.setResult(true);

                        // Fire only on key-up — one action per physical press.
                        if (ev.getAction() != KeyEvent.ACTION_UP) return;

                        fireSystemButtonPress(param.thisObject.getClass().getClassLoader());
                    }
                }).size();

        if (hooked > 0) {
            Log.i(TAG, "SHORTCUT-GUARD: armed on interceptKeyEventPreFocusCheck"
                    + " (" + hooked + " overload(s))");
        } else {
            Log.i(TAG, "SHORTCUT-GUARD: interceptKeyEventPreFocusCheck absent on"
                    + " this build (pre-v203) — no hook installed");
        }
    }

    private static void fireSystemButtonPress(ClassLoader cl) {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object app = at.getMethod("currentApplication").invoke(null);
            if (app == null) {
                Log.w(TAG, "SHORTCUT-GUARD: currentApplication() returned null");
                return;
            }
            Method isAlive      = app.getClass().getMethod("isAlive");
            Method frameCommand = app.getClass().getMethod("frameCommand", String.class);
            boolean alive = Boolean.TRUE.equals(isAlive.invoke(app));
            if (alive) {
                frameCommand.invoke(app, "systemButtonPress");
            }
        } catch (Throwable t) {
            Log.e(TAG, "SHORTCUT-GUARD: fireSystemButtonPress threw: " + t);
        }
    }

    private static boolean readNavigatorEnabled() {
        try (BufferedReader br = new BufferedReader(new FileReader(DEVICE_CONFIG_FILE))) {
            String line;
            while ((line = br.readLine()) != null) {
                int idx = line.indexOf(NAVIGATOR_KEY);
                if (idx < 0) continue;
                String rest = line.substring(idx + NAVIGATOR_KEY.length());
                int comma = rest.lastIndexOf(',');
                String value = (comma >= 0 ? rest.substring(comma + 1) : rest).trim();
                return !"false".equalsIgnoreCase(value);
            }
        } catch (Throwable ignored) {
            // File absent on old firmware — assume navigator enabled (safe no-op).
        }
        return true;
    }
}
