package com.lumi.uxpatcher.systemux;

import android.util.Log;

import com.lumi.uxpatcher.Config;
import com.lumi.uxpatcher.firmware.FirmwareNames;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static com.lumi.uxpatcher.Config.TAG;

/** Debug (Config.LOG_PINNING_API): logs every call and return on the pinning binder. Log: PIN-API */
public final class PinningServiceLogger {
    private PinningServiceLogger() {}

    public static void install(final LoadPackageParam lpparam) {
        if (!Config.LOG_PINNING_API) return;
        try {
            Class<?> binderClass = FirmwareNames.load(lpparam.classLoader, FirmwareNames.PIN_BINDER);
            int count = 0;
            for (final Method m : binderClass.getDeclaredMethods()) {
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        StringBuilder sb = new StringBuilder("PIN-API call ");
                        sb.append(m.getName()).append('(');
                        for (int i = 0; i < param.args.length; i++) {
                            if (i > 0) sb.append(", ");
                            Object a = param.args[i];
                            // truncate long values
                            sb.append(a == null ? "null" : a.toString().length() > 120
                                    ? a.toString().substring(0, 120) + "…" : a.toString());
                        }
                        sb.append(')');
                        Log.i(TAG, sb.toString());
                    }
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object r = param.getResult();
                        Log.i(TAG, "PIN-API return " + m.getName() + " → "
                                + (r == null ? "null" : r.toString().length() > 200
                                   ? r.toString().substring(0, 200) + "…" : r.toString()));
                    }
                });
                count++;
            }
            Log.i(TAG, "PIN-API: logged " + count + " methods on NavigatorItemPinningManagerBinder");
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "PIN-API: binder class not found — firmware may have moved it");
        } catch (Throwable t) {
            Log.e(TAG, "PIN-API: logger install failed", t);
        }
    }
}
