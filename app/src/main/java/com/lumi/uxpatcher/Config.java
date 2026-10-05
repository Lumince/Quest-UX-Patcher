package com.lumi.uxpatcher;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Every tunable and on/off switch. Per-build Meta class names live in FirmwareNames. */
public final class Config {
    private Config() {}

    /** Logcat tag for all hooks */
    public static final String TAG = "UXPatcher";

    /** Logged on every load, to show which build is running */
    public static final String BUILD_ID = "1.0.2";

    // ── Packages ──────────────────────────────────────────────────────────────
    public static final String SYSTEMUX_PACKAGE = "com.oculus.systemux";
    public static final String VRSHELL_PACKAGE  = "com.oculus.vrshell";
    public static final String SOCIAL_PACKAGE   = "com.oculus.socialplatform";

    /** Packages the module hooks. Keep in sync with res/values/arrays.xml. */
    public static final Set<String> TARGET_PACKAGES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            SYSTEMUX_PACKAGE,                       // System UX: dock / bar / Library panels
            "com.oculus.panelapp.settings",         // Settings
            "com.oculus.panelapp.quicksettings",    // Quick Settings panel
            VRSHELL_PACKAGE,                        // VrShell: window title pill + drag handle
            "com.oculus.horizon",                   // Home / Friends / Social
            "com.oculus.panelapp.library",          // Library
            "com.oculus.metacam",                   // Camera
            "com.oculus.hzosgallery",               // Gallery
            "com.oculus.store",                     // Store
            "com.oculus.tv",                        // TV
            "com.oculus.helpcenter",                // Help & Tips
            "com.oculus.systemutilities",           // Files
            SOCIAL_PACKAGE                          // Profile / Social panel
    )));

    // ── AMOLED colour rules ───────────────────────────────────────────────────
    /** A colour with R, G and B all below this counts as a dark background and is blacked out */
    public static final int AMOLED_THRESHOLD = 100;

    /** Same, for Compose gradient colours (see GradientAmoled) */
    public static final int GRADIENT_THRESHOLD = 96;

    /** Also black out translucent dark greys (alpha >= 0x60), keeping alpha */
    public static final boolean DARKEN_TRANSLUCENT = true;

    /** Quick controls: the grey tiles and slider pills (white at 30%) take the background colour */
    public static final boolean QUICK_TILES_BG = true;

    /** Navigator Library: same for the left-hand tiles (All, Unknown Sources, Downloads, Search, Sort) */
    public static final boolean LIBRARY_TILES_BG = true;

    /** Tiles above: the white/light grey hover highlight takes the background colour (same alpha) */
    public static final boolean TILE_HOVER_BG = true;

    /** Grey of the VrShell window drag line (RGB, alpha kept) */
    public static final int CONTROL_HANDLE_COLOR = 0x808080;

    // ── Dock ──────────────────────────────────────────────────────────────────
    /** Extra dp of dock left padding (hide-profile), on top of the measured right-hand gap */
    public static final int DOCK_LEFT_PAD_EXTRA_DP = 12;
    /** How many apps can be pinned to the dock */
    public static final int DOCK_PIN_LIMIT = 10;

    /** Let the dock's app list scroll sideways (off: the dock is widened instead) */
    public static final boolean DOCK_SCROLL = false;

    /** Show an X in the dock's "drop here to unpin" zone while a pinned app is dragged */
    public static final boolean DOCK_DROP_ZONE_X = true;
    /** Keep the idle 1x1 drop layer and open a real-size presenter for the Unpin box when a drag starts */
    public static final boolean DOCK_DROP_ZONE_BOX_ON_DRAG = true;

    /** Create the real-size presenter ~3.5 s after start-up so the first drag does not flicker */
    public static final boolean DOCK_DROP_ZONE_BOX_WARMUP = true;
    /** No Unpin box while a VR game runs (stock cannot unpin then) */
    public static final boolean DOCK_DROP_ZONE_HIDE_IN_GAME = true;

    /** Show the battery percentage next to the dock's battery icon */
    public static final boolean DOCK_BATTERY_PERCENT = true;

    /** Space after the battery percentage so it isn't cut off */
    public static final int DOCK_BATTERY_PERCENT_END_PAD_DP = 9;

    /** Max extra left dp for the clock */
    public static final int DOCK_CLOCK_LEFT_PAD_MAX_DP = 24;

    /** Padding tweaks (dp) when the apps are on the left */
    public static final int DOCK_SWAPPED_LEFT_NUDGE_DP = 0;
    public static final int DOCK_SWAPPED_RIGHT_NUDGE_DP = 0;

    /** Keep the Library's "Unknown Sources" tab always visible */
    public static final boolean LIBRARY_UNKNOWN_TAB_ALWAYS = true;

    /** Max dock widening, as a multiple of its original width */
    public static final float DOCK_MAX_WIDEN_FACTOR = 1.9f;

    // ── Diagnostics ───────────────────────────────────────────────────────────
    /** Log drawable types that reach View.setBackground unhandled */
    public static final boolean DEBUG_BG = false;

    /** Dump the view tree (tag UXPatcher-DUMP) in DUMP_PACKAGES */
    public static final boolean DEBUG_DUMP = false;

    /** Debug: logs the dock's view tree */
    public static final boolean DOCK_LAYOUT_DUMP = false;
    public static final Set<String> DUMP_PACKAGES = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            SYSTEMUX_PACKAGE,
            "com.oculus.panelapp.settings",
            "com.oculus.systemutilities",
            SOCIAL_PACKAGE)));

    /** Log every call and return of the pinning binder (tag PIN-API) */
    public static final boolean LOG_PINNING_API = false;
}
