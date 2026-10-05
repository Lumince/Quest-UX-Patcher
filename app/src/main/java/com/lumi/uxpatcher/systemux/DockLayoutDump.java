package com.lumi.uxpatcher.systemux;

import android.util.Log;
import android.view.View;
import android.view.ViewGroup;

import com.lumi.uxpatcher.Config;
import com.lumi.uxpatcher.firmware.FirmwareNames;

import java.util.WeakHashMap;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/** Logs the dock's view tree so we can find the button containers*/
public final class DockLayoutDump {
    private DockLayoutDump() {}

    private static final String LTAG = "UXPatcher-DOCK";
    private static final WeakHashMap<View, Boolean> DONE = new WeakHashMap<View, Boolean>();

    public static void install(final LoadPackageParam lp) {
        if (!Config.DOCK_LAYOUT_DUMP) return;
        try {
            Class<?> bar = lp.classLoader.loadClass(FirmwareNames.BAR_VIEW);
            XposedBridge.hookAllConstructors(bar, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    final View v = (View) param.thisObject;
                    v.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
                        @Override public void onLayoutChange(View view, int l, int t, int r, int b, int ol, int ot, int or, int ob) {
                            if (view.getWidth() == 0 || !(view instanceof ViewGroup)) return;
                            synchronized (DONE) { if (DONE.containsKey(view)) return; DONE.put(view, Boolean.TRUE); }
                            final ViewGroup g = (ViewGroup) view;
                            g.postDelayed(new Runnable() { @Override public void run() { dump(g, "early"); } }, 4000);
                            g.postDelayed(new Runnable() { @Override public void run() { dump(g, "late"); } }, 14000);
                        }
                    });
                }
            });
            Log.i(LTAG, "dock layout dump hooked (BarView)");
        } catch (Throwable t) {
            Log.w(LTAG, "install failed: " + t);
        }
    }

    private static void dump(ViewGroup bar, String when) {
        try {
            Log.i(LTAG, "=== dock dump (" + when + ") " + bar.getWidth() + "x" + bar.getHeight() + " density " + bar.getResources().getDisplayMetrics().density + " ===");
            StringBuilder up = new StringBuilder("parents:");
            View p = bar;
            for (int i = 0; i < 4 && p.getParent() instanceof View; i++) { p = (View) p.getParent(); up.append(' ').append(p.getClass().getSimpleName()); }
            Log.i(LTAG, up.toString());
            int[] o = new int[2];
            bar.getLocationInWindow(o);
            walk(bar, 0, o[0], o[1]);
            Log.i(LTAG, "=== end dock dump (" + when + ") ===");
        } catch (Throwable t) {
            Log.w(LTAG, "dump failed: " + t);
        }
    }

    private static void walk(View v, int depth, int ox, int oy) { walk(v, depth, ox, oy, 6); }

    private static void walk(View v, int depth, int ox, int oy, int levels) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < depth; i++) sb.append("  ");
        sb.append(v.getClass().getSimpleName());
        String id = idName(v);
        if (id != null) sb.append(" #").append(id);
        int[] loc = new int[2];
        v.getLocationInWindow(loc);
        sb.append(" [").append(loc[0] - ox).append(',').append(loc[1] - oy).append(' ').append(v.getWidth()).append('x').append(v.getHeight()).append(']');
        if (v.getVisibility() != View.VISIBLE) sb.append(" vis=").append(v.getVisibility());
        if (v.getContentDescription() != null) sb.append(" cd='").append(v.getContentDescription()).append('\'');
        if (v instanceof android.widget.TextView) sb.append(" text='").append(((android.widget.TextView) v).getText()).append('\'');
        if (v.isClickable()) sb.append(" clickable");
        ViewGroup.LayoutParams lp = v.getLayoutParams();
        if (lp != null) {
            sb.append(" lp=").append(lp.getClass().getSimpleName()).append(' ').append(lp.width).append('x').append(lp.height);
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                sb.append(" m=").append(m.leftMargin).append(',').append(m.topMargin).append(',').append(m.rightMargin).append(',').append(m.bottomMargin);
            }
        }
        if (v.getPaddingLeft() + v.getPaddingRight() + v.getPaddingTop() + v.getPaddingBottom() > 0) {
            sb.append(" pad=").append(v.getPaddingLeft()).append(',').append(v.getPaddingTop()).append(',').append(v.getPaddingRight()).append(',').append(v.getPaddingBottom());
        }
        Log.i(LTAG, sb.toString());
        if (!(v instanceof ViewGroup) || levels <= 0) return;
        ViewGroup g = (ViewGroup) v;
        if (v.getClass().getName().equals(FirmwareNames.DYNAMIC_APPS_VIEW)) {
            StringBuilder s2 = new StringBuilder();
            for (int i = 0; i < depth + 1; i++) s2.append("  ");
            String lm = "-";
            try {
                Object l = v.getClass().getMethod("getLayoutManager").invoke(v);
                lm = l == null ? "null" : l.getClass().getName();
            } catch (Throwable ignored) { }
            StringBuilder sup = new StringBuilder();
            for (Class<?> c = v.getClass().getSuperclass(); c != null && c != Object.class && sup.length() < 200; c = c.getSuperclass()) sup.append(c.getSimpleName()).append(' ');
            Log.i(LTAG, s2.append("(").append(g.getChildCount()).append(" children; extends ").append(sup).append("; layoutManager ").append(lm)
                    .append("; clipChildren ").append(g.getClipChildren()).append(" clipToPadding ").append(g.getClipToPadding()).append(")").toString());
            for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), depth + 1, ox, oy, 2);
            return;
        }
        for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i), depth + 1, ox, oy, levels - 1);
    }

    private static String idName(View v) {
        try {
            if (v.getId() == View.NO_ID) return null;
            return v.getResources().getResourceEntryName(v.getId());
        } catch (Throwable t) {
            return "0x" + Integer.toHexString(v.getId());
        }
    }
}
