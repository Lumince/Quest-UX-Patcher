package com.lumi.uxpatcher.systemux;

import android.util.Log;

import com.lumi.uxpatcher.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

import static com.lumi.uxpatcher.Config.TAG;

/**
 * Tells the Dock Editor which pin limit is applied, via a file in SystemUX's files dir with limit=, pid=, boot= and build= lines.
 * limit=0 means loaded but not patched. A reader should trust it only while that pid is still SystemUX in the same boot.
 * Logs: DOCK-STATUS
 */
public final class DockStatus {
    private DockStatus() {}

    static final String FILE_NAME = "uxpatcher_dock_status";
    private static volatile int sPublished = -1;
    private static boolean sBusy = false;

    /** Writes limit=0 once the app context exists, so "loaded but not patched" differs from "not loaded". Never overwrites a real limit. */
    public static void publishLoaded() {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                for (int i = 0; i < 60; i++) {
                    try {
                        if (sPublished > 0) return;
                        Object app = de.robv.android.xposed.XposedHelpers
                                .callStaticMethod(Class.forName("android.app.ActivityThread"), "currentApplication");
                        if (app != null) {
                            synchronized (DockStatus.class) {
                                if (sPublished > 0) return;
                                write(0);
                            }
                            return;
                        }
                        Thread.sleep(500);
                    } catch (Throwable ignored) {
                        return;
                    }
                }
            }
        }, "uxp-dock-status-loaded");
        t.setDaemon(true);
        t.start();
    }

    /** Writes the limit the dock uses. Cheap once published. */
    static void publish(final int limit) {
        if (limit == sPublished) return;
        synchronized (DockStatus.class) {
            if (sBusy) return;
            sBusy = true;
        }
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    write(limit);
                } catch (Throwable t) {
                    Log.w(TAG, "DOCK-STATUS: could not write status file: " + t);
                } finally {
                    synchronized (DockStatus.class) { sBusy = false; }
                }
            }
        }, "uxp-dock-status").start();
    }

    private static void write(int limit) throws Exception {
        android.app.Application app = (android.app.Application) de.robv.android.xposed.XposedHelpers
                .callStaticMethod(Class.forName("android.app.ActivityThread"), "currentApplication");
        if (app == null) throw new IllegalStateException("no application context yet");
        File f = new File(app.getFilesDir(), FILE_NAME);
        String body = "limit=" + limit + "\n"
                + "pid=" + android.os.Process.myPid() + "\n"
                + "boot=" + bootId() + "\n"
                + "build=" + Config.BUILD_ID + "\n";
        try (FileOutputStream out = new FileOutputStream(f, false)) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        f.setReadable(true, false);
        sPublished = limit;
        Log.i(TAG, "DOCK-STATUS: wrote " + f + " limit=" + limit + " pid=" + android.os.Process.myPid());
    }

    private static String bootId() {
        try (java.io.BufferedReader r = new java.io.BufferedReader(new java.io.FileReader("/proc/sys/kernel/random/boot_id"))) {
            String s = r.readLine();
            return s == null ? "" : s.trim();
        } catch (Throwable t) {
            return "";
        }
    }
}
