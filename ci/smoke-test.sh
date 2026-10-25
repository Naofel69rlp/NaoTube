#!/usr/bin/env bash
# Installs NaoTube on the CI emulator, drives it with remote-control keys and takes
# screenshots of each screen, so every build can be checked visually.
set -x
mkdir -p shots

shot() {
  adb exec-out screencap -p > "shots/$1.png"
}

key() {
  adb shell input keyevent "$1"
  sleep "${2:-1.5}"
}

adb install -r NaoTube.apk
adb logcat -c
adb shell am start -W -n com.naotube.tv/.MainActivity
sleep 30
shot 01-accueil

key KEYCODE_DPAD_DOWN 3
shot 02-rangee
key KEYCODE_DPAD_RIGHT 3
shot 03-focus
key KEYCODE_DPAD_CENTER 15
shot 04-fiche-video
key KEYCODE_DPAD_CENTER 30
shot 05-lecteur
key KEYCODE_DPAD_UP 2
shot 06-controles
key KEYCODE_BACK 2
key KEYCODE_BACK 4
shot 07-retour

# Other screens, opened directly (debug extra understood by MainActivity)
for screen in search subscriptions library settings; do
  adb shell am start -W -n com.naotube.tv/.MainActivity --es naotube.screen "$screen"
  sleep 8
  shot "08-$screen"
done

adb shell am start -W -n com.naotube.tv/.MainActivity --es naotube.screen search --es naotube.query "musique relaxante"
sleep 20
shot 09-search-results

adb logcat -d > shots/logcat.txt
adb logcat -d -b crash > shots/crash.txt || true
grep -E "AndroidRuntime|FATAL EXCEPTION|NaoTube" shots/logcat.txt > shots/errors.txt || true
exit 0
