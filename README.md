# OnePlus Pad 2 desktop mode on PixelOS 17: two bugs, root-caused

Two desktop-mode bugs on the OnePlus Pad 2 (`caihong`) running PixelOS 17
(`17.0-20261022-1226`), traced to their causes and verified on the device.

| Bug | Cause | Status |
|---|---|---|
| **Tearing/stutter with many windows**, worst with video playing | The kernel display driver (LineageOS sm8650-modules, imported from OnePlus 12 OOS 15) allows 2 planes per blend stage. The Pad 2's OOS 16 driver allows 4 on its quad-pipe panel, and the OOS 16 vendor compositor relies on that. Every frame gets rejected (`> 2 planes @ stage 0`). | One-line kernel fix identified; verified with a runtime test module |
| **Gray, empty "Desktop" card in Overview**; "No recent items" when the desk is in the background | The prebuilt Pixel Launcher reads framework-internal bool resources by numeric ID. PixelOS's own added resources (`custom_symbols.xml`) shift those IDs, so it reads `config_hideNavBarForKeyboard` (false) instead of `config_isDesktopModeSupported` (true) and concludes the device has no desktop mode. 6 IDs are shifted in total | Fixed and verified with a module that patches the launcher on the device |

Full write-ups:

- [docs/TEARING.md](docs/TEARING.md): kernel logs, compositor logs, source comparison, test module, measurements.
- [docs/OVERVIEW.md](docs/OVERVIEW.md): SurfaceFlinger layers, launcher recents state, all shifted resource IDs, the patching module, workaround.

Log excerpts are in [`evidence/`](evidence).

## Tearing test fix (KernelSU module)

[`module/zpos4.c`](module/zpos4.c) is a small kernel module. It puts a kprobe
on the one instruction in `msm_drm` that implements the 2-plane limit
(`cmp w23, #2` at `sde_crtc_atomic_check+0xbc0`) and makes it compare against 4
instead. It changes nothing on flash. Before arming, it checks the instruction
and refuses to load on any other `msm_drm` build.

| | Kernel rejections | Compositor/SurfaceFlinger errors |
|---|---|---|
| Before (9 windows) | 366 per 3 s (every frame) | every frame |
| With the module, after a reboot, 13 windows, YouTube 60 fps in the app and in Chrome, windows dragged by touch and mouse | 0 | 0 |

**This is a test, not a product.** It is built for exactly one kernel:
`6.1.174-g638ecc425319` (PixelOS caihong `17.0-20261022-1226`), with symbol CRCs
computed for that kernel. On other builds the kernel will refuse it, or the
module refuses itself. The real fix belongs in the kernel source.

Install: download `zpos4_test.zip` from the
[releases](../../releases) and install it in KernelSU, then reboot.
Remove: uninstall `zpos4_test` in KernelSU and reboot.

Check that it's active:

```
adb shell su -c 'cat /sys/kernel/debug/kprobes/list' | grep sde_crtc_atomic_check
# 0000000000000000  k  sde_crtc_atomic_check+0xbc0  msm_drm
```

Build it yourself: [`tools/build.sh`](tools/build.sh). It needs the LineageOS
kernel (`638ecc425319`) and sm8650-modules (`ec04e4f8d5c9`) trees, clang/lld 22,
flex, bison, pahole and libelf. The script reads the running config from the
tablet and checks `module_layout`'s CRC against the device (`0xea759d7f`).

## Overview fix (KernelSU/Magisk module)

[`launcher/`](launcher) patches **your device's own** Pixel Launcher when you
install it. The zip contains only the patcher and an ID table, nothing from
Google. It rewrites the 6 framework resource IDs the launcher reads at the wrong
place (9 instructions):

- desktop-mode support, which fixes the Overview card;
- the app-launch blur, on now, as Google intended;
- the search entry points default;
- the taskbar's IME-switcher icon.

The new IDs are looked up by name on the device. The APK keeps its size and its
original signing block. Android doesn't verify APKs on system partitions at boot,
so the launcher keeps Google's signature, and the Google app's −1 feed keeps
working. At boot the patched copy is bind-mounted over the stock APK; nothing on
flash changes. Details and verification: [docs/OVERVIEW.md](docs/OVERVIEW.md#fix-patch-the-launchers-ids-kernelsumagisk-module).

- **Install:** download `launcher_resid_fix.zip` from the [releases](../../releases),
  install it in KernelSU, and reboot. If you used the workaround below, delete the
  prop first: `adb shell su -c 'resetprop -p --delete persist.wm.debug.desktop_mode_enforce_device_restrictions'`.
- **After a system update:** the module only applies its patch while the stock
  launcher and `framework-res.apk` are unchanged. Otherwise it does nothing, and
  its description says so. Press the module's Action button to patch again, then reboot.
- **Supported:** only launcher APKs listed in [`launcher/ids.txt`](launcher/ids.txt)
  (currently the one in PixelOS caihong `17.0-20261022-1226`). For any other APK
  the installer stops without changing anything. A new launcher needs a new
  table; [`tools/remap-resource-ids.py`](tools/remap-resource-ids.py) shows how
  this one was derived.
- **Remove:** uninstall `launcher_resid_fix` in KernelSU, then reboot.
- **Build:** [`tools/build-launcher-fix.sh`](tools/build-launcher-fix.sh) (a JDK and d8).

## Overview workaround (without the module)

```
adb shell su -c 'setprop persist.wm.debug.desktop_mode_enforce_device_restrictions false'
adb shell am force-stop com.google.android.apps.nexuslauncher
```

This is a debug switch that also lifts Shell's desktop-mode device restrictions,
and it fixes only the desktop-mode check, not the other shifted IDs.
See [docs/OVERVIEW.md](docs/OVERVIEW.md).

## Binaries examined

| File | SHA-256 |
|---|---|
| `/vendor_dlkm/lib/modules/msm_drm.ko` (scmversion `gec04e4f8d5c9`) | `807cb2962e9b6c5aa55b28cb4127e1d09131fa423f68c55d2026610b64ce7381` |
| `/system_ext/priv-app/NexusLauncherReleaseTablet/NexusLauncherReleaseTablet.apk` | `91a82304b1ead531f3109d00dff990000d7f12cf5215c7906762e44d2d883ad1` |
| `/system/framework/framework-res.apk` | `5e7d617fa4c38b135884557098057482f7715473814b39852e8b7045a41e38ed` |

## License

Write-up, scripts, the launcher patcher and KernelSU files: GPL-3.0-or-later ([LICENSE](LICENSE)).
The kernel module [`module/zpos4.c`](module/zpos4.c) is GPL-2.0, as required
for code linked into the Linux kernel ([module/COPYING](module/COPYING)).

The investigation, modules and write-up were done with an AI assistant (Claude), under the author's direction, and verified on the author's tablet.
