#!/bin/sh
# Builds the release APK, installs it on the TV, compiles it ahead of time, and sets up what an
# install loses or a new app lacks on this TCL TV:
#  - APP_AUTO_START: TCL's TclAppBoot refuses to start a background service without it;
#  - the remote-button accessibility service, which an install unbinds and the system leaves unbound;
#  - Rattan as the home app.
# Usage: tools/install.sh [ip:port]
set -eu
TV="${1:-192.168.178.59:5555}"
PKG=site.gehlot.rattan
SERVICE=$PKG/$PKG.RemoteKeys
export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home}"
cd "$(dirname "$0")/.."

./gradlew -q assembleRelease
adb -s "$TV" install -r app/build/outputs/apk/release/app-release.apk
adb -s "$TV" shell "
  cmd package compile -m speed -f $PKG >/dev/null
  appops set $PKG APP_AUTO_START allow
  settings put secure enabled_accessibility_services ''
  settings put secure enabled_accessibility_services $SERVICE
  settings put secure accessibility_enabled 1
  cmd package set-home-activity $PKG/.HomeActivity >/dev/null
  sleep 3
  dumpsys accessibility | grep -q 'Bound services:{Service\[label=Rattan' && echo 'remote buttons: bound' || echo 'remote buttons: NOT bound'
"
