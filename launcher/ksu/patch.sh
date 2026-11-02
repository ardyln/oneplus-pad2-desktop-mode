#!/system/bin/sh
# Patch this device's Pixel Launcher into $MODDIR/launcher.apk.
# Used by customize.sh (install) and action.sh (re-patch after an update).
MODDIR=${0%/*}
PKG=com.google.android.apps.nexuslauncher

fail() { echo "! $*"; exit 1; }

APK=$(pm path "$PKG" 2>/dev/null | head -1 | sed 's/^package://')
[ -n "$APK" ] || fail "$PKG is not installed"
case "$APK" in
	/system/*|/system_ext/*|/product/*) ;;
	*) fail "$PKG is installed as an update in $APK; only the system copy can be patched" ;;
esac
awk -v p="$APK" '$5 == p { found = 1 } END { exit !found }' /proc/self/mountinfo &&
	fail "a patched launcher is already mounted over $APK; disable the module, reboot, then try again"

echo "- Patching $APK"
CLASSPATH="$MODDIR/patcher.dex" app_process / Patcher "$APK" "$MODDIR/launcher.apk.new" "$MODDIR/ids.txt" ||
	{ rm -f "$MODDIR/launcher.apk.new"; fail "patching failed, nothing changed"; }
mv "$MODDIR/launcher.apk.new" "$MODDIR/launcher.apk"
chmod 644 "$MODDIR/launcher.apk"
chcon u:object_r:system_file:s0 "$MODDIR/launcher.apk"
echo "$APK" > "$MODDIR/target"
# What the patch depends on: the stock launcher and the framework IDs it was resolved against.
sha256sum "$APK" /system/framework/framework-res.apk | cut -d' ' -f1 > "$MODDIR/stock.sha256"

# Drop compiled code of the stock APK so ART recompiles the patched one.
rm -f /data/dalvik-cache/*/"$(echo "${APK#/}" | tr / @)"@*
echo "- Done. Reboot to apply."
