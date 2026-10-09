package com.lumi.uxpatcher;

import android.util.Log;

import static com.lumi.uxpatcher.Config.TAG;

/**
 * User settings: the app writes them, every hooked process reads them once at start.
 *
 *   bg_color       opaque ARGB int, default black. Used by every "make it black" hook.
 *   hide_profile   boolean, default false. Hides the dock's profile button.
 *   accent_color   RGB int, or -1 = off (Meta's accents stay).
 *   text_color     RGB int, or -1 = off (Meta's text colour stays).
 *   pin_limit_on   boolean, default true. Raise the dock pin limit to Config.DOCK_PIN_LIMIT.
 *   unknown_tab_on boolean, default true. Keep the Library's "Unknown Sources" tab visible.
 *
 * Two channels, both written on every save. Channel 2 wins when it has a value, then channel 1, then the defaults.
 *   1. SharedPreferences file FILE (world readable), read with XSharedPreferences. Used first, can be stale.
 *   2. Settings.Global keys G_*, written through su. Read lazily: no Context exists at load time.
 *
 * A change applies after KILL PROCESSES. Logcat: "CONFIG: ...".
 */
public final class Prefs {
    private Prefs() {}

    public static final String MODULE_PKG = "com.lumi.uxpatcher";
    public static final String FILE = "uxpatcher_settings";
    public static final String KEY_BG = "bg_color";
    public static final String KEY_HIDE_PROFILE = "hide_profile";

    public static final String KEY_ACCENT = "accent_color";
    public static final String KEY_TEXT = "text_color";
    public static final String KEY_PIN_LIMIT = "pin_limit_on";
    public static final String KEY_UNKNOWN_TAB = "unknown_tab_on";
    public static final String KEY_DOCK_ORDER = "dock_order";
    public static final String KEY_APPS_LEFT = "dock_apps_left";
    public static final String KEY_LIB_FIRST = "dock_library_first";
    public static final String KEY_HIDE_PT = "hide_passthrough";
    public static final String KEY_HIDE_BATT_ICON = "hide_battery_icon";
    public static final String KEY_BATT_PERCENT = "battery_percent";
    public static final String KEY_BG_ENABLED = "bg_enabled";
    public static final String KEY_HIDE_NOTIF_BADGE = "hide_notif_badge";

    /** Stored in place of an RGB value when the colour is not customised. */
    public static final int OFF = -1;

    public static final String G_BG = "uxpatcher_bg_color";
    public static final String G_ACCENT = "uxpatcher_accent_color";
    public static final String G_TEXT = "uxpatcher_text_color";
    public static final String G_HIDE = "uxpatcher_hide_profile";
    public static final String G_PIN_LIMIT = "uxpatcher_pin_limit";
    public static final String G_UNKNOWN_TAB = "uxpatcher_unknown_tab";
    public static final String G_DOCK_ORDER = "uxpatcher_dock_order";
    public static final String G_APPS_LEFT = "uxpatcher_dock_apps_left";
    public static final String G_LIB_FIRST = "uxpatcher_dock_library_first";
    public static final String G_HIDE_PT = "uxpatcher_hide_passthrough";
    public static final String G_HIDE_BATT_ICON = "uxpatcher_hide_battery_icon";
    public static final String G_BATT_PERCENT = "uxpatcher_battery_percent";
    public static final String G_BG_ENABLED = "uxpatcher_bg_enabled";
    public static final String G_HIDE_NOTIF_BADGE = "uxpatcher_hide_notif_badge";
    public static final String DEFAULT_DOCK_ORDER = "profile,qs,notif,pt";

    public static final int DEFAULT_BG = 0xFF000000;

    private static volatile int bg = DEFAULT_BG;
    private static volatile boolean hide = false;
    private static volatile int accent = OFF;
    private static volatile int text = OFF;
    private static volatile boolean pinLimit = true;
    private static volatile boolean unknownTab = true;
    private static volatile String dockOrder = DEFAULT_DOCK_ORDER;
    private static volatile boolean appsLeft = false;
    private static volatile boolean libFirst = false;
    private static volatile boolean hidePt = false;
    private static volatile boolean hideBattIcon = false;
    private static volatile boolean battPercent = true;
    private static volatile boolean bgEnabled = true;
    private static volatile boolean hideNotifBadge = false;
    private static volatile boolean loaded = false;
    private static boolean xspTried = false;
    private static boolean xspHadValues = false;
    private static String source = "defaults";

    // ── Hook-side API ─────────────────────────────────────────────────────────────────────
    /** Configured background colour (always opaque). */
    public static int bg() {
        ensureLoaded();
        return bg;
    }

    /** Whether the dock's profile button should be hidden. */
    public static boolean hideProfile() {
        ensureLoaded();
        return hide;
    }

    /** Accent colour as opaque ARGB, or 0 when the accent is not customised. */
    public static int accent() {
        ensureLoaded();
        return accent == OFF ? 0 : 0xFF000000 | accent;
    }

    /** Text colour as opaque ARGB, or 0 when the text colour is not customised. */
    public static int text() {
        ensureLoaded();
        return text == OFF ? 0 : 0xFF000000 | text;
    }

    /** Raise the dock's pin limit? */
    public static boolean pinLimit() {
        ensureLoaded();
        return pinLimit;
    }

    /** Keep the Library's Unknown Sources tab visible? */
    public static boolean unknownTab() {
        ensureLoaded();
        return unknownTab;
    }

    /** Dock button order */
    public static String dockOrder() {
        ensureLoaded();
        return dockOrder;
    }

    /** Apps on the left */
    public static boolean appsLeft() {
        ensureLoaded();
        return appsLeft;
    }

    /** Library button on the left */
    public static boolean libraryFirst() {
        ensureLoaded();
        return libFirst;
    }

    /** Hide the passthrough button */
    public static boolean hidePassthrough() {
        ensureLoaded();
        return hidePt;
    }

    /** Hide the battery icon */
    public static boolean hideBatteryIcon() {
        ensureLoaded();
        return hideBattIcon;
    }

    /** Show the battery percentage. Always on while the battery icon is hidden, so one of them shows. */
    public static boolean batteryPercent() {
        ensureLoaded();
        return battPercent || hideBattIcon;
    }

    /** Hide the unread-count badge on the dock's Notifications button */
    public static boolean hideNotificationBadge() {
        ensureLoaded();
        return hideNotifBadge;
    }

    /** Whether background-colour theming is enabled. When false, Meta's own backgrounds show through. */
    public static boolean bgEnabled() {
        ensureLoaded();
        return bgEnabled;
    }

    /** True once the real settings (not just defaults) are read */
    public static boolean isLoaded() {
        ensureLoaded();
        return loaded;
    }

    /** Call from handleLoadPackage to read the settings as early as possible */
    public static void init(String pkg) {
        ensureLoaded();
        Log.i(TAG, "CONFIG: " + pkg + " bg=#" + hex(bg) + " hideProfile=" + hide + " hidePt=" + hidePt + " hideBattIcon=" + hideBattIcon
                + " pinLimit=" + pinLimit + " unknownTab=" + unknownTab
                + " accent=" + hexOrOff(accent) + " text=" + hexOrOff(text)
                + " (source=" + source + (loaded ? "" : ", waiting for app context") + ")");
    }

    private static synchronized void ensureLoaded() {
        if (loaded) return;
        if (!xspTried) {
            xspTried = true;
            try {
                Integer c = null; Boolean h = null; Integer ac = null, tx = null;
                Boolean pl = null, ut = null;
                String dord = null;
                Boolean al = null, lf = null, hp = null, hb = null, bp = null, bge = null, hnb = null;
                Object[] r = Xsp.read();
                if (r != null) {
                    c = (Integer) r[0]; h = (Boolean) r[1]; ac = (Integer) r[2]; tx = (Integer) r[3];
                    pl = (Boolean) r[4]; ut = (Boolean) r[5]; dord = (String) r[6]; al = (Boolean) r[7]; lf = (Boolean) r[8];
                    hp = (Boolean) r[9]; hb = (Boolean) r[10]; bp = (Boolean) r[11]; bge = (Boolean) r[12]; hnb = (Boolean) r[13];
                }
                if (c != null) bg = 0xFF000000 | c;
                if (h != null) hide = h;
                if (ac != null) accent = ac;
                if (tx != null) text = tx;
                if (pl != null) pinLimit = pl;
                if (ut != null) unknownTab = ut;
                if (dord != null) dockOrder = dord;
                if (al != null) appsLeft = al;
                if (lf != null) libFirst = lf;
                if (hp != null) hidePt = hp;
                if (hb != null) hideBattIcon = hb;
                if (bp != null) battPercent = bp;
                if (bge != null) bgEnabled = bge;
                if (hnb != null) hideNotifBadge = hnb;
                if (c != null || h != null || ac != null || tx != null || pl != null || ut != null || dord != null || al != null || lf != null || hp != null || hb != null || bp != null || bge != null || hnb != null) {
                    xspHadValues = true;
                    source = "XSharedPreferences";
                }
            } catch (Throwable t) {
                Log.w(TAG, "CONFIG: XSharedPreferences unavailable: " + t);
            }
        }
        // channel 2: Settings.Global (needs a Context)
        try {
            android.app.Application app = (android.app.Application) de.robv.android.xposed.XposedHelpers
                    .callStaticMethod(Class.forName("android.app.ActivityThread"), "currentApplication");
            if (app == null) return;       // try again on the next call
            android.content.ContentResolver cr = app.getContentResolver();
            String sb = android.provider.Settings.Global.getString(cr, G_BG);
            String sh = android.provider.Settings.Global.getString(cr, G_HIDE);
            String sa = android.provider.Settings.Global.getString(cr, G_ACCENT);
            String st = android.provider.Settings.Global.getString(cr, G_TEXT);
            String sp = android.provider.Settings.Global.getString(cr, G_PIN_LIMIT);
            String su = android.provider.Settings.Global.getString(cr, G_UNKNOWN_TAB);
            String sd = android.provider.Settings.Global.getString(cr, G_DOCK_ORDER);
            String sal = android.provider.Settings.Global.getString(cr, G_APPS_LEFT);
            String slf = android.provider.Settings.Global.getString(cr, G_LIB_FIRST);
            String shp = android.provider.Settings.Global.getString(cr, G_HIDE_PT);
            String shb = android.provider.Settings.Global.getString(cr, G_HIDE_BATT_ICON);
            String sbp = android.provider.Settings.Global.getString(cr, G_BATT_PERCENT);
            String sbge = android.provider.Settings.Global.getString(cr, G_BG_ENABLED);
            String shnb = android.provider.Settings.Global.getString(cr, G_HIDE_NOTIF_BADGE);
            if (sb != null) { try { bg = 0xFF000000 | Integer.parseInt(sb.trim()); } catch (NumberFormatException ignored) { } }
            if (sh != null) hide = "1".equals(sh.trim()) || "true".equalsIgnoreCase(sh.trim());
            // non-number = off
            if (sa != null) { try { accent = Integer.parseInt(sa.trim()); } catch (NumberFormatException e) { accent = OFF; } }
            if (st != null) { try { text = Integer.parseInt(st.trim()); } catch (NumberFormatException e) { text = OFF; } }
            if (sp != null) pinLimit = !("0".equals(sp.trim()) || "false".equalsIgnoreCase(sp.trim()));
            if (su != null) unknownTab = !("0".equals(su.trim()) || "false".equalsIgnoreCase(su.trim()));
            if (sal != null) appsLeft = "1".equals(sal.trim()) || "true".equalsIgnoreCase(sal.trim());
            if (slf != null) libFirst = "1".equals(slf.trim()) || "true".equalsIgnoreCase(slf.trim());
            if (shp != null) hidePt = "1".equals(shp.trim()) || "true".equalsIgnoreCase(shp.trim());
            if (shb != null) hideBattIcon = "1".equals(shb.trim()) || "true".equalsIgnoreCase(shb.trim());
            if (sbp != null) battPercent = !("0".equals(sbp.trim()) || "false".equalsIgnoreCase(sbp.trim()));
            if (shnb != null) hideNotifBadge = "1".equals(shnb.trim()) || "true".equalsIgnoreCase(shnb.trim());
            if (sbge != null) bgEnabled = !("0".equals(sbge.trim()) || "false".equalsIgnoreCase(sbge.trim()));
            if (sd != null && !sd.trim().isEmpty() && !"null".equals(sd.trim())) dockOrder = sd.trim();
            if (sb != null || sh != null || sa != null || st != null || sp != null || su != null || sd != null || sal != null || slf != null || shp != null || shb != null || sbp != null || sbge != null || shnb != null) {
                source = xspHadValues ? "Settings.Global (overrides XSharedPreferences)" : "Settings.Global";
            }
            loaded = true;
            Log.i(TAG, "CONFIG: loaded bg=#" + hex(bg) + " hideProfile=" + hide
                    + " accent=" + hexOrOff(accent) + " text=" + hexOrOff(text) + " (source=" + source + ")");
        } catch (Throwable t) {
            loaded = true;     // give up, keep defaults
            Log.w(TAG, "CONFIG: Settings.Global read failed: " + t);
        }
    }

    /** Isolated so the module's own app never loads XSharedPreferences */
    private static final class Xsp {
        static Object[] read() {
            de.robv.android.xposed.XSharedPreferences x =
                    new de.robv.android.xposed.XSharedPreferences(MODULE_PKG, FILE);
            Integer c = x.contains(KEY_BG) ? Integer.valueOf(x.getInt(KEY_BG, DEFAULT_BG)) : null;
            Boolean h = x.contains(KEY_HIDE_PROFILE)
                    ? Boolean.valueOf(x.getBoolean(KEY_HIDE_PROFILE, false)) : null;
            Integer ac = x.contains(KEY_ACCENT) ? Integer.valueOf(x.getInt(KEY_ACCENT, OFF)) : null;
            Integer tx = x.contains(KEY_TEXT) ? Integer.valueOf(x.getInt(KEY_TEXT, OFF)) : null;
            Boolean pl = x.contains(KEY_PIN_LIMIT) ? Boolean.valueOf(x.getBoolean(KEY_PIN_LIMIT, true)) : null;
            Boolean ut = x.contains(KEY_UNKNOWN_TAB) ? Boolean.valueOf(x.getBoolean(KEY_UNKNOWN_TAB, true)) : null;
            String dord = x.contains(KEY_DOCK_ORDER) ? x.getString(KEY_DOCK_ORDER, DEFAULT_DOCK_ORDER) : null;
            Boolean al = x.contains(KEY_APPS_LEFT) ? Boolean.valueOf(x.getBoolean(KEY_APPS_LEFT, false)) : null;
            Boolean lf = x.contains(KEY_LIB_FIRST) ? Boolean.valueOf(x.getBoolean(KEY_LIB_FIRST, false)) : null;
            Boolean hp = x.contains(KEY_HIDE_PT) ? Boolean.valueOf(x.getBoolean(KEY_HIDE_PT, false)) : null;
            Boolean hb = x.contains(KEY_HIDE_BATT_ICON) ? Boolean.valueOf(x.getBoolean(KEY_HIDE_BATT_ICON, false)) : null;
            Boolean bp = x.contains(KEY_BATT_PERCENT) ? Boolean.valueOf(x.getBoolean(KEY_BATT_PERCENT, true)) : null;
            Boolean bge = x.contains(KEY_BG_ENABLED) ? Boolean.valueOf(x.getBoolean(KEY_BG_ENABLED, true)) : null;
            Boolean hnb = x.contains(KEY_HIDE_NOTIF_BADGE) ? Boolean.valueOf(x.getBoolean(KEY_HIDE_NOTIF_BADGE, false)) : null;
            return new Object[]{c, h, ac, tx, pl, ut, dord, al, lf, hp, hb, bp, bge, hnb};
        }
    }

    private static String hexOrOff(int rgb) {
        return rgb == OFF ? "off" : "#" + hex(rgb);
    }

    private static String hex(int argb) {
        return String.format("%06X", argb & 0xFFFFFF);
    }
}
