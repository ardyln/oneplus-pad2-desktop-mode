#!/usr/bin/env bash
# Build zpos4.ko against the kernel PixelOS caihong 17.0-20261022 runs, and
# package it as a KernelSU module zip.
#
#   KDIR=/path/to/android_kernel_oneplus_sm8650   (LineageOS, 638ecc425319)
#   with LineageOS android_kernel_oneplus_sm8650-modules (ec04e4f8d5c9) checked
#   out next to it as ../sm8650-modules (the kernel tree symlinks into it).
#
# Needs: clang/lld 22 (LLVM=1), flex, bison, pahole (dwarves), libelf-dev, adb
# with root on the tablet (to read /proc/config.gz).
set -euo pipefail

: "${KDIR:?set KDIR to the kernel source tree}"
: "${LLVM_BIN:=/usr/lib/llvm-22/bin}"
HERE=$(cd "$(dirname "$0")/.." && pwd)
OUT=$HERE/build
export PATH=$LLVM_BIN:$PATH

# CONFIG_DEBUG_INFO_BTF= on the command line only skips building the
# resolve_btfids host tool (which no longer compiles with newer host clang).
# autoconf.h still has DEBUG_INFO_BTF_MODULES=y, so struct module, and with it
# the module_layout CRC, matches the device.
MK=(make -C "$KDIR" ARCH=arm64 LLVM=1 CONFIG_DEBUG_INFO_BTF= -j"$(nproc)")

# 1. Use the running kernel's exact config.
adb shell 'su -c "cat /proc/config.gz"' | gunzip > "$KDIR/.config"
"${MK[@]}" olddefconfig >/dev/null
"${MK[@]}" modules_prepare
release=$(cat "$KDIR/include/config/kernel.release")
[ "$release" = "6.1.174-g638ecc425319" ] || echo "warning: kernel.release is $release"

# 2. CONFIG_MODVERSIONS=y: compute the CRCs of the symbols we import from the
#    same tree (genksyms writes them as "#SYMVER name crc" into .o.cmd files).
"${MK[@]}" kernel/kprobes.o kernel/printk/printk.o kernel/module/version.o
grep -h '#SYMVER' "$KDIR"/kernel/.kprobes.o.cmd \
	"$KDIR"/kernel/printk/.printk.o.cmd \
	"$KDIR"/kernel/module/.version.o.cmd |
	awk '{ printf "%s\t%s\tvmlinux\tEXPORT_SYMBOL_GPL\t\n", $3, $2 }' \
	> "$KDIR/Module.symvers"
# Sanity check against what the device's own modules were built with.
grep -q '^0xea759d7f	module_layout' "$KDIR/Module.symvers" ||
	{ echo "module_layout CRC does not match the device"; exit 1; }

# 3. Build the module.
rm -rf "$OUT"; mkdir -p "$OUT/src" "$OUT/ksu"
cp "$HERE"/module/zpos4.c "$HERE"/module/Makefile "$OUT/src/"
"${MK[@]}" M="$OUT/src" modules
modprobe --dump-modversions "$OUT/src/zpos4.ko"

# 4. KernelSU module zip.
cp "$OUT/src/zpos4.ko" "$HERE"/ksu/module.prop "$HERE"/ksu/post-fs-data.sh "$OUT/ksu/"
(cd "$OUT/ksu" && zip -q -r "$OUT/zpos4_test.zip" .)
sha256sum "$OUT/src/zpos4.ko" "$OUT/zpos4_test.zip"
