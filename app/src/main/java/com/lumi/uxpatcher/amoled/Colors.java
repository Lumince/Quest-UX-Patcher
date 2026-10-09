package com.lumi.uxpatcher.amoled;

import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;

import com.lumi.uxpatcher.Config;
import com.lumi.uxpatcher.Prefs;

/** The AMOLED colour rules, shared by every hook that recolours something. */
public final class Colors {
    private Colors() {}

    /** The colour rule: opaque dark -> background colour, translucent dark (alpha >= 0x60) -> same alpha in that colour. Others unchanged. */
    public static int remap(int color) {
        if (!Prefs.bgEnabled()) return color;
        int bg = Prefs.bg();
        if (isDarkNonBackground(color)) return bg;
        if (Config.DARKEN_TRANSLUCENT
                && Color.alpha(color) >= 0x60 && Color.alpha(color) < 255
                && (color & 0x00FFFFFF) != 0
                && Color.red(color)   < Config.AMOLED_THRESHOLD
                && Color.green(color) < Config.AMOLED_THRESHOLD
                && Color.blue(color)  < Config.AMOLED_THRESHOLD) {
            return (color & 0xFF000000) | (bg & 0x00FFFFFF);
        }
        return color;
    }

    public static final int TILES_OFF = 0, TILES_QUICK = 1, TILES_LIBRARY = 2;

    /** Which tile rule (if any) applies in this app/process */
    public static int tileMode(String pkg, String process) {
        if (Config.QUICK_TILES_BG
                && ("com.oculus.panelapp.quicksettings".equals(pkg) || "com.oculus.panelapp.settings".equals(pkg))) {
            return TILES_QUICK;
        }
        // the Library UI runs in the :SystemBar process
        if (Config.LIBRARY_TILES_BG && "com.oculus.panelapp.library".equals(pkg)) {
            return TILES_LIBRARY;
        }
        return TILES_OFF;
    }

    /** A grey tile: white at 30% alpha (the grey on a see-through panel). size = draw width/height. */
    public static boolean isTileFill(int mode, String call, int color, float[] size) {
        if (color != 0x4CFFFFFF || size == null) return false;
        float w = size[0], h = size[1];
        if (mode == TILES_LIBRARY) {
            return "drawRoundRect".equals(call) && h >= 40f && h <= 100f && w >= 40f;
        }
        if (h < 80f || h > 100f || w < 80f) return false;
        // the wide slider pills are plain rects; hover highlights are plain rects too, so only the big ones count
        return "drawRoundRect".equals(call) || ("drawRect".equals(call) && w >= 600f);
    }

    /** A hover highlight: light neutral, see-through, tile-sized (not the grey tile itself) */
    public static boolean isTileHover(int mode, String call, int color, float[] size) {
        if (size == null) return false;
        int a = Color.alpha(color);
        if (a == 0 || a > 0xB0) return false;
        int r = Color.red(color), g = Color.green(color), b = Color.blue(color);
        int max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        if (max - min > 8 || min < 0x80) return false;
        float w = size[0], h = size[1];
        if (!"drawRoundRect".equals(call) && !"drawRect".equals(call)) return false;
        return h >= 40f && h <= 100f && w >= 40f;
    }

    /** Opaque, dark and not already the background. Translucent scrims are excluded so they stay see-through. */
    public static boolean isDarkNonBackground(int color) {
        if (!Prefs.bgEnabled()) return false;
        return Color.alpha(color) == 255
                && color != Prefs.bg()          // black by default, the user's colour otherwise
                && Color.red(color)   < Config.AMOLED_THRESHOLD
                && Color.green(color) < Config.AMOLED_THRESHOLD
                && Color.blue(color)  < Config.AMOLED_THRESHOLD;
    }

    /** The ARGB of a ColorDrawable or solid GradientDrawable, else 0. */
    public static int extractSolidColor(Drawable d) {
        if (d instanceof ColorDrawable) {
            return ((ColorDrawable) d).getColor();
        }
        if (d instanceof GradientDrawable) {
            try {
                android.content.res.ColorStateList csl = ((GradientDrawable) d).getColor();
                if (csl != null) return csl.getDefaultColor();
            } catch (Throwable ignored) {}
        }
        return 0;
    }

    /** A background-coloured version of a drawable. GradientDrawable is kept (corners, strokes); others become a ColorDrawable. */
    public static Drawable toBackground(Drawable d) {
        if (!Prefs.bgEnabled()) return d;
        final int bg = Prefs.bg();
        if (d instanceof GradientDrawable) {
            try {
                d.mutate();
                ((GradientDrawable) d).setColor(bg);
                return d;
            } catch (Throwable ignored) {}
        }
        return new ColorDrawable(bg);
    }
}
