#!/system/bin/sh
# Bind the patched launcher over the stock one, before the package scan, but
# only if the stock APK and framework-res are still the ones it was made for.
MODDIR=${0%/*}
APK=$(cat "$MODDIR/target" 2>/dev/null)
[ -f "$MODDIR/launcher.apk" ] && [ -f "$APK" ] || exit 0

if [ "$(sha256sum "$APK" /system/framework/framework-res.apk | cut -d' ' -f1)" != "$(cat "$MODDIR/stock.sha256")" ]; then
	sed -i 's/^description=.*/description=⚠ Not applied: the launcher or framework changed since the patch (system update?). Press Action to patch it again, then reboot./' "$MODDIR/module.prop"
	exit 0
fi
sed -i 's/^description=⚠.*/description=Patches the Pixel Launcher on this device so its hard-coded framework resource IDs match PixelOS (fixes the empty Desktop card in Overview). Applied at boot by bind mount; nothing on flash is changed./' "$MODDIR/module.prop"
chcon u:object_r:system_file:s0 "$MODDIR/launcher.apk"
mount --bind "$MODDIR/launcher.apk" "$APK"
