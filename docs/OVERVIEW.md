# Gray card in Overview (Recents) in desktop mode

**Device:** OnePlus Pad 2 (`caihong`), PixelOS `17.0-20261022-1226`
(Android 17, `CP2A.260605.016`). Launcher: prebuilt Pixel Launcher
`/system_ext/priv-app/NexusLauncherReleaseTablet` (versionCode 907).

## Symptom

- Swipe up and hold while a desk is active: Overview shows a **"Desktop" card
  that is a plain lavender/gray rectangle**. The desk's windows are not visible
  in it, so the card is useless.
- Go Home first (desk in the background), then open Overview:
  **"No recent items"**, although the desk holds recently used windows.

## Where the gray comes from

With the desk active, SurfaceFlinger shows the desk's windows as live surfaces,
already scaled and positioned *inside* the card, but **below** the launcher's
window (z 37). The launcher paints an opaque card over them:

```
VRI-com.google.android.apps.nexuslauncher/...NexusLauncherActivity   z 37  0,0-3000,2120
Caption of Task=146                                                   z 30  1099,477-1901,510
VRI-com.android.chrome/...Main                                        z 27  1099,477-1901,978
Task=146                                                              z 22
...
```

So the windows are rendered; the launcher just never builds task views for them.

## Shell side is fine

WM Shell's `RecentTasksController` returns the desk group correctly (ProtoLog
`WM_SHELL_RECENT_TASKS`):

```
generateList(getRecentTasks)
initializeDesksMap - allDeskIds: [6]
Task 146 is an active desktop task on desk 6
...
Adding desk 6 with tasks to grouped tasks
```

and the launcher receives it, but drops it
([`evidence/overview-launcher-recents-before.txt`](../evidence/overview-launcher-recents-before.txt)):

```
RecentTasksList:
  mResultsUi=[id=830, tasks=
  ]
  rawTasks=[
    Desk ID= 6, Desk Display ID=0, Tasks=[id=146 winMode=freeform ... ]
```

Pixel Launcher's `RecentTasksList.loadTasksInBackground` only turns a desk
group into a `DesktopTask` when its `DesktopState.canEnterDesktopMode` is true;
otherwise the group is silently skipped.

## Cause: shifted internal resource IDs

Pixel Launcher is a prebuilt Google app. It reads framework-internal
`com.android.internal.R.bool` resources by **numeric ID**, inlined at build
time from Google's framework. Its `DesktopModule_ProvideDesktopStateFactory`
(decompiled with jadx `--no-replace-consts`) reads:

```java
boolean z10 = SystemProperties.getBoolean("persist.wm.debug.desktop_mode_enforce_device_restrictions", true);
boolean z11 = context.getResources().getBoolean(17891826);   // 0x011101f2
boolean z12 = context.getResources().getBoolean(17891827);   // 0x011101f3
boolean z13 = context.getResources().getBoolean(17891438);   // 0x0111006e
desktopStateImpl.canEnterDesktopMode =
    ((!z10 ? true : z12) && ((ENABLE_PROJECTED_DISPLAY_DESKTOP_MODE || z13) && ENABLE_DESKTOP_WINDOWING_MODE))
    || (isDesktopModeForcedEnabled() && (!z10 || ((z12 && z13) || z11)));
```

This matches AOSP's `DesktopStateImpl` (z11 = `config_isDesktopModeDevOptionSupported`,
z12 = `config_isDesktopModeSupported`, z13 = `config_canInternalDisplayHostDesktops`).
On PixelOS's `framework-res.apk` (`aapt2 dump resources`) the IDs resolve to:

| ID | Launcher means | PixelOS framework-res has | Value |
|---|---|---|---|
| `0x0111006e` | `config_canInternalDisplayHostDesktops` | `config_canInternalDisplayHostDesktops` | true |
| `0x011101f2` | `config_isDesktopModeDevOptionSupported` | `config_hideDisplayCutoutWithDisplayArea` | false |
| `0x011101f3` | `config_isDesktopModeSupported` | `config_hideNavBarForKeyboard` | false |
| | | `config_isDesktopModeDevOptionSupported` is at `0x01110200` | false |
| | | `config_isDesktopModeSupported` is at `0x01110201` | **true** |

Between `0x0111006e` (still matching) and `0x011101f2`, PixelOS's framework-res
has 14 more bool entries than the framework the launcher was built against.
They are exactly the 14 bools PixelOS declares in
[`core/res/res/values/custom_symbols.xml`](https://github.com/PixelOS-AOSP/android_frameworks_base/blob/seventeen/core/res/res/values/custom_symbols.xml)
(`config_chargingControlEnabled`, `config_dt2sGestureAvailable`,
`config_hasAlertSlider`, …). aapt2 gives private resources IDs in sorted order,
so they land among the AOSP bools and push every later bool back. With
z12 = false and restrictions enforced, `canEnterDesktopMode` is false, so the
launcher treats the device as having no desktop mode. That explains both symptoms.

Ruled out along the way:

- `GroupedTaskInfo` parcel layout: identical in PixelOS Shell and in the launcher.
- Task snapshots: present for all desk tasks.
- Only the desk (and no older fullscreen tasks) is listed because
  `config_activeTaskDurationHours` = 6 hides tasks idle for more than 6 hours.
  That is the AOSP default, not part of this bug.

## Verification / workaround

```
adb shell su -c 'setprop persist.wm.debug.desktop_mode_enforce_device_restrictions false'
adb shell am force-stop com.google.android.apps.nexuslauncher
```

With z10 = false the launcher's formula becomes true without reading the
shifted IDs. Afterwards Overview shows the Desktop card with every window's
thumbnail, the "new desk" button appears, and the taskbar shows running
indicators. This is a debug switch: it also lifts Shell's device restrictions
for desktop mode. It is a test and workaround, not the fix.

To undo: set it back to `true` (or `resetprop --delete` it) and restart the launcher.

## All shifted IDs in the launcher

The launcher's dex code contains 552 `const` instructions with a value in the
framework range (`0x01xxxxxx`), 327 distinct values. Public resources have fixed
IDs, so only private ones can be shifted. A model of the shift: a resource's ID
in Google's build is its PixelOS ID minus the number of PixelOS-only resources
of the same type that sort before it. That model is in
[`tools/remap-resource-ids.py`](../tools/remap-resource-ids.py). It reproduces
both IDs above. It flags 7 values, and each was checked against the code that
uses it:

| Launcher reads | PixelOS has there | Meant (by the code that reads it) | Runtime value: now → fixed |
|---|---|---|---|
| `0x011101f3` ×2 | `config_hideNavBarForKeyboard` | `config_isDesktopModeSupported` (`DesktopModeStatus`, `DesktopModule`) | false → **true** |
| `0x011101f2` ×2 | `config_hideDisplayCutoutWithDisplayArea` | `config_isDesktopModeDevOptionSupported` | false → false |
| `0x011101c5` ×2 | `config_enableWcgMode` | `config_enterDesktopByDefaultOnFreeformDisplay` (read next to `persist.wm.debug.enter_desktop_by_default_on_freeform_display`) | false → false |
| `0x0111017c` | `config_eap_sim_based_auth_supported` | `config_enableAppLaunchBlur` (stored in `QuickstepTransitionManager.mIsAppLaunchBlurEnabled`) | false → **true** |
| `0x01110263` | `config_restart_radio_on_pdp_fail_regular_deactivation` | `config_searchAllEntrypointsEnabledDefault` (`ProvideSearchEntryPointsDefault`) | false → **true** |
| `0x01080467` | drawable `ic_ime_nav_back` | drawable `ic_ime_switcher` (the taskbar's `R.id.ime_switcher` button) | back arrow → switcher icon |
| `0x01040276` | string `config_defaultContextualSearchPackageName` | the same: stored in `ContextualSearchStateManager.mContextualSearchPackage` | not shifted; the model is wrong for strings |

Runtime values are from `cmd overlay lookup android android:bool/<name>`, which
includes the device's overlays. Every other private ID the launcher uses lies
before the first PixelOS addition of its type.

So besides the Overview bug, PixelOS currently also turns off the app-launch
blur and the search entry points default, and shows the wrong icon on the
taskbar's IME switcher button.

## Fix: patch the launcher's IDs (KernelSU/Magisk module)

[`launcher/`](../launcher) is a module that rewrites those IDs in the device's
own launcher. Nothing from Google is distributed. On install,
[`Patcher.java`](../launcher/Patcher.java) (run with `app_process`):

1. checks the stock APK's SHA-256 against [`ids.txt`](../launcher/ids.txt), which
   lists the Google IDs, their resource names and the expected number of uses;
2. looks up each name's ID on the running system
   (`Resources.getSystem().getIdentifier`);
3. decodes every method's instructions and rewrites the matching
   `const vAA, #+id` instructions. It stops if any count differs from the table;
4. fixes the dex SHA-1 and Adler-32 and the zip entry's CRC-32. The APK keeps its
   size and layout, and its signing block is untouched.

At boot, `post-fs-data.sh` bind-mounts the patched copy over the stock APK. It
does this only if the stock launcher and `framework-res.apk` still have the
hashes they had at install time. Otherwise it does nothing and says so in the
module description. The module's Action button patches again.

Why the signature doesn't break: the patched APK no longer matches its own v3
signature (`apksigner verify` fails with a digest mismatch). But Android doesn't
verify the contents of APKs on system partitions at boot. In this build's
`InstallPackageHelper.scanPackageForInitLI`, skipVerify is
`(parseFlags & PARSE_IS_SYSTEM_DIR) != 0`, so the certificates are only read
(`ApkSignatureVerifier.unsafeGetCertsWithoutVerification`). The launcher keeps
Google's signature (`8dbbc65a`), and with it the Google app's −1 feed. If the
launcher is ever updated through the Play Store (an APK in `/data/app`), the
module refuses to install.

Verified on the tablet (with `persist.wm.debug.desktop_mode_enforce_device_restrictions` deleted):

- The patch made on the device is byte-identical to the one made and checked on
  a PC (`3d6078f0…`), and only the 9 instructions, the two dex checksums and the
  CRCs differ from stock.
- After a reboot, the launcher runs from the bind-mounted copy with the same
  signature. Overview shows the Desktop card with all of the desk's windows and
  the "new desk" button. The −1 feed opens. There are no crashes.

Note for KernelSU 3.x: KernelSU no longer mounts a module's `system/` folder
unless a "metamodule" is installed, so this module bind-mounts the file itself.

## Proposed fix in PixelOS

PixelOS ships Google's prebuilt launcher, so `framework-res` has to keep the
internal resource IDs that launcher was built against. The resources in
`custom_symbols.xml` must not shift them, for example by moving the additions
out of the `android` package or by pinning IDs. Other prebuilt Google apps may
read shifted IDs too; only the launcher was checked.
