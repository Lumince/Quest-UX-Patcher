package com.lumi.uxpatcher.vrlockscreen;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;

import com.lumi.uxpatcher.Config;
import com.lumi.uxpatcher.Prefs;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/** Themes the VR lock screen's PIN-pad, pattern-lock views, and action buttons. */
public class LockscreenThemeHook {

    private static final String TAG = Config.TAG + "-LOCKSCREEN";

    // Widget class names inside the VrLockscreen APK
    private static final String CLS_PIN_VIEW     = "oculus.internal.widget.VerifyLockPinView";
    private static final String CLS_KEYPAD_BASE  = "oculus.internal.widget.KeypadBaseView";
    private static final String CLS_PATTERN_VIEW = "oculus.internal.widget.VerifyLockPatternView";
    private static final String CLS_STATUS_BAR    = "com.oculus.os.vrlockscreen.statusbar.VrStatusBar";

    /** Alpha for disabled primary buttons, matching how Meta's OCButton fades them */
    private static final float DISABLED_ALPHA = 0.4f;

    // Primary buttons: light fill, dark text (0xff272727)
    private static final Set<Integer> PRIMARY_BTN_IDS = new HashSet<>(Arrays.asList(
        0x7f0b03ce,  // Continue button — PIN pad
        0x7f0b004b,  // Continue button — screen A
        0x7f0b004d,  // Continue button — screen B
        0x7f0b04bc,  // Continue button — screen C
        0x7f0b0274   // Continue button — screen D
    ));

    // ImageViews / ImageButtons with white icons
    private static final Set<Integer> ICON_VIEW_IDS = new HashSet<>(Arrays.asList(
        0x7f0b0503,  // ImageButton — power_button (WelcomeScreenLayout)
        0x7f0b0146,  // ImageButton — btn_add_user (WelcomeScreenLayout)
        0x7f0b0332,  // ImageView   — info_icon
        0x7f0b07d0   // ImageView   — warning_icon
    ));

    // Plain Buttons whose icon is a vector inside the background drawable,
    // so the drawable itself has to be tinted (not an ImageView tint)
    private static final Set<Integer> ICON_BUTTON_IDS = new HashSet<>(Arrays.asList(
        0x7f0b0762,  // user_switch_back_btn  — back arrow (user_switch_panel)
        0x7f0b00d7,  // back_btn              — back arrow (vr_forgot_pin_premium)
        0x7f0b06a5,  // switch_user_btn       — back arrow (vr_lockscreen_pin_premium, usually GONE)
        0x7f0b02b4   // forgot_pin_btn        — help "?" button (vr_lockscreen_pin_premium)
    ));

    public static void install(LoadPackageParam lp) {
        if (!Prefs.bgEnabled()) return;

        hookBackgroundOnConstruct(lp, CLS_PIN_VIEW);
        hookBackgroundOnConstruct(lp, CLS_KEYPAD_BASE);
        hookBackgroundOnConstruct(lp, CLS_PATTERN_VIEW);
        hookBackgroundOnConstruct(lp, CLS_STATUS_BAR);

        hookViewsByIds();
    }

    // ── Background on constructor ─────────────────────────────────────────────

    /**
     * After each constructor of the named view class, set the view's background
     * to the user's chosen background colour.
     */
    private static void hookBackgroundOnConstruct(LoadPackageParam lp, String className) {
        XC_MethodHook applyBg = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                // Guard: not every hooked class is guaranteed to be a View.
                if (!(param.thisObject instanceof View)) return;
                try {
                    ((View) param.thisObject).setBackgroundColor(Prefs.bg());
                } catch (Throwable t) {
                    Log.w(TAG, "setBackgroundColor failed on " + className, t);
                }
            }
        };

        try {
            Class<?> cls = lp.classLoader.loadClass(className);
            for (java.lang.reflect.Constructor<?> ctor : cls.getDeclaredConstructors()) {
                XposedBridge.hookMethod(ctor, applyBg);
            }
            Log.i(TAG, "BG hook installed on " + className);
        } catch (ClassNotFoundException e) {
            Log.i(TAG, "Class not found (skip): " + className);
        } catch (Throwable t) {
            Log.w(TAG, "Failed to hook constructors of " + className, t);
        }
    }

    // ── ID-based view theming ─────────────────────────────────────────────────

    /** Themes the views above by resource ID, and re-applies when they change */
    private static void hookViewsByIds() {
        try {
            // Initial theme on attach
            XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    View v = (View) param.thisObject;
                    int id = v.getId();

                    if (PRIMARY_BTN_IDS.contains(id)) {
                        if (!(v instanceof Button)) {
                            Log.i(TAG, "PRIMARY_BTN id matched non-Button: "
                                    + v.getClass().getName()
                                    + " id=0x" + Integer.toHexString(id));
                            return;
                        }
                        applyButtonTheme((Button) v, id);

                    } else if (ICON_VIEW_IDS.contains(id)) {
                        if (!(v instanceof ImageView)) {
                            Log.i(TAG, "ICON_VIEW id matched non-ImageView: "
                                    + v.getClass().getName()
                                    + " id=0x" + Integer.toHexString(id));
                            return;
                        }
                        applyIconTheme((ImageView) v, id);

                    } else if (ICON_BUTTON_IDS.contains(id)) {
                        applyButtonIconTheme(v, id);
                    }
                }
            });

            // Re-apply icon colour whenever the drawable is swapped out
            XposedHelpers.findAndHookMethod(ImageView.class, "setImageDrawable",
                    android.graphics.drawable.Drawable.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    ImageView iv = (ImageView) param.thisObject;
                    if (ICON_VIEW_IDS.contains(iv.getId())) {
                        applyIconColorFilter(iv);
                    }
                }
            });

            // Re-apply when the view itself is made VISIBLE
            XposedHelpers.findAndHookMethod(View.class, "setVisibility", int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if ((int) param.args[0] != View.VISIBLE) return;
                    View v = (View) param.thisObject;
                    if (ICON_VIEW_IDS.contains(v.getId()) && v instanceof ImageView) {
                        applyIconColorFilter((ImageView) v);
                        Log.i(TAG, "icon re-themed on setVisibility 0x" + Integer.toHexString(v.getId()));
                    }
                }
            });

            // Re-apply when a PARENT becomes visible
            XposedHelpers.findAndHookMethod(View.class, "onVisibilityChanged",
                    View.class, int.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if ((int) param.args[1] != View.VISIBLE) return;
                    View v = (View) param.thisObject;
                    if (ICON_VIEW_IDS.contains(v.getId()) && v instanceof ImageView) {
                        applyIconColorFilter((ImageView) v);
                        Log.i(TAG, "icon re-themed on visibilityChanged 0x" + Integer.toHexString(v.getId()));
                    } else if (ICON_BUTTON_IDS.contains(v.getId())) {
                        applyButtonIconTint(v);
                    }
                }
            });

            // Fade primary buttons when disabled, restore when enabled (like OCButton)
            XposedHelpers.findAndHookMethod(View.class, "setEnabled", boolean.class, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    View v = (View) param.thisObject;
                    if (v instanceof Button && PRIMARY_BTN_IDS.contains(v.getId())) {
                        v.setAlpha((boolean) param.args[0] ? 1f : DISABLED_ALPHA);
                    }
                }
            });

            Log.i(TAG, "ID-based button/icon hooks installed");
        } catch (Throwable t) {
            Log.w(TAG, "Failed to install ID-based view hooks", t);
        }
    }

    private static void applyButtonTheme(Button btn, int id) {
        final int accent = Prefs.accent() != 0 ? Prefs.accent() : Color.DKGRAY;
        // Accent darkened so text stays readable; darker again when disabled
        final int enabledBg  = darken(accent, 0.38f);
        final int disabledBg = darken(accent, 0.20f);

        final ColorStateList tintCSL = new ColorStateList(
                new int[][] {
                    new int[]{ android.R.attr.state_enabled },
                    new int[]{}   // disabled / default
                },
                new int[]{ enabledBg, disabledBg }
        );

        btn.post(() -> {
            try {
                btn.setBackgroundTintList(tintCSL);
                btn.setBackgroundTintMode(PorterDuff.Mode.SRC_ATOP);
                btn.setTextColor(Color.WHITE);
                btn.setAlpha(btn.isEnabled() ? 1f : DISABLED_ALPHA);
                Log.i(TAG, "primary btn themed 0x" + Integer.toHexString(id)
                        + " enabled=" + btn.isEnabled() + " alpha=" + btn.getAlpha());
                Log.i(TAG, "BTN-DESC 0x" + Integer.toHexString(id) + " view=" + btn.getWidth() + "x" + btn.getHeight()
                        + " pad=" + btn.getPaddingLeft() + "," + btn.getPaddingTop()
                        + " textSize=" + btn.getTextSize()
                        + " bg=" + com.lumi.uxpatcher.systemux.SecurityLockThemeHook.describe(btn.getBackground(), 0));
            } catch (Throwable t) {
                Log.w(TAG, "primary btn post-theme failed 0x" + Integer.toHexString(id), t);
            }
        });
    }

    private static void applyIconTheme(ImageView iv, int id) {
        iv.post(() -> {
            try {
                iv.setBackgroundColor(Color.TRANSPARENT);
                applyIconColorFilter(iv);
                Log.i(TAG, "icon view themed 0x" + Integer.toHexString(id));
            } catch (Throwable t) {
                Log.w(TAG, "icon tint post failed 0x" + Integer.toHexString(id), t);
            }
        });
    }

    private static void applyButtonIconTheme(View v, int id) {
        v.post(() -> {
            try {
                applyButtonIconTint(v);
                Log.i(TAG, "icon button themed 0x" + Integer.toHexString(id));
            } catch (Throwable t) {
                Log.w(TAG, "icon button theme failed 0x" + Integer.toHexString(id), t);
            }
        });
    }

    /** Tints the VectorDrawable / BitmapDrawable leaves of a button's background */
    private static void applyButtonIconTint(View v) {
        android.graphics.drawable.Drawable bg = v.getBackground();
        if (bg == null) {
            Log.i(TAG, "icon button SKIP null bg 0x" + Integer.toHexString(v.getId()));
            return;
        }
        final int accent = Prefs.accent();
        final int tint   = accent != 0 ? accent : Color.WHITE;
        final ColorStateList csl = ColorStateList.valueOf(tint);

        bg = bg.mutate();
        int n = tintLeaves(bg, csl);
        v.invalidate();
        Log.i(TAG, "icon button tint 0x" + Integer.toHexString(v.getId())
                + " bg=" + bg.getClass().getSimpleName()
                + " leaves=" + n + " tint=0x" + Integer.toHexString(tint));
    }

    /** Recursively tints icon leaves; returns how many were tinted. */
    private static int tintLeaves(android.graphics.drawable.Drawable d, ColorStateList csl) {
        if (d == null) return 0;
        int count = 0;
        if (d instanceof android.graphics.drawable.StateListDrawable) {
            // Tint every state, so hover/pressed stay themed
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                android.graphics.drawable.StateListDrawable sl =
                        (android.graphics.drawable.StateListDrawable) d;
                for (int i = 0; i < sl.getStateCount(); i++) {
                    android.graphics.drawable.Drawable child = sl.getStateDrawable(i);
                    if (child != null) count += tintLeaves(child.mutate(), csl);
                }
            }
        } else if (d instanceof android.graphics.drawable.LayerDrawable) {
            android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) d;
            for (int i = 0; i < ld.getNumberOfLayers(); i++) {
                android.graphics.drawable.Drawable layer = ld.getDrawable(i);
                if (layer != null) count += tintLeaves(layer.mutate(), csl);
            }
        } else if (d instanceof android.graphics.drawable.InsetDrawable
                || d instanceof android.graphics.drawable.RotateDrawable
                || d instanceof android.graphics.drawable.ScaleDrawable
                || d instanceof android.graphics.drawable.ClipDrawable) {
            // wrappers: tint passes through to the inner drawable
            d.setTintList(csl);
            d.setTintMode(PorterDuff.Mode.SRC_IN);
            count++;
        } else if (d instanceof android.graphics.drawable.VectorDrawable
                || d instanceof android.graphics.drawable.BitmapDrawable) {
            d.setTintList(csl);
            d.setTintMode(PorterDuff.Mode.SRC_IN);
            count++;
        }
        // GradientDrawable (hover circle) deliberately skipped.
        return count;
    }

    /** Tints the icon with the accent colour */
    private static void applyIconColorFilter(ImageView iv) {
        android.graphics.drawable.Drawable d = iv.getDrawable();
        if (d == null) {
            Log.i(TAG, "colorFilter SKIP null 0x" + Integer.toHexString(iv.getId()));
            return;
        }

        final int accent = Prefs.accent();
        final int tint   = accent != 0 ? accent : Color.WHITE;
        Log.i(TAG, "colorFilter on 0x" + Integer.toHexString(iv.getId())
                + " drawable=" + d.getClass().getSimpleName()
                + " accent=0x" + Integer.toHexString(accent)
                + " tint=0x" + Integer.toHexString(tint));

        // setColorFilter is ignored on ImageButton in this ROM; setImageTintList works
        iv.setImageTintList(android.content.res.ColorStateList.valueOf(tint));
        iv.setImageTintMode(PorterDuff.Mode.SRC_IN);
    }

    /** Darken a colour by multiplying HSV Value by {@code factor} (0.0–1.0). */
    private static int darken(int color, float factor) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(0f, hsv[2] * factor);
        return Color.HSVToColor(hsv);
    }
}
