package com.lumi.uxpatcher.firmware;

/** Class names that moved between firmware builds. Arrays are candidates: newest first, first class that exists wins. */
public final class FirmwareNames {
    private FirmwareNames() {}

    // ── OCUI widgets ──────────────────────────────────────────────────────────────────────
    public static final String[] OC_SIDE_NAV = {
            "com.oculus.ocui.view.navigation.OCSideNav",
            "com.oculus.ocui.OCSideNav"};

    public static final String[] OC_RECYCLER_VIEW = {
            "com.oculus.ocui.view.list.OCRecyclerView",
            "com.oculus.ocui.OCRecyclerView"};

    /** Root of Quick Settings / floating panels. */
    public static final String[] OC_MULTI_LAYER_CONTAINER = {
            "com.oculus.ocui.view.popup.OCMultiLayerContainer",
            "com.oculus.ocui.OCMultiLayerContainer"};

    /** The translucent "glass" panel background (dock pill, Library panels, cards). */
    public static final String[] OC_PANEL_BACKGROUND = {
            "com.oculus.ocui.view.drawable.OCPanelBackgroundDrawable",
            "com.oculus.ocui.OCPanelBackgroundDrawable"};

    // ── SystemUX dock ─────────────────────────────────────────────────────────────────────
    public static final String[] PIN_BINDER = {
            "com.oculus.common.navigatoritempinningservice"
                    + ".NavigatorItemPinningService$NavigatorItemPinningManagerBinder"};

    public static final String BAR_VIEW =
            "com.oculus.panelapp.anytimeui.bar.BarView";
    public static final String DYNAMIC_APPS_VIEW =
            "com.oculus.panelapp.anytimeui.bar.apps.DynamicAppsView";
    public static final String SYSTEM_STATUS_VIEW =
            "com.oculus.panelapp.anytimeui.bar.status.SystemStatusView";

    // ── VrShell window bars ───────────────────────────────────────────────────────────────
    public static final String CONTROL_BAR_BASE_ACTIVITY =
            "com.oculus.panelapp.controlbar.BaseControlBarActivity";
    public static final String CONTROL_BAR_HANDLE_ACTIVITY =
            "com.oculus.panelapp.controlbar.HandleBarControlBarActivity";
    public static final String COMPOSE_ANDROID_VIEW =
            "androidx.compose.ui.platform.AndroidComposeView";

    // ── VrShell on-screen keyboard ────────────────────────────────────────────────────────
    /** Package prefixes of the keyboard's own views. */
    public static final String[] KEYBOARD_VIEW_PREFIXES = {
            "com.oculus.panelapp.keyboardv2.",
            "com.oculus.panelapp.keyboard."};
    /** The views that draw the keys into a cached Bitmap with a software Canvas. */
    public static final String[] KEYBOARD_KEY_VIEWS = {
            "com.oculus.panelapp.keyboardv2.KeyboardView",
            "com.oculus.panelapp.keyboardv2.KeyboardPopupView"};
    /** Other keyboard views that also open the colour gate (mic button, readout bar). */
    public static final String[] KEYBOARD_GATE_CLASSES = {
            "com.oculus.panelapp.keyboardv2.DictationToggleButton",
            "com.oculus.panelapp.keyboardv2.DictationToggleButtonFrame",
            "com.oculus.panelapp.keyboardv2.DictationToggleButtonLanguageIndicator",
            "com.oculus.panelapp.keyboardv2.KeyboardPanelView",
            "com.oculus.panelapp.keyboardv2.ReadoutPanelView",
            "com.oculus.panelapp.keyboardv2.ReadoutEditText",
            "com.oculus.panelapp.keyboardv2.ReadoutCaretMoveButton",
            "com.oculus.panelapp.keyboardv2.ReadoutDeleteButton",
            "com.oculus.panelapp.keyboardv2.SwipeTrailView"};

    // ── Meta IPC (stable AIDL) ────────────────────────────────────────────────────────────
    public static final String RESIZE_IPC_DESCRIPTOR =
            "com.oculus.vrshell.privateipc.IResizeIPCService";
    /** First AIDL method (FIRST_CALL_TRANSACTION). */
    public static final int RESIZE_IPC_TRANSACTION = 0x65;

    // ── helpers ───────────────────────────────────────────────────────────────────────────
    /** Loads the first class in {@code names} that exists. */
    public static Class<?> load(ClassLoader cl, String... names) throws ClassNotFoundException {
        ClassNotFoundException last = null;
        for (String n : names) {
            try {
                return cl.loadClass(n);
            } catch (ClassNotFoundException e) {
                last = e;
            }
        }
        throw last != null ? last : new ClassNotFoundException("no class names given");
    }

    /** True if {@code cls} is (or extends) one of the named classes. */
    public static boolean isNamed(Class<?> cls, String... names) {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            for (String n : names) if (c.getName().equals(n)) return true;
        }
        return false;
    }
}
