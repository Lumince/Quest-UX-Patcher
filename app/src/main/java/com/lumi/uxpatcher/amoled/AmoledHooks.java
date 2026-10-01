package com.lumi.uxpatcher.amoled;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.util.Log;
import android.view.View;

import com.lumi.uxpatcher.Config;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static com.lumi.uxpatcher.Config.TAG;
import static com.lumi.uxpatcher.amoled.Colors.*;

/** Generic dark -> black hooks for app packages: View/Window backgrounds and Canvas fills. Not used in SystemUX / VrShell. */
public final class AmoledHooks {
    private AmoledHooks() {}

    // ── AMOLED background hooks ───────────────────────────────────────────────

    /** Hooks the View, PhoneWindow and Canvas background paths; dark colours (see Colors.remap) become the background colour */
    public static void install(final LoadPackageParam lpparam) {

        // ── View.setBackgroundColor ───────────────────────────────────────────
        try {
            XposedHelpers.findAndHookMethod(View.class, "setBackgroundColor", int.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            int color = (int) param.args[0];
                            int mapped = remap(color);
                            if (mapped != color) {
                                Log.d(TAG, "AMOLED: setBackgroundColor #"
                                        + Integer.toHexString(color) + " → #" + Integer.toHexString(mapped));
                                param.args[0] = mapped;
                            }
                        }
                    });
            Log.d(TAG, "AMOLED: hooked View.setBackgroundColor");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": AMOLED: could not hook View.setBackgroundColor: " + t);
        }

        // ── View.setBackground / View.setBackgroundDrawable ───────────────────
        // DecorView.setWindowBackground() calls setBackgroundDrawable() directly, so hook both
        final XC_MethodHook bgHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                Drawable d = (Drawable) param.args[0];
                int color = extractSolidColor(d);
                int mapped = color == 0 ? 0 : remap(color);
                if (mapped != 0 && mapped != color) {
                    Log.d(TAG, "AMOLED: background #"
                            + Integer.toHexString(color) + " → #" + Integer.toHexString(mapped));
                    if (Color.alpha(mapped) == 255) param.args[0] = toBackground(d);
                    else if (d instanceof ColorDrawable) param.args[0] = new ColorDrawable(mapped);
                } else if (Config.DEBUG_BG && d != null && color == 0) {
                    Log.d(TAG, "BG-DEBUG: " + param.thisObject.getClass().getName()
                            + " <- " + d.getClass().getName());
                }
            }
        };
        for (String name : new String[]{"setBackground", "setBackgroundDrawable"}) {
            try {
                XposedHelpers.findAndHookMethod(View.class, name, Drawable.class, bgHook);
                Log.d(TAG, "AMOLED: hooked View." + name);
            } catch (Throwable t) {
                XposedBridge.log(TAG + ": AMOLED: could not hook View." + name + ": " + t);
            }
        }

        // ── PhoneWindow.setBackgroundDrawable ─────────────────────────────────
        // Xposed hooks the declared class, so hook PhoneWindow, not the abstract Window
        try {
            Class<?> phoneWindowClass = Class.forName(
                    "com.android.internal.policy.PhoneWindow",
                    false, lpparam.classLoader);
            XposedHelpers.findAndHookMethod(phoneWindowClass, "setBackgroundDrawable", Drawable.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            Drawable d = (Drawable) param.args[0];
                            int color = extractSolidColor(d);
                            if (color != 0 && isDarkNonBackground(color)) {
                                Log.d(TAG, "AMOLED: PhoneWindow.setBgDrawable #"
                                        + Integer.toHexString(color) + " → background colour");
                                param.args[0] = toBackground(d);
                            } else if (d != null) {
                                // log what we don't handle yet
                                Log.d(TAG, "AMOLED: PhoneWindow.setBgDrawable unhandled: "
                                        + d.getClass().getSimpleName()
                                        + " color=#" + Integer.toHexString(color));
                            }
                        }
                    });
            Log.d(TAG, "AMOLED: hooked PhoneWindow.setBackgroundDrawable");
        } catch (Throwable t) {
            Log.d(TAG, "AMOLED: PhoneWindow.setBackgroundDrawable not hookable: " + t);
        }

        // ── Canvas.drawRect / drawRoundRect (Compose backgrounds) ────────────
        // Compose bypasses the View background system and fills with drawRect / drawRoundRect.
        // Remap the paint colour for the call, then restore it (Compose re-uses one Paint).
        final XC_MethodHook canvasPaintHook = new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                android.graphics.Paint paint =
                        (android.graphics.Paint) param.args[param.args.length - 1];
                if (paint == null) return;
                int color = paint.getColor();
                int mapped = remap(color);
                if (mapped != color) {
                    param.setObjectExtra("savedColor", color);
                    paint.setColor(mapped);
                }
            }
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Integer saved = (Integer) param.getObjectExtra("savedColor");
                if (saved != null) {
                    android.graphics.Paint paint =
                            (android.graphics.Paint) param.args[param.args.length - 1];
                    if (paint != null) paint.setColor(saved);
                }
            }
        };
        try {
            XposedHelpers.findAndHookMethod(android.graphics.Canvas.class, "drawRect",
                    float.class, float.class, float.class, float.class,
                    android.graphics.Paint.class, canvasPaintHook);
            Log.d(TAG, "AMOLED: hooked Canvas.drawRect(ffff+Paint)");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": AMOLED: could not hook Canvas.drawRect: " + t);
        }
        try {
            XposedHelpers.findAndHookMethod(android.graphics.Canvas.class, "drawRoundRect",
                    float.class, float.class, float.class, float.class,
                    float.class, float.class,
                    android.graphics.Paint.class, canvasPaintHook);
            Log.d(TAG, "AMOLED: hooked Canvas.drawRoundRect");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": AMOLED: could not hook Canvas.drawRoundRect: " + t);
        }

        RecordingCanvasHook.tileMode = Colors.tileMode(lpparam.packageName, lpparam.processName);
        RecordingCanvasHook.install(null);

        Log.i(TAG, "AMOLED: hooks installed in " + lpparam.packageName);
    }
}
