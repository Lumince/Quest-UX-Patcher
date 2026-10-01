package com.lumi.uxpatcher.amoled;

import android.util.Log;
import android.view.View;

import com.lumi.uxpatcher.Config;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static com.lumi.uxpatcher.Config.TAG;
import static com.lumi.uxpatcher.amoled.Colors.*;

/** React Native apps (Files, Gallery, TV, Help & Tips) background colours. */
public final class ReactNativeHook {
    private ReactNativeHook() {}

    // ── backgrounds ──────────────────────────────────────────────────────────────

    /** React Native never calls View.setBackgroundColor, so hook BaseViewManager.setBackgroundColor (not obfuscated). */
    public static void install(final LoadPackageParam lpparam) {
        try {
            Class<?> bvm = lpparam.classLoader.loadClass(
                    "com.facebook.react.uimanager.BaseViewManager");
            XposedHelpers.findAndHookMethod(bvm, "setBackgroundColor", View.class, int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int color = (Integer) param.args[1];
                            int mapped = remap(color);
                            if (Config.DEBUG_BG) {
                                Log.d(TAG, "RN: setBackgroundColor #" + Integer.toHexString(color)
                                        + (mapped != color ? " → #" + Integer.toHexString(mapped) : " (kept)")
                                        + " on " + param.args[0].getClass().getSimpleName());
                            }
                            if (mapped != color) param.args[1] = mapped;
                        }
                    });
            Log.i(TAG, "RN: hooked BaseViewManager.setBackgroundColor in " + lpparam.packageName);
        } catch (ClassNotFoundException ignored) {
            // not a React Native app
        } catch (Throwable t) {
            Log.e(TAG, "RN: hook failed in " + lpparam.packageName, t);
        }
    }
}
