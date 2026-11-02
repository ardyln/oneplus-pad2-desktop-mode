# Tearing with many windows in desktop mode

**Device:** OnePlus Pad 2 (`caihong`, OPD2403), PixelOS `17.0-20261022-1226`
(Android 17, `CP2A.260605.016`), kernel `6.1.174-g638ecc425319`, vendor from
OxygenOS 16 (`OPD2403IN … U.R4T3.31362f8-b6a7dc-bdc05c`). Panel 3000×2120,
run at 120 Hz.

## Symptom

Open enough windows in desktop mode and the screen starts to tear and stutter.
It is most visible while video plays (YouTube app or YouTube in Chrome) and
while windows are being moved. Screenshots look clean, because they are taken
from SurfaceFlinger's composition, not from the panel.

## What actually fails

The kernel display driver rejects the frame that the vendor compositor asks for,
on every frame:

```
[drm:_sde_crtc_check_zpos:6199] [sde error]> 2 planes @ stage 0
[drm:_sde_crtc_atomic_check:6408] [sde error]crtc205 failed pstate check -22
```

The Qualcomm composer (SDM, `vendor.qti.hardware.display.composer-service`)
tries all its strategies, ending with "GPU composes everything into one buffer".
That is rejected too, and the frame is not presented:

```
E/SDM : DRMAtomicReq::Validate: drmModeAtomicCommit failed with error 22 (Invalid argument).
I/SDM : HWDeviceDRM::DumpHWLayers: HWLayers Stack: layer_count: 1, app_layer_count: 40, gpu_target_index: 40
I/SDM : HWDeviceDRM::DumpHWLayers: pipe = 1, pipe_id = 160, z_order = 0 ... dst_rect: x = 0,    w = 750
I/SDM : HWDeviceDRM::DumpHWLayers: pipe = 2, pipe_id = 156, z_order = 0 ... dst_rect: x = 750,  w = 750
I/SDM : HWDeviceDRM::DumpHWLayers: pipe = 3, pipe_id = 152, z_order = 0 ... dst_rect: x = 1500, w = 750
I/SDM : HWDeviceDRM::DumpHWLayers: pipe = 4, pipe_id = 148, z_order = 0 ... dst_rect: x = 2250, w = 750
E/SDM : CompManager::Prepare: Composition strategies exhausted for display = 69-0. (first frame = False)
E/SurfaceFlinger: getDeviceCompositionChanges: validate failed for display …: BAD_DISPLAY (2)
E/SurfaceFlinger: presentAndGetReleaseFences: present failed for display …: NOT_VALIDATED (7)
```

With 9 desk windows (40 HWC layers) this was **366 rejections in 3 seconds**,
i.e. every frame at 120 Hz. With 4 windows (20 layers) it did not happen. It
comes and goes as the layer count and on-screen activity change; it is not a
clean "more than N windows" limit.

The rejected request is the simplest possible layout: one full-screen GPU target
cut into four 750 px strips, one per layer mixer. The panel runs a quad-pipe
topology (4 mixers, `Topology: 9`, `LM_Split: true`):

```
crtc:205 width:3000 height:2120
	mixer:0 ctl:0 width:750 height:2120
	mixer:1 ctl:0 width:750 height:2120
	mixer:2 ctl:0 width:750 height:2120
	mixer:3 ctl:0 width:750 height:2120
```

Raw excerpts are in [`evidence/`](../evidence).

## Cause: display driver older than the vendor compositor

The tablet's `msm_drm.ko` reports `scmversion gec04e4f8d5c9`. That is
[LineageOS/android_kernel_oneplus_sm8650-modules@ec04e4f8d5c9](https://github.com/LineageOS/android_kernel_oneplus_sm8650-modules/tree/ec04e4f8d5c96d6beb41626d3e5af4f10e0ac189),
whose `qcom/opensource/display-drivers` was last imported from the
**OnePlus 12, OxygenOS 15.0.0.850** ("Import changes from CPH2581_15.0.0.850(EX01)").
The vendor composer on the device comes from the **Pad 2's OxygenOS 16**.

The check that rejects our frames differs between the two:

LineageOS ([`msm/sde/sde_crtc.c:6198`](https://github.com/LineageOS/android_kernel_oneplus_sm8650-modules/blob/ec04e4f8d5c96d6beb41626d3e5af4f10e0ac189/qcom/opensource/display-drivers/msm/sde/sde_crtc.c#L6198)), what PixelOS runs:

```c
		} else if (zpos_cnt == 2) {
			SDE_ERROR("> 2 planes @ stage %d\n", z_pos);
			return -EINVAL;
```

OnePlus Pad 2, OxygenOS 16 source
([OnePlusOSS `oneplus/sm8650_b_16.0.0_pad2`, `sde_crtc.c:6142`](https://github.com/OnePlusOSS/android_kernel_modules_and_devicetree_oneplus_sm8650/blob/3b0ed1b3edbcbffec3fdf43e4e7e54730a7c08a9/vendor/qcom/opensource/display-drivers/msm/sde/sde_crtc.c#L6142)):

```c
		} else if (sde_crtc->num_mixers && (zpos_cnt == 2*((sde_crtc->num_mixers + 1)/2))) {
			SDE_ERROR("> %d planes @ stage %d\n", 2*((sde_crtc->num_mixers + 1)/2),
				z_pos);
			return -EINVAL;
```

With 4 mixers, OnePlus's driver allows **4** planes per blend stage; LineageOS's
allows **2**. The OOS16 composer relies on the former.

Most frames still pass on the LineageOS driver because
`_sde_crtc_check_plane_layout()` normally splits quad-pipe planes into
`SDE_LAYOUT_LEFT`/`SDE_LAYOUT_RIGHT`, and `_sde_crtc_check_zpos()` counts each
half separately (2 + 2). That split only happens when
`sde_rm_topology_is_group(…, SDE_RM_TOPOLOGY_GROUP_QUADPIPE)` returns true, which
reads the topology from `drm_atomic_get_new_connector_state()`. **My reading of
the code (not traced)** is that commits which don't carry the connector state
leave all four strips at `SDE_LAYOUT_NONE`, so they are counted as one group of
4 and rejected.

### Other differences in the same file

The rest of the `sde_crtc.c` diff between the two trees (dest-scaler quad
op-mode, mode-clock "bubble" factor for quad pipe, LLCC reset on disable,
`adjusted_mode` taken from the new CRTC state in `_sde_crtc_check_plane_layout`)
was not needed for this fix. It is worth reviewing when syncing the
driver with the Pad 2 OOS16 source.

## Verification: runtime test

To test without rebuilding or flashing the kernel, [`module/zpos4.c`](../module/zpos4.c)
puts a kprobe on the one instruction that implements `zpos_cnt == 2` in the
device's `msm_drm.ko`:

```
sde_crtc_atomic_check+0xbc0:  cmp w23, #0x2        ; zpos_cnt == 2
sde_crtc_atomic_check+0xbc4:  b.eq <"> 2 planes @ stage" error path>
```

and emulates `cmp w23, #4` (sets NZCV, advances PC). It registers the probe
disarmed, checks that the original opcode really is `0x71000aff`
(`subs wzr, w23, #2`), and only then arms it, so on any other `msm_drm` build it
refuses to load. Nothing is written to flash.

Results on the same device, same desk:

| Test | Kernel rejections | SDM / SF errors | GPU-target frames |
|---|---|---|---|
| Before: 9 windows, 54 layers, idle UI | 366 / 3 s (every frame) | every frame | n/a |
| Module loaded at runtime, same desk | 0 | 0 | n/a |
| After reboot, module loaded at boot (KernelSU), 13 windows, scrolling + Overview | 0 | 0 | 897 |
| YouTube app, 60 fps video, 15 s | 0 | 0 | 1119 |
| YouTube in Chrome, 60 fps video, windows dragged by touch and mouse, 20 s | 0 | 0 | 2034 |
| YouTube app playing, other windows dragged by touch and mouse, 20 s | 0 | 0 | 1773 |

(Mouse drags were injected with `input mouse swipe`.)

## Remaining: GPU load

Even without rejections, SDM composes most of a busy desktop on the GPU (a
3000×2120 target at 120 Hz with 40–50 layers, plus Night Light's color matrix).
SurfaceFlinger still counts GPU missed frames under that load. That would show up as
occasional stutter, not tearing, and is a separate issue.

## Proposed fix

Apply the Pad 2 OOS16 version of the stage limit in
`qcom/opensource/display-drivers/msm/sde/sde_crtc.c` (`_sde_crtc_check_zpos`):

```diff
-		} else if (zpos_cnt == 2) {
-			SDE_ERROR("> 2 planes @ stage %d\n", z_pos);
+		} else if (sde_crtc->num_mixers && (zpos_cnt == 2*((sde_crtc->num_mixers + 1)/2))) {
+			SDE_ERROR("> %d planes @ stage %d\n", 2*((sde_crtc->num_mixers + 1)/2),
+				z_pos);
 			return -EINVAL;
```

Better still, sync the display driver with the Pad 2 OOS16 tree, which is what
the shipped vendor composer was built against.
