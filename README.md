# Quest UX Patcher (Vector module)
Allows for customizing colors for SystemUX (Dock UI & Nav UI), Raises the max pinned apps on Dock UI to 10, shows the battery percentage on Dock UI, and forces the Unknown Sources tab to be visible.

## Requirements
* Rooted Meta Quest Headset (Pre-September 24th 2026 firmware)
* Magisk w/ Zygisk: Yes
* LSPosed/Vector installed and active in Magisk

## How to use (first install)
1. If your Quest headset isn't rooted and it's on a supported firmware for rooting, do so with [Singularity](https://github.com/Lumince/singularity/releases/)
2. Open Magisk Manager and install the latest stable [Vector](https://github.com/JingMatrix/Vector/releases/) Magisk module
3. Check that Magisk shows `Zygisk: Yes` on the main page. If it does, reboot and go to step 5
4. If `Zygisk: Yes` isn't shown, go into Magisk settings and toggle Zygisk off then on. Then open Singularity → AIO Tweaks → Utils → Fix Magisk Zygisk → Apply
5. Root your device again and install `UXPatcher.apk`
6. Open Vector, enable UX Patcher, and select all the apps it prompts you to scope
7. Open UX Patcher, accept the Magisk root prompt, and press **Kill Processes** \
**If you have issues with your UI disappearing, run this command in shell ( I am actively looking into the issue )** \
`adb shell am force-stop com.oculus.vrshell`


## Trouble finding target apps?
Tap on the module \
Press this \
<img width="70" height="52" alt="image" src="https://github.com/user-attachments/assets/ac4ebcfc-15e5-486c-a0fd-f6d4bdc05768" /> \
Then select this \
<img width="375" height="74" alt="image" src="https://github.com/user-attachments/assets/e895b82b-1292-40c4-a91d-7e34716a899d" />

## App UI
* **Background color**: pure black by default. Pick a colour and press **Set Color**, or **Reset** to go back to Black.
* **Accent color** and **Text color**: **Default** to White.
* **Dock**: *Hide profile icon* and *Raise pin limit to 10*.
* **Library**: *Force Unknown Sources tab visible*.
* **Kill Processes**: restarts the target apps
## Supported versions
The module is setup to find the parts it needs to patch via their *shape* instead of using the obfuscated class names. This will make it so it should just continue to work on newer firmware, as long as the code its patching is still there.

## Logging

```
adb logcat -s UXPatcher:* AndroidRuntime:E
```
