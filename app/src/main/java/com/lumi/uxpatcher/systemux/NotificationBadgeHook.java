package com.lumi.uxpatcher.systemux;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.lumi.uxpatcher.Prefs;
import com.lumi.uxpatcher.firmware.FirmwareNames;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import java.util.WeakHashMap;

import static com.lumi.uxpatcher.Config.TAG;

/** Optionally hides the unread-count badge on the dock's Notifications button. */
public final class NotificationBadgeHook {
    private NotificationBadgeHook() {}

    private static final String[] NAMES = {"notifications_badge", "notifications_badge_container"};
    private static final java.util.concurrent.ConcurrentHashMap<Integer, Boolean> VERDICT =
            new java.util.concurrent.ConcurrentHashMap<Integer, Boolean>();
    private static final WeakHashMap<View, Boolean> ROOTS = new WeakHashMap<View, Boolean>();
    private static int HIDE_LOGS = 0;

    public static void install(LoadPackageParam lpparam) {
        XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if ((Integer) param.args[0] == View.GONE) return;
                    if (!Prefs.hideNotificationBadge()) return;
                    if (isBadge((View) param.thisObject)) param.args[0] = View.GONE;
                } catch (Throwable ignored) { }
            }
        });
        XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                try {
                    View v = (View) param.thisObject;
                    if (!Prefs.hideNotificationBadge() || !isBadge(v)) return;
                    hide(v, "attach");
                } catch (Throwable ignored) { }
            }
        });
        try {
            Class<?> sv = lpparam.classLoader.loadClass(FirmwareNames.SYSTEM_STATUS_VIEW);
            XposedBridge.hookAllConstructors(sv, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    final View root = (View) param.thisObject;
                    synchronized (ROOTS) { if (ROOTS.containsKey(root)) return; ROOTS.put(root, Boolean.TRUE); }
                    root.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                        @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                            enforce(v);
                        }
                    });
                    root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                        private ViewTreeObserver.OnPreDrawListener pre;
                        @Override public void onViewAttachedToWindow(final View v) {
                            if (pre != null) return;
                            pre = new ViewTreeObserver.OnPreDrawListener() {
                                @Override public boolean onPreDraw() { enforce(v); return true; }
                            };
                            v.getViewTreeObserver().addOnPreDrawListener(pre);
                            enforce(v);
                        }
                        @Override public void onViewDetachedFromWindow(View v) {
                            if (pre != null) {
                                try { v.getViewTreeObserver().removeOnPreDrawListener(pre); } catch (Throwable ignored) { }
                                pre = null;
                            }
                        }
                    });
                }
            });
            Log.i(TAG, "BADGE: hooked View.setVisibility / onAttachedToWindow / " + FirmwareNames.SYSTEM_STATUS_VIEW);
        } catch (Throwable t) {
            Log.w(TAG, "BADGE: status view backstop not installed: " + t);
            Log.i(TAG, "BADGE: hooked View.setVisibility / onAttachedToWindow");
        }
    }

    private static void enforce(View root) {
        try {
            if (!Prefs.hideNotificationBadge() || !(root instanceof ViewGroup)) return;
            walk((ViewGroup) root, 0);
        } catch (Throwable ignored) { }
    }

    private static void walk(ViewGroup g, int depth) {
        if (depth > 6) return;
        for (int i = 0; i < g.getChildCount(); i++) {
            View c = g.getChildAt(i);
            if (isBadge(c)) hide(c, "layout");
            else if (c instanceof ViewGroup && c.getVisibility() != View.GONE) walk((ViewGroup) c, depth + 1);
        }
    }

    private static void hide(View v, String why) {
        if (v.getVisibility() == View.GONE) return;
        v.setVisibility(View.GONE);
        if (HIDE_LOGS++ < 6) {
            Log.i(TAG, "BADGE: hid " + v.getClass().getSimpleName() + " id=0x" + Integer.toHexString(v.getId()) + " (" + why + ")");
        }
    }

    private static boolean isBadge(View v) {
        int id = v.getId();
        if (id == View.NO_ID || (id >>> 24) != 0x7f) return false;
        Boolean cached = VERDICT.get(id);
        if (cached != null) return cached;
        boolean hit = false;
        try {
            String entry = v.getResources().getResourceEntryName(id);
            for (String n : NAMES) if (n.equals(entry)) { hit = true; break; }
        } catch (Throwable ignored) { }   // unknown id in these Resources: not ours
        VERDICT.put(id, hit);
        if (hit) Log.i(TAG, "BADGE: matched view id 0x" + Integer.toHexString(id) + " on " + v.getClass().getSimpleName());
        return hit;
    }
}
