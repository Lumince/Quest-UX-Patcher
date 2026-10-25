package com.lumi.uxpatcher.debug;

import android.app.Activity;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.lumi.uxpatcher.Config;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/**
 * Debug hook: dumps view hierarchies (tag UXPatcher-WIN), on Activity.onResume and
 * whenever a root view attaches (covers service windows like the lockscreen).
 */
public final class WindowDumpHook {
    private WindowDumpHook() {}

    private static final String TAG = Config.TAG + "-WIN";

    public static void install(LoadPackageParam lp) {
        // Activities
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity a = (Activity) param.thisObject;
                    Log.i(TAG, "══ onResume ══  activity=" + a.getClass().getName()
                            + "  pkg=" + lp.packageName);
                    dumpTree(a.getWindow().getDecorView(), 0);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "onResume hook failed", t);
        }

        // Root views (parent is a ViewRootImpl, not a View)
        try {
            XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    View v = (View) param.thisObject;
                    Object parent = v.getParent();
                    if (parent == null) return;
                    if (parent instanceof View) return;
                    Log.i(TAG, "══ rootView attached ══  class=" + v.getClass().getName()
                            + "  pkg=" + lp.packageName);
                    dumpTree(v, 0);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "onAttachedToWindow hook failed", t);
        }

        Log.i(TAG, "WindowDumpHook installed in " + lp.packageName);
    }

    static void dumpTree(View v, int depth) {
        String indent = "  ".repeat(depth);
        String bg = v.getBackground() == null ? "null"
                : v.getBackground().getClass().getSimpleName();
        String id = "0x" + Integer.toHexString(v.getId());

        // Text colour / icon tint
        String extra = "";
        if (v instanceof TextView) {
            extra = "  textColor=0x" + Integer.toHexString(((TextView) v).getCurrentTextColor());
        } else if (v instanceof ImageView) {
            android.content.res.ColorStateList tint = ((ImageView) v).getImageTintList();
            extra = "  tint=" + (tint == null ? "null" : "0x" + Integer.toHexString(tint.getDefaultColor()));
        }

        Log.i(TAG, indent + "- " + v.getClass().getName()
                + "  id=" + id + "  bg=" + bg + extra);

        if (v instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                dumpTree(vg.getChildAt(i), depth + 1);
            }
        }
    }
}
