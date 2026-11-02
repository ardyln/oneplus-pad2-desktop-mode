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

Somewhere between `0x0111006e` (still matching) and `0x011101f2`, PixelOS's
framework-res has 14 more bool entries than the framework the launcher was built
against. I have not identified which entries: they could be PixelOS additions
or differences between AOSP and Google's internal resource set. With
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

## Proposed fix

PixelOS ships Google's prebuilt launcher, so `framework-res` has to keep the
internal resource IDs that launcher was built against. The extra bool entries
before `config_hideDisplayCutoutWithDisplayArea` must not shift the IDs, for
example by moving additions out of the `android` package or by pinning IDs. It is
worth checking which other internal IDs the prebuilt launcher (and other prebuilt
Google apps) read, since this range probably isn't the only one affected.
