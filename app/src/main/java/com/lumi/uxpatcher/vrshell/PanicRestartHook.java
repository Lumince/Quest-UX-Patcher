package com.lumi.uxpatcher.vrshell;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static com.lumi.uxpatcher.Config.TAG;

/** Intercepts the 5x Meta-button "panic_press" flow before it reaches the bug reporter
 * and replaces it with a VrShell process restart. */
public final class PanicRestartHook {

    private static final String BUG_REPORT_ACTION = "com.oculus.action.BUG_REPORT";

    public static void install(LoadPackageParam lp) {
        XposedHelpers.findAndHookMethod(
                "com.oculus.vrshell.BugReportBroadcastReceiver",
                lp.classLoader,
                "onReceive",
                Context.class,
                Intent.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        Intent intent = (Intent) param.args[1];
                        if (intent == null || !BUG_REPORT_ACTION.equals(intent.getAction())) {
                            return;
                        }
                        param.setResult(null);
                        Log.i(TAG, "PANIC-RESTART: suppressed BUG_REPORT broadcast — restarting VrShell");
                        android.os.Process.killProcess(android.os.Process.myPid());
                    }
                }
        );
        Log.i(TAG, "PANIC-RESTART: hooked BugReportBroadcastReceiver#onReceive");
    }
}
