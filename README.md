# Rattan

A small, quiet home screen for Android TV. It is built for one TV, a TCL 65P7K with Google TV 12.

- A still photo as the background, with glass panels for the clock, inputs and apps. One OpenGL
  shader draws both: the glass bends the photo at its rounded rims, frosts it lightly inside and
  catches the light along the edge. It redraws only when focus or layout changes.
- Every app fits in the dock (up to seven per row); focus always starts on the first app. A focused
  app grows once inside a white ring; nothing pulses.
- Live TV opens the FRITZ!Box's cable channel list (`http://192.168.178.1/dvb/m3u/tvhd.m3u`) in VLC.
- No network access of its own: the app has no `INTERNET` permission.

## Build

Requirements: JDK 17 and the Android SDK (platform 36, build-tools 36).

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleRelease
```

Release builds are signed with `signing/release.jks`, as configured in `signing/keystore.properties`.
Both files stay out of git; without them the release APK is unsigned.

`tools/make_wallpaper.py <photo>` turns a photo into the background (1920x1080, the TV's UI size),
its frosted copy, the banner and the icon.

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

The background photo is by Adrien Olichon on Unsplash
(https://unsplash.com/photos/LMKuqUWW0P0), used under the Unsplash License.

The settings glyph is the Material Icons "settings" icon, Apache License 2.0.
