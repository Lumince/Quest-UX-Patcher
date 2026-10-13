package com.lumi.uxpatcher.vralert;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.TextView;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import com.lumi.uxpatcher.Prefs;
import com.lumi.uxpatcher.RootCommandReceiver;

import static com.lumi.uxpatcher.Config.MODULE_PACKAGE;
import static com.lumi.uxpatcher.Config.TAG;

/**
 * Headset Power menu (Sleep / Cancel / Power Options -> Power Off / Restart), shown by
 * com.oculus.vralertservice.ShutdownConfirmActivity.
 *
 *   - Plain View/ConstraintLayout dialog, not Compose. Hardcoded to a light theme in the
 *     manifest, so none of the existing AMOLED hooks touch it on their own.
 *   - Buttons: sleep_button, cancel_button, power_options_button (reveals power_off_button /
 *     restart_button, both start View.GONE). Older firmware may have no power_options_button;
 *     in that case restart_button is always visible and the injected Soft Reboot starts visible too.
 *   - Resource ids aren't stable across firmware builds, so every lookup here goes through
 *     Resources.getIdentifier(name, "id", pkg) instead of hardcoded ids.
 *
 * Also adds a "Soft Reboot" button below Restart that runs `killall zygote64` as root -- the
 * same command Singularity's own Soft Reboot card uses. The actual `su` call happens over in
 * RootCommandReceiver, inside com.lumi.uxpatcher's process: this hook runs inside
 * com.oculus.vralertservice, which has no root grant of its own.
 */
public final class ShutdownConfirmHook {

    private static final String PKG = "com.oculus.vralertservice";
    private static final String CLASS = PKG + ".ShutdownConfirmActivity";

    private ShutdownConfirmHook() {}

    public static void install(final LoadPackageParam lpparam) {
        try {
            Class<?> cls = lpparam.classLoader.loadClass(CLASS);
            XposedHelpers.findAndHookMethod(cls, "setupButtonListeners", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        onButtonsReady((Activity) param.thisObject);
                    } catch (Throwable t) {
                        Log.e(TAG, "VRALERT: theming/button inject failed", t);
                    }
                }
            });
            Log.i(TAG, "VRALERT: hooked setupButtonListeners in " + lpparam.packageName);
        } catch (Throwable t) {
            Log.e(TAG, "VRALERT: install failed in " + lpparam.packageName, t);
        }
    }

    // ── Theming + button injection, run once setupButtonListeners() returns ──────────────────

    private static void onButtonsReady(Activity activity) {
        Button restartBtn = findButton(activity, "restart_button");

        // null = leave the stock text colour alone (theming disabled).
        //
        // The panel's own background isn't handled here -- it's a custom self-drawing
        // OcPanelBackgroundDrawable, recoloured by PanelBackgroundHook (wired into this
        // process too via MainHook.installVrAlert()).
        Integer fg = null;
        if (Prefs.bgEnabled()) {
            int bg = Prefs.bg();
            fg = Prefs.text() != 0 ? Prefs.text() : Color.WHITE;

            for (String name : new String[]{"sleep_button", "cancel_button", "power_options_button",
                    "power_off_button", "restart_button"}) {
                Button b = findButton(activity, name);
                if (b != null) b.setTextColor(fg);
            }
            for (String name : new String[]{"battery_percentage_text", "battery_charging_prompt",
                    "sca_update_message", "power_to_update_message"}) {
                TextView tv = findTextView(activity, name);
                if (tv != null) tv.setTextColor(fg);
            }
            // power_to_update_hyperlink keeps its own @color/hyperlink_text -- stays legible.

            // Every button shares the same background now (see newButtonBackground) instead of
            // each style's own stock drawable, so their highlights all match. Sleep is the only
            // one filled by default -- it's the dialog's one CTA pill.
            Button sleepBtn = findButton(activity, "sleep_button");
            if (sleepBtn != null) sleepBtn.setBackground(newButtonBackground(bg));
            for (String name : new String[]{"cancel_button", "power_options_button",
                    "power_off_button", "restart_button"}) {
                Button b = findButton(activity, name);
                if (b != null) b.setBackground(newButtonBackground(null));
            }
        }

        injectSoftRebootButton(activity, restartBtn, fg);
    }

    /** Fallback highlight when no accent colour is configured. */
    private static final int HOVER_HIGHLIGHT = 0x40FFFFFF;   // ~25% white

    /** Shared highlight for every button: the accent colour, lightened and ~25% transparent. Falls back to white when no accent is set. */
    private static int highlightColor() {
        int accent = Prefs.accent();
        if (accent == 0) return HOVER_HIGHLIGHT;
        int r = (accent >> 16) & 0xFF;
        int g = (accent >> 8) & 0xFF;
        int b = accent & 0xFF;
        // Lighten halfway to white so it still reads as a highlight, not a flat colour swap.
        r += (255 - r) / 2;
        g += (255 - g) / 2;
        b += (255 - b) / 2;
        return (0x40 << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Shared background for every button in this dialog, replacing each one's own stock style
     * drawable. {@code fillColor} null = transparent until pressed/focused/hovered; non-null =
     * filled by default (Sleep's CTA pill). Fresh instance every call -- one Drawable can't back
     * two Views at once without their pressed states bleeding into each other.
     */
    private static Drawable newButtonBackground(Integer fillColor) {
        android.graphics.drawable.GradientDrawable def = new android.graphics.drawable.GradientDrawable();
        def.setCornerRadius(9999f);   // full pill regardless of the button's height
        def.setColor(fillColor != null ? fillColor : Color.TRANSPARENT);

        android.graphics.drawable.GradientDrawable highlight = new android.graphics.drawable.GradientDrawable();
        highlight.setCornerRadius(9999f);
        highlight.setColor(highlightColor());

        android.graphics.drawable.StateListDrawable sld = new android.graphics.drawable.StateListDrawable();
        sld.addState(new int[]{android.R.attr.state_pressed}, highlight);
        sld.addState(new int[]{android.R.attr.state_focused}, highlight);
        sld.addState(new int[]{android.R.attr.state_hovered}, highlight);
        sld.addState(new int[]{}, def);
        return sld;
    }

    /**
     * Clones Restart's button style and ConstraintLayout.LayoutParams, then re-anchors the copy
     * below Restart instead of Power Off. Starts GONE if the firmware has a Power Options
     * sub-menu (revealed together with Restart when tapped), or VISIBLE on older flat-menu firmware.
     *
     * Goes through reflection for the LayoutParams fields since this module doesn't compile
     * against androidx.constraintlayout (same pattern as BatteryPercentHook).
     */
    private static void injectSoftRebootButton(Activity activity, Button restartBtn, Integer fg) {
        if (restartBtn == null) return;
        ViewParent parent = restartBtn.getParent();
        if (!(parent instanceof android.view.ViewGroup) || !parent.getClass().getName().endsWith("ConstraintLayout")) {
            Log.w(TAG, "VRALERT: restart_button's parent isn't a ConstraintLayout, skipping Soft Reboot button");
            return;
        }
        android.view.ViewGroup root = (android.view.ViewGroup) parent;

        android.view.ViewGroup.LayoutParams restartLp = restartBtn.getLayoutParams();
        Object newLp;
        try {
            Class<?> lpClass = restartLp.getClass();
            try {
                // Copy constructor clones every constraint/bias/dimension field at once -- a
                // partial copy was the earlier bug that put this button in the wrong spot.
                newLp = lpClass.getConstructor(lpClass).newInstance(restartLp);
            } catch (NoSuchMethodException noCopyCtor) {
                newLp = lpClass.getConstructor(int.class, int.class).newInstance(restartLp.width, restartLp.height);
                for (String f : new String[]{"startToStart", "endToEnd", "topToBottom"}) {
                    java.lang.reflect.Field field = lpClass.getField(f);
                    field.setInt(newLp, field.getInt(restartLp));
                }
                if (restartLp instanceof android.view.ViewGroup.MarginLayoutParams) {
                    android.view.ViewGroup.MarginLayoutParams rm = (android.view.ViewGroup.MarginLayoutParams) restartLp;
                    ((android.view.ViewGroup.MarginLayoutParams) newLp).setMargins(
                            rm.leftMargin, rm.topMargin, rm.rightMargin, rm.bottomMargin);
                }
            }

            // The one field that needs to change from the clone: point at Restart instead of
            // Power Off.
            java.lang.reflect.Field topToBottom = lpClass.getField("topToBottom");
            topToBottom.setInt(newLp, restartBtn.getId());
        } catch (Throwable t) {
            Log.e(TAG, "VRALERT: couldn't clone restart_button's layout params, skipping Soft Reboot button", t);
            return;
        }

        TypedValue tv = new TypedValue();
        activity.getTheme().resolveAttribute(android.R.attr.buttonBarNeutralButtonStyle, tv, true);
        Button softReboot = new Button(activity, null, 0, tv.resourceId);
        softReboot.setId(View.generateViewId());
        softReboot.setText("Soft Reboot");
        if (fg != null) {
            softReboot.setTextColor(fg);
            softReboot.setBackground(newButtonBackground(null));
        }
        // Hardcoded GONE instead of restartBtn.getVisibility() -- read this early, it still
        // reports VISIBLE because the Group hasn't mirrored its own GONE state onto Restart yet.
        softReboot.setVisibility(View.GONE);
        softReboot.setLayoutParams((android.view.ViewGroup.LayoutParams) newLp);

        softReboot.setOnClickListener(v -> {
            Intent i = new Intent(RootCommandReceiver.ACTION_SOFT_REBOOT);
            i.setPackage(MODULE_PACKAGE);   // explicit target -- Android 8+ drops un-targeted implicit broadcasts
            activity.sendBroadcast(i);
            dismissLikeCancel(activity);
        });

        root.addView(softReboot);

        // Mirror Power Options' own click handling here, since the Group's referenced-id list
        // is fixed at build time and can't include a view we added at runtime.
        Button powerOptionsBtn = findButton(activity, "power_options_button");
        if (powerOptionsBtn != null) {
            // Capture before replacing -- querying lazily from inside the new listener would
            // just return the new listener itself.
            final View.OnClickListener original = getOriginalListener(powerOptionsBtn);
            powerOptionsBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (original != null) original.onClick(v);
                    softReboot.setVisibility(View.VISIBLE);
                }
            });
        } else {
            // Older firmware: flat power menu with no "Power Options" sub-menu -- Restart is
            // already visible, so Soft Reboot should be visible from the start too.
            softReboot.setVisibility(View.VISIBLE);
        }

        Log.i(TAG, "VRALERT: Soft Reboot button injected");
    }

    /** The listener setupButtonListeners() already attached, so we can chain to it. */
    private static View.OnClickListener getOriginalListener(View v) {
        try {
            Object info = XposedHelpers.callMethod(v, "getListenerInfo");
            return (View.OnClickListener) XposedHelpers.getObjectField(info, "mOnClickListener");
        } catch (Throwable t) {
            Log.w(TAG, "VRALERT: couldn't chain power_options_button's original click listener", t);
            return null;
        }
    }

    /** Same result Cancel sends (RESULT_CANCELED) then finishes -- the caller expects a result either way. */
    private static void dismissLikeCancel(Activity activity) {
        try {
            Bundle result = new Bundle();
            result.putInt("android.intent.extra.RETURN_RESULT", 0);
            XposedHelpers.setBooleanField(activity, "mImmediateDismiss", true);
            XposedHelpers.callMethod(activity, "maybeSendResult", result);
        } catch (Throwable t) {
            Log.w(TAG, "VRALERT: maybeSendResult reflection failed, finishing anyway", t);
        }
        activity.finish();
    }

    // ── Lookups by name (resource ids aren't stable across firmware builds) ──────────────────

    private static int id(Activity a, String name) {
        return a.getResources().getIdentifier(name, "id", PKG);
    }

    private static Button findButton(Activity a, String name) {
        int resId = id(a, name);
        if (resId == 0) return null;
        View v = a.findViewById(resId);
        return (v instanceof Button) ? (Button) v : null;
    }

    private static TextView findTextView(Activity a, String name) {
        int resId = id(a, name);
        if (resId == 0) return null;
        View v = a.findViewById(resId);
        return (v instanceof TextView) ? (TextView) v : null;
    }
}
