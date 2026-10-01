package com.lumi.uxpatcher.amoled;

import android.graphics.Color;
import android.util.Log;
import android.view.View;

import com.lumi.uxpatcher.firmware.FirmwareNames;

import java.lang.reflect.Constructor;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import com.lumi.uxpatcher.Prefs;

import static com.lumi.uxpatcher.Config.TAG;

/** Forces black on the root of Quick Settings and other floating panels. Not for SystemUX / VrShell: it would square off the dock pill. */
public final class MultiLayerContainerHook {
    private MultiLayerContainerHook() {}

    // ── OCMultiLayerContainer: root view of Quick Settings / floating panels ──

    /** Its window background is a LayerDrawable that extractSolidColor() can't read, so the PhoneWindow hook skips it: force black directly */
    public static void install(final LoadPackageParam lpparam) {
        try {
            Class<?> cls = FirmwareNames.load(lpparam.classLoader, FirmwareNames.OC_MULTI_LAYER_CONTAINER);
            for (Constructor<?> ctor : cls.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ctor, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        View v = (View) param.thisObject;
                        v.setBackgroundColor(Prefs.bg());
                        Log.i(TAG, "MULTILAYER: OCMultiLayerContainer constructed, forced black");
                    }
                });
            }
            // LOAD-BEARING: the constructor hook does not fire in QuickSettingsActivity, so also blacken on setContentView
            final Class<?> containerCls = cls;
            XposedHelpers.findAndHookMethod(android.app.Activity.class, "setContentView",
                    View.class, new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            View content = (View) param.args[0];
                            if (content != null && containerCls.isInstance(content)) {
                                content.setBackgroundColor(Prefs.bg());
                                Log.i(TAG, "MULTILAYER: setContentView → forced black on "
                                        + content.getClass().getSimpleName());
                            }
                        }
                    });
            Log.i(TAG, "MULTILAYER: black background hooked on OCMultiLayerContainer in "
                    + lpparam.packageName);
        } catch (ClassNotFoundException ignored) {
            // not in every package
        } catch (Throwable t) {
            Log.e(TAG, "MULTILAYER: hook failed in " + lpparam.packageName, t);
        }
    }
}
