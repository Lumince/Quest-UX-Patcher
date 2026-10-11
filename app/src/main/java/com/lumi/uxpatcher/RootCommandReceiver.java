package com.lumi.uxpatcher;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import static com.lumi.uxpatcher.Config.TAG;

/**
 * Lets a hooked process -- which runs under its OWN package/UID, not com.lumi.uxpatcher's --
 * ask this app to run one of a small, fixed set of root commands on its behalf, instead of
 * calling `su` itself from inside the hooked process.
 */
public final class RootCommandReceiver extends BroadcastReceiver {

    public static final String ACTION_SOFT_REBOOT = "com.lumi.uxpatcher.action.SOFT_REBOOT";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!ACTION_SOFT_REBOOT.equals(intent.getAction())) return;

        final PendingResult result = goAsync();
        new Thread(() -> {
            try {
                runAsRoot("killall zygote64");
            } finally {
                result.finish();
            }
        }, "UXPatcher-rootcmd").start();
    }

    private static void runAsRoot(String cmd) {
        try {
            Process proc = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            int exit = proc.waitFor();
            Log.i(TAG, "ROOTCMD: su cmd=[" + cmd + "] exit=" + exit);
        } catch (Throwable t) {
            Log.e(TAG, "ROOTCMD: su failed: " + cmd, t);
        }
    }
}
