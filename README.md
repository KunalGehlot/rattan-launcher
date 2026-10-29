# Rattan

A small, quiet home screen for Android TV. It is built for one TV, a TCL 65P7K with Google TV 12.

- A black frame around a dark-oak rattan weave, and glass panels for the clock, inputs and apps.
- Focus always starts on the first app. A focused app grows once; nothing pulses.
- No network access at all: the app has no `INTERNET` permission.

## Build

Requirements: JDK 17 and the Android SDK (platform 36, build-tools 36).

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleRelease
```

Release builds are signed with `signing/release.jks`, as configured in `signing/keystore.properties`.
Both files stay out of git; without them the release APK is unsigned.

`tools/make_wallpaper.py` draws the launcher's banner and icon (`python3 -I tools/make_wallpaper.py`).

## Install and make it the home screen

```sh
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package set-home-activity site.gehlot.rattan/.HomeActivity
```

Back to Projectivy:

```sh
adb shell cmd package set-home-activity com.spocky.projengmenu/.ui.home.MainActivity
```

## Credits

The settings glyph is the Material Icons "settings" icon, Apache License 2.0.
