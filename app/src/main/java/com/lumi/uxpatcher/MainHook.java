package com.lumi.uxpatcher;

import android.util.Log;

import com.lumi.uxpatcher.amoled.AmoledHooks;
import com.lumi.uxpatcher.amoled.MultiLayerContainerHook;
import com.lumi.uxpatcher.amoled.PanelBackgroundHook;
import com.lumi.uxpatcher.amoled.ReactNativeHook;
import com.lumi.uxpatcher.amoled.SideNavHooks;
import com.lumi.uxpatcher.amoled.ThemeHooks;
import com.lumi.uxpatcher.library.UnknownSourcesTabHook;
import com.lumi.uxpatcher.systemux.BatteryPercentHook;
import com.lumi.uxpatcher.systemux.DockDropZoneHook;
import com.lumi.uxpatcher.systemux.DockStatus;
import com.lumi.uxpatcher.systemux.DockPinLimitHook;
import com.lumi.uxpatcher.systemux.DockScrollHook;
import com.lumi.uxpatcher.systemux.DockWidenHook;
import com.lumi.uxpatcher.systemux.PinningServiceLogger;
import com.lumi.uxpatcher.systemux.ProfileButtonHook;
import com.lumi.uxpatcher.vrshell.ControlBarHook;
import com.lumi.uxpatcher.vrshell.KeyboardHooks;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam;

import static com.lumi.uxpatcher.Config.SYSTEMUX_PACKAGE;
import static com.lumi.uxpatcher.Config.TAG;
import static com.lumi.uxpatcher.Config.VRSHELL_PACKAGE;

/** Xposed entry point. Decides which hooks run in which package. */
public class MainHook implements IXposedHookLoadPackage {

    @Override
    public void handleLoadPackage(LoadPackageParam lp) {
        if (!Config.TARGET_PACKAGES.contains(lp.packageName)) return;
        Log.i(TAG, "Loaded into " + lp.packageName + " [module " + Config.BUILD_ID + "] (pid=" + android.os.Process.myPid()
                + ", process=" + lp.processName + ")");

        Hooks.run("CONFIG", lp, p -> Prefs.init(p.packageName));

        if (Config.DEBUG_DUMP && Config.DUMP_PACKAGES.contains(lp.packageName)) {
            Hooks.run("DUMP", lp, HierarchyDump::install);
        }

        if (SYSTEMUX_PACKAGE.equals(lp.packageName)) {
            installSystemUx(lp);
        } else if (VRSHELL_PACKAGE.equals(lp.packageName)) {
            installVrShell(lp);
        } else {
            installApp(lp);
        }

        // User accent / text colours (hooks nothing while both are off)
        Hooks.run("THEME", lp, ThemeHooks::install);
    }

    /** Panel apps (Settings, Files, Camera, ...): black backgrounds everywhere */
    private static void installApp(LoadPackageParam lp) {
        Hooks.run("AMOLED", lp, AmoledHooks::install);
        Hooks.run("RN", lp, ReactNativeHook::install);
        Hooks.run("PANEL", lp, PanelBackgroundHook::install);
        Hooks.run("GRADIENT", lp, p -> GradientAmoled.install(p, Config.GRADIENT_THRESHOLD));
        Hooks.run("SIDENAV", lp, SideNavHooks::install);
        Hooks.run("MULTILAYER", lp, MultiLayerContainerHook::install);
        if ("com.oculus.panelapp.library".equals(lp.packageName)) {
            Hooks.run("UNKNOWN-TAB", lp, UnknownSourcesTabHook::install);   // keep Unknown Sources visible
        }
    }

    /** SystemUX: dock and Library panels. No AMOLED/MultiLayer hooks here, a square black fill would square the dock pill. */
    private static void installSystemUx(LoadPackageParam lp) {
        // Tell Dock Editor the module is loaded (limit=0 until the pin patch publishes the real one)
        try { DockStatus.publishLoaded(); } catch (Throwable t) { Log.w(TAG, "DockStatus.publishLoaded failed", t); }
        Hooks.run("PANEL", lp, PanelBackgroundHook::install);
        Hooks.run("SIDENAV", lp, SideNavHooks::install);
        Hooks.run("DOCK-MAX", lp, DockPinLimitHook::install);
        Hooks.run("DOCK-WIDEN", lp, DockWidenHook::install);
        Hooks.run("SCROLL", lp, DockScrollHook::install);   // also provides the width probe for DOCK-WIDEN
        Hooks.run("PIN-API", lp, PinningServiceLogger::install);
        Hooks.run("PROFILE", lp, ProfileButtonHook::install);
        Hooks.run("DROPZONE", lp, DockDropZoneHook::install);
        Hooks.run("BATTERY", lp, BatteryPercentHook::install);
    }

    /** VrShell: window bars (title pill + drag handle) and keyboard only */
    private static void installVrShell(LoadPackageParam lp) {
        Hooks.run("SIDENAV", lp, SideNavHooks::install);
        Hooks.run("CONTROLBAR", lp, ControlBarHook::install);
        Hooks.run("KEYBOARD", lp, KeyboardHooks::install);
    }
}
