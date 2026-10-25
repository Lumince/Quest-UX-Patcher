package com.lumi.uxpatcher.systemux;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.util.Log;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;

import com.lumi.uxpatcher.Config;
import com.lumi.uxpatcher.Prefs;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

/** Themes the passcode screens (set up / change / remove passcode) */
public final class SecurityLockThemeHook {
    private SecurityLockThemeHook() {}

    private static final String TAG = Config.TAG + "-SECLOCK";
    private static final String ACTIVITY_PREFIX = "com.oculus.common.passcode.";
    private static final int PRIMARY_TEXT = 0xff272727;

    public static void install(LoadPackageParam lp) {
        if (Prefs.accent() == 0) {
            Log.i(TAG, "accent off: nothing hooked in " + lp.packageName);
            return;
        }
        // In Settings the controls aren't in a passcode activity, so any activity counts (OCButtons only)
        final boolean anyActivity = "com.oculus.panelapp.settings".equals(lp.packageName);
        XposedHelpers.findAndHookMethod(View.class, "onAttachedToWindow", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                final View v = (View) param.thisObject;
                final boolean isSwitch = v instanceof Switch;
                final boolean isText = v instanceof TextView;
                if (!isSwitch && !isText) return;
                if (anyActivity) {
                    if (isText && !isSwitch && !isOCButton(v)) return;
                } else if (!inPasscodeActivity(v.getContext())) {
                    return;
                }
                v.post(() -> {
                    try {
                        if (isSwitch) themeToggle((Switch) v);
                        else themeButton((TextView) v);
                    } catch (Throwable t) {
                        Log.w(TAG, "theme failed on " + v.getClass().getName(), t);
                    }
                });
            }
        });
        Log.i(TAG, "SecurityLock theme hook installed in " + lp.packageName);
    }

    private static boolean isOCButton(View v) {
        for (Class<?> k = v.getClass(); k != null; k = k.getSuperclass()) {
            if ("com.oculus.ocui.view.button.OCButton".equals(k.getName())) return true;
        }
        return false;
    }

    private static boolean inPasscodeActivity(Context c) {
        for (int i = 0; i < 8 && c != null; i++) {
            if (c instanceof Activity) return c.getClass().getName().startsWith(ACTIVITY_PREFIX);
            if (!(c instanceof ContextWrapper)) return false;
            c = ((ContextWrapper) c).getBaseContext();
        }
        return false;
    }

    // ── Buttons ───────────────────────────────────────────────────────────────

    private static void themeButton(TextView tv) {
        if (tv instanceof Switch) return;
        if (tv.getCurrentTextColor() != PRIMARY_TEXT) return;   // light primary buttons only
        final int accent = Prefs.accent();
        final ColorStateList bg = new ColorStateList(
                new int[][]{ new int[]{android.R.attr.state_enabled}, new int[]{} },
                new int[]{ darken(accent, 0.38f), darken(accent, 0.20f) });
        tv.setBackgroundTintList(bg);
        tv.setBackgroundTintMode(PorterDuff.Mode.SRC_ATOP);
        tv.setTextColor(Color.WHITE);
        Drawable d = tv.getBackground();
        Log.i(TAG, "primary button themed: " + tv.getClass().getName()
                + " text='" + tv.getText() + "' bg=" + (d == null ? "null" : d.getClass().getSimpleName())
                + " id=0x" + Integer.toHexString(tv.getId()));
        Log.i(TAG, "BTN-DESC enabled=" + tv.isEnabled() + " alpha=" + tv.getAlpha()
                + " view=" + tv.getWidth() + "x" + tv.getHeight()
                + " pad=" + tv.getPaddingLeft() + "," + tv.getPaddingTop()
                + " textSize=" + tv.getTextSize() + " bg=" + describe(d, 0));
    }

    /** Debug: describes a drawable (class, size, radius, fill, children) for logs */
    public static String describe(Drawable d, int depth) {
        if (d == null) return "null";
        StringBuilder sb = new StringBuilder(d.getClass().getSimpleName());
        sb.append('{').append(d.getBounds().width()).append('x').append(d.getBounds().height());
        try {
            android.graphics.Outline o = new android.graphics.Outline();
            d.getOutline(o);
            sb.append(" r=").append(o.getRadius());
        } catch (Throwable ignored) { }
        if (d instanceof GradientDrawable) {
            GradientDrawable g = (GradientDrawable) d;
            ColorStateList cl = g.getColor();
            sb.append(" fill=").append(cl == null ? "null" : "0x" + Integer.toHexString(cl.getDefaultColor()));
            sb.append(" corner=").append(g.getCornerRadius());
        }
        if (depth < 4) {
            if (d instanceof LayerDrawable) {
                LayerDrawable ld = (LayerDrawable) d;
                sb.append(" layers=[");
                for (int i = 0; i < ld.getNumberOfLayers(); i++) {
                    if (i > 0) sb.append(" | ");
                    sb.append(describe(ld.getDrawable(i), depth + 1));
                }
                sb.append(']');
            } else if (d instanceof android.graphics.drawable.InsetDrawable) {
                sb.append(" inner=").append(describe(((android.graphics.drawable.InsetDrawable) d).getDrawable(), depth + 1));
            } else if (d instanceof StateListDrawable) {
                Drawable cur = d.getCurrent();
                if (cur != d) sb.append(" cur=").append(describe(cur, depth + 1));
            }
        }
        return sb.append('}').toString();
    }

    // ── Toggles ───────────────────────────────────────────────────────────────

    private static void themeToggle(Switch sw) {
        final int on = darken(Prefs.accent(), 0.55f);
        Drawable track = sw.getTrackDrawable();
        Drawable thumb = sw.getThumbDrawable();
        ColorStateList tl = sw.getTrackTintList();
        Log.i(TAG, "toggle " + sw.getClass().getSimpleName()
                + " track=" + (track == null ? "null" : track.getClass().getSimpleName())
                + " thumb=" + (thumb == null ? "null" : thumb.getClass().getSimpleName())
                + " trackTint=" + (tl == null ? "null" : "0x" + Integer.toHexString(tl.getDefaultColor())));

        int done = 0;

        // Track tint list: replace the green entries
        if (tl != null) {
            int[][] states = {
                new int[]{ android.R.attr.state_checked,  android.R.attr.state_enabled},
                new int[]{ android.R.attr.state_checked, -android.R.attr.state_enabled},
                new int[]{-android.R.attr.state_checked,  android.R.attr.state_enabled},
                new int[]{-android.R.attr.state_checked, -android.R.attr.state_enabled},
            };
            int[] colors = new int[states.length];
            boolean changed = false;
            for (int i = 0; i < states.length; i++) {
                int c = tl.getColorForState(states[i], tl.getDefaultColor());
                if (isGreen(c)) {
                    colors[i] = (c & 0xFF000000) | (on & 0x00FFFFFF);
                    changed = true;
                } else {
                    colors[i] = c;
                }
            }
            if (changed) {
                sw.setTrackTintList(new ColorStateList(states, colors));
                done++;
            }
        }

        // Track drawable: recolour green shapes
        if (track != null) done += tintCheckedGreens(track.mutate(), on);

        ColorStateList thl = sw.getThumbTintList();
        if (thl != null && isGreen(thl.getDefaultColor())) {
            sw.setThumbTintList(ColorStateList.valueOf(on));
            done++;
        }
        sw.invalidate();
        Log.i(TAG, "toggle themed (" + done + " change(s)) on=0x" + Integer.toHexString(on));
    }

    /** Recolours green GradientDrawable fills under {@code d} */
    private static int tintCheckedGreens(Drawable d, int on) {
        if (d == null) return 0;
        int n = 0;
        if (d instanceof StateListDrawable) {
            if (android.os.Build.VERSION.SDK_INT >= 29) {
                StateListDrawable sl = (StateListDrawable) d;
                for (int i = 0; i < sl.getStateCount(); i++) {
                    Drawable c = sl.getStateDrawable(i);
                    if (c != null) n += tintCheckedGreens(c.mutate(), on);
                }
            }
        } else if (d instanceof LayerDrawable) {
            LayerDrawable ld = (LayerDrawable) d;
            for (int i = 0; i < ld.getNumberOfLayers(); i++) {
                Drawable c = ld.getDrawable(i);
                if (c != null) n += tintCheckedGreens(c.mutate(), on);
            }
        } else if (d instanceof GradientDrawable) {
            ColorStateList cl = ((GradientDrawable) d).getColor();
            if (cl != null && isGreen(cl.getDefaultColor())) {
                ((GradientDrawable) d).setColor((cl.getDefaultColor() & 0xFF000000) | (on & 0x00FFFFFF));
                n++;
            }
        }
        return n;
    }

    // ── Colour helpers ────────────────────────────────────────────────────────

    /** Saturated green (hue 80..170) */
    private static boolean isGreen(int color) {
        if ((color >>> 24) < 0x40) return false;
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        return hsv[1] > 0.3f && hsv[2] > 0.3f && hsv[0] >= 80f && hsv[0] <= 170f;
    }

    private static int darken(int color, float factor) {
        float[] hsv = new float[3];
        Color.colorToHSV(color, hsv);
        hsv[2] = Math.max(0f, hsv[2] * factor);
        return Color.HSVToColor(hsv);
    }
}
