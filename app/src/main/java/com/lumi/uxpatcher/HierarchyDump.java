package com.lumi.uxpatcher;

import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/** Debug: logs each window's view tree (class, size, alpha, drawables, fading edge) after attach. adb logcat -s UXPatcher-DUMP */
final class HierarchyDump {

    private static final String TAG = "UXPatcher-DUMP";
    private static final long DELAY_MS = 4000;
    private static final int MAX_DEPTH = 40;
    private static final Set<View> DONE = Collections.newSetFromMap(new WeakHashMap<View, Boolean>());

    private HierarchyDump() {}

    static void install(LoadPackageParam lpparam) {
        try {
            Class<?> vri = Class.forName("android.view.ViewRootImpl", false, lpparam.classLoader);
            // all overloads: setView's signature varies by Android version
            XposedBridge.hookAllMethods(vri, "setView", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (param.args.length == 0 || !(param.args[0] instanceof View)) return;
                    final View root = (View) param.args[0];
                    synchronized (DONE) {
                        if (!DONE.add(root)) return;
                    }
                    root.postDelayed(new Runnable() {
                        @Override public void run() { dump(root); }
                    }, DELAY_MS);
                }
            });
            Log.i(TAG, "dump hook installed in " + lpparam.packageName);
        } catch (Throwable t) {
            Log.e(TAG, "install failed", t);
        }
    }

    private static void dump(View root) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== window root ").append(root.getClass().getName()).append(" ===\n");
        walk(root, 0, sb);
        flush(sb);
    }

    private static void walk(View v, int depth, StringBuilder sb) {
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getName())
          .append(' ').append(v.getWidth()).append('x').append(v.getHeight())
          .append(" a=").append(v.getAlpha());
        if (v.getVisibility() != View.VISIBLE) sb.append(" vis=").append(v.getVisibility());

        describe(" bg", v.getBackground(), sb);
        describe(" fg", v.getForeground(), sb);
        if (v instanceof ImageView) describe(" img", ((ImageView) v).getDrawable(), sb);

        if (v.isVerticalFadingEdgeEnabled())
            sb.append(" FADE-V(").append(v.getVerticalFadingEdgeLength()).append(')');
        if (v.isHorizontalFadingEdgeEnabled()) sb.append(" FADE-H");
        try {
            Object solid = XposedHelpers.callMethod(v, "getSolidColor");
            if (solid instanceof Integer && (Integer) solid != 0)
                sb.append(" solid=#").append(Integer.toHexString((Integer) solid));
        } catch (Throwable ignored) {}
        sb.append('\n');

        if (v instanceof ViewGroup && depth < MAX_DEPTH) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), depth + 1, sb);
        }
    }

    private static void describe(String tag, Drawable d, StringBuilder sb) {
        if (d == null) return;
        sb.append(tag).append('=').append(d.getClass().getName());
        if (d instanceof ColorDrawable) {
            sb.append("(#").append(Integer.toHexString(((ColorDrawable) d).getColor())).append(')');
        } else if (d instanceof GradientDrawable) {
            ColorStateList c = ((GradientDrawable) d).getColor();
            sb.append(c != null ? "(#" + Integer.toHexString(c.getDefaultColor()) + ")" : "(gradient)");
        } else if (d instanceof LayerDrawable) {
            LayerDrawable ld = (LayerDrawable) d;
            sb.append('[');
            for (int i = 0; i < ld.getNumberOfLayers(); i++) {
                Drawable l = ld.getDrawable(i);
                if (i > 0) sb.append(", ");
                sb.append(l == null ? "null" : l.getClass().getSimpleName());
            }
            sb.append(']');
        }
    }

    /** Logcat cuts entries at ~4 KB, so emit line-aligned chunks */
    private static void flush(StringBuilder sb) {
        String s = sb.toString();
        int start = 0;
        while (start < s.length()) {
            int end = Math.min(start + 3500, s.length());
            if (end < s.length()) {
                int nl = s.lastIndexOf('\n', end);
                if (nl > start) end = nl;
            }
            Log.i(TAG, s.substring(start, end));
            start = end + 1;
        }
    }
}
