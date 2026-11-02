#!/system/bin/sh
APK=$(cat "${0%/*}/target" 2>/dev/null)
[ -n "$APK" ] && rm -f /data/dalvik-cache/*/"$(echo "${APK#/}" | tr / @)"@*
