package com.lumi.uxpatcher.vrshell;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import com.lumi.uxpatcher.Config;
import com.lumi.uxpatcher.Prefs;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/** Themes the heads-up notification popup from VrShell's toast subsystem. */
public final class ToastThemeHook {
    private ToastThemeHook() {}

    private static final String TAG = Config.TAG + "-TOAST";

    private static final String CLS_TOASTS_ACTIVITY = "com.oculus.panelapp.toasts.ToastsActivity";
    private static final String CLS_TOAST_LAYER     = "com.oculus.panelapp.toasts.view.ToastLayerView";

    public static void install(LoadPackageParam lp) {
        // Hook the base Activity class
        try {
            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    Activity a = (Activity) param.thisObject;
                    if (!CLS_TOASTS_ACTIVITY.equals(a.getClass().getName())) return;
                    try {
                        Log.i(TAG, "ToastsActivity.onResume fired");
                        onToastsResumed(a);
                    } catch (Throwable t) {
                        Log.e(TAG, "onToastsResumed crashed", t);
                    }
                }
            });
            Log.i(TAG, "Activity.onResume hook installed (filtering for ToastsActivity)");
        } catch (Throwable t) {
            Log.e(TAG, "Failed to hook Activity.onResume", t);
        }
    }

    // ── Runs every time a notification popup becomes visible ──────────────────

    private static void onToastsResumed(Activity a) {
        if (!Prefs.bgEnabled()) {
            Log.i(TAG, "bgEnabled=false, skipping");
            return;
        }
        final int bg = Prefs.bg();
        Log.i(TAG, "theming with bg=0x" + Integer.toHexString(bg));

        // Transparent window background removes the blue flash from the theme's ColorDrawable
        a.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

        // ToastLayerView is present from activity startup
        View decor = a.getWindow().getDecorView();
        ViewGroup layerView = findViewGroupByClass(decor, CLS_TOAST_LAYER);
        if (layerView == null) {
            Log.e(TAG, "ToastLayerView NOT FOUND in decor tree");
            return;
        }
        Log.i(TAG, "ToastLayerView found, children=" + layerView.getChildCount());

        // Container stays transparent; the card carries the colour
        layerView.setBackgroundColor(Color.TRANSPARENT);

        // Theme cards that are already visible
        for (int i = 0; i < layerView.getChildCount(); i++) {
            View child = layerView.getChildAt(i);
            Log.i(TAG, "Theming existing child: " + child.getClass().getName());
            themeSlot(child, bg);
        }

        // Theme future cards (WindowDumpHook must not set its own listener on this view)
        layerView.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener() {
            @Override
            public void onChildViewAdded(View parent, View child) {
                Log.i(TAG, "onChildViewAdded: " + child.getClass().getName());
                try {
                    themeSlot(child, bg);
                } catch (Throwable t) {
                    Log.e(TAG, "themeSlot crashed", t);
                }
            }
            @Override
            public void onChildViewRemoved(View parent, View child) {}
        });

        Log.i(TAG, "Listener armed on ToastLayerView");
    }

    // ── Recursive card theming ────────────────────────────────────────────────

    private static void themeSlot(View v, int bg) {
        String cn = v.getClass().getName();

        // Buttons own their LayerDrawable and glow drawables
        if (cn.contains("Button") || cn.contains("button")) return;

        Drawable d = v.getBackground();

        if (v instanceof ViewGroup) {
            // Recolour the grey LayerDrawable card panels
            if (d instanceof LayerDrawable) {
                tintLayerDrawable(v, (LayerDrawable) d, bg);
                Log.i(TAG, "  themed ViewGroup: " + cn);
            }
            ViewGroup vg = (ViewGroup) v;
            for (int i = 0; i < vg.getChildCount(); i++) {
                themeSlot(vg.getChildAt(i), bg);
            }
        } else if (v instanceof TextView || v instanceof ImageView) {
            // Content: leave alone
        } else {
            // Plain View: only the grey LayerDrawable panels
            if (d instanceof LayerDrawable) {
                tintLayerDrawable(v, (LayerDrawable) d, bg);
                Log.i(TAG, "  themed View leaf: " + cn);
            }
        }
    }

    // ── Layer tinting ─────────────────────────────────────────────────────

    /** Sets the fill of each GradientDrawable layer, keeping its shape (corners, stroke) */
    private static void tintLayerDrawable(View v, LayerDrawable ld, int bg) {
        boolean hit = false;
        for (int i = 0; i < ld.getNumberOfLayers(); i++) {
            Drawable layer = ld.getDrawable(i);
            if (layer instanceof GradientDrawable) {
                layer.mutate();
                ((GradientDrawable) layer).setColor(bg);
                hit = true;
            }
        }
        if (!hit) {
            // No GradientDrawable layers: flat colour
            v.setBackgroundColor(bg);
        }
        v.invalidate();
    }

    // ── Utility ──────────────────────────────────────────────────────────────

    private static ViewGroup findViewGroupByClass(View root, String target) {
        if (root.getClass().getName().equals(target) && root instanceof ViewGroup) {
            return (ViewGroup) root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup vg = (ViewGroup) root;
            for (int i = 0; i < vg.getChildCount(); i++) {
                ViewGroup found = findViewGroupByClass(vg.getChildAt(i), target);
                if (found != null) return found;
            }
        }
        return null;
    }
}
