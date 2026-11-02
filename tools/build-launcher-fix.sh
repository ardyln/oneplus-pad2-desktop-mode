#!/usr/bin/env bash
# Build the Pixel Launcher resource ID fix as a KernelSU/Magisk module zip.
# The zip contains only the patcher and the ID table; the launcher is patched on
# the device, from the device's own copy, when the module is installed.
#
# Needs: a JDK (javac, java) and d8 from the Android SDK build-tools.
#   D8_JAR=/path/to/build-tools/<version>/lib/d8.jar   (default: next to `d8` on PATH)
set -euo pipefail

HERE=$(cd "$(dirname "$0")/.." && pwd)
OUT=$HERE/build/launcher
D8_JAR=${D8_JAR:-$(dirname "$(readlink -f "$(command -v d8)")")/lib/d8.jar}

rm -rf "$OUT"
mkdir -p "$OUT/classes" "$OUT/zip"
javac -J-Xmx256m --release 8 -Xlint:-options -d "$OUT/classes" "$HERE/launcher/Patcher.java"
java -Xmx512m -cp "$D8_JAR" com.android.tools.r8.D8 --release --min-api 26 \
	--output "$OUT/zip" "$OUT/classes"/*.class
mv "$OUT/zip/classes.dex" "$OUT/zip/patcher.dex"
cp "$HERE"/launcher/ksu/* "$HERE/launcher/ids.txt" "$HERE/LICENSE" "$OUT/zip/"

zip="$HERE/build/launcher_resid_fix.zip"
rm -f "$zip"
(cd "$OUT/zip" && zip -q -r "$zip" .)
unzip -l "$zip"
sha256sum "$zip"
