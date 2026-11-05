#!/bin/bash
# Smoke test on an emulator: install, open the main screens, play a station,
# switch to a tablet-sized screen, and report crashes. Screenshots are printed
# into the log as base64 JPEGs between SHOT-BEGIN / SHOT-END markers.
set -u
APK="$1"
PKG=com.yudhis7.gelombang

shot() {
  local name="$1"
  adb exec-out screencap -p > "/tmp/$name.png" 2>/dev/null || return 0
  convert "/tmp/$name.png" -resize 480x -quality 55 "/tmp/$name.jpg" 2>/dev/null || cp "/tmp/$name.png" "/tmp/$name.jpg"
  echo "SHOT-BEGIN $name"
  base64 "/tmp/$name.jpg"
  echo "SHOT-END $name"
}

texts() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml 2>/dev/null | grep -oE '(text|content-desc)="[^"]+"' | sort -u | head -80 | tr '\n' ' '
  echo
}

# Centre of the first node whose text or description matches exactly ("x y").
find_xy() {
  adb shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
  adb shell cat /sdcard/ui.xml 2>/dev/null | python3 -c '
import re, sys
x = sys.stdin.read(); t = re.escape(sys.argv[1])
for node in re.findall(r"<node [^>]*>", x):
    if re.search(r"(text|content-desc)=\"" + t + "\"", node):
        b = re.search(r"bounds=\"\[(\d+),(\d+)\]\[(\d+),(\d+)\]\"", node)
        if b:
            a, c, d, e = map(int, b.groups()); print((a + d) // 2, (c + e) // 2); break
' "$1"
}

tap() {
  local xy
  xy=$(find_xy "$1")
  if [ -n "$xy" ]; then adb shell input tap $xy; echo "tapped '$1' at $xy"; else echo "NOT FOUND: '$1'"; fi
}

media() {
  adb shell dumpsys media_session 2>/dev/null | grep -E "package=$PKG|state=PlaybackState|metadata:|description=" | head -8
}

echo "== install"
adb install -r "$APK"
adb logcat -c

echo "== launch (phone)"
adb shell am start -W -n $PKG/.ui.MainActivity
sleep 14
texts; shot phone-home

echo "== explore"
tap "Explore"; sleep 6; texts; shot phone-explore

echo "== Indonesia"
tap "Indonesia"; sleep 10; texts; shot phone-country

echo "== play first station"
W=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f1)
H=$(adb shell wm size | grep -oE '[0-9]+x[0-9]+' | tail -1 | cut -dx -f2)
# first station row sits under the title and genre chips
adb shell input tap $((W / 3)) $((H * 30 / 100))
sleep 15; texts; shot phone-playing; media

echo "== pause / resume"
tap "Pause"; sleep 2; texts; tap "Play"; sleep 8; media

echo "== now playing"
XY=$(find_xy "Pause"); [ -z "$XY" ] && XY=$(find_xy "Play")
Y=${XY#* }
adb shell input tap $((W / 4)) ${Y:-$((H * 85 / 100))}
sleep 3; texts; shot phone-nowplaying
tap "Close"; sleep 2

echo "== search"
tap "Search"; sleep 2
adb shell input text "jazz"; sleep 8; texts; shot phone-search

echo "== library"
tap "Library"; sleep 3; texts; shot phone-library

echo "== tablet layout"
adb shell wm size 2560x1600
adb shell wm density 320
adb shell am start -W -n $PKG/.ui.MainActivity
sleep 6; texts; shot tablet-home
tap "Explore"; sleep 10; texts; shot tablet-explore
adb shell wm size reset
adb shell wm density reset

echo "== playback service"
adb shell dumpsys activity services $PKG | grep -E "ServiceRecord|isForeground" | head -4
media

echo "== crashes"
adb logcat -d -b crash | tail -60
adb logcat -d | grep -E "FATAL|AndroidRuntime|ANR in" | head -40
echo "== app warnings"
APP_PID=$(adb shell pidof $PKG | tr -d '\r')
[ -n "$APP_PID" ] && adb logcat -d --pid="$APP_PID" | grep -E " [WE] " | grep -v "TextView\|hiddenapi" | head -60
echo "== app log"
adb logcat -d | grep -iE "gelombang|ExoPlayer|MediaSession|media3" | grep -iE "error|exception|fail" | head -40
echo "== done"
