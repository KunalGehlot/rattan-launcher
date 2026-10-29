# Rattan

A small, quiet home screen for Android TV. It is built for one TV, a TCL 65P7K with Google TV 12.

- A still photo as the background with smoked glass for the inputs, the settings button and the
  app dock. One OpenGL shader draws photo and glass: the rim bends the photo, the inside is darkened
  and lightly frosted, the edge catches the light, and a soft oval quiets the photo behind the
  clock. It redraws only when the layout changes, never on focus.
- Type: a large Instrument Serif clock with a letter-spaced gold date, and the focused item's name
  in italic serif above the dock.
- Focus is one glass lens that springs from item to item, reshaping as it goes: a ring with a
  gold glow around app banners, a clear pane over controls. It glides within the dock or the
  inputs and reappears when moving between them. Nothing pulses.
- Every app fits in the dock (balanced rows of at most seven); focus always starts on the first app.
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

Back to the Google TV home screen (disabled on this TV, not removed):

```sh
adb shell pm enable com.google.android.apps.tv.launcherx
adb shell pm enable com.google.android.tungsten.setupwraith
adb shell cmd package set-home-activity com.google.android.apps.tv.launcherx/.home.HomeActivity
```

## Credits

The background photo is by Adrien Olichon on Unsplash
(https://unsplash.com/photos/LMKuqUWW0P0), used under the Unsplash License.

Fonts: Instrument Serif (The Instrument Serif Project Authors) and Inter (The Inter Project
Authors), both SIL Open Font License 1.1; the licence texts are in `licenses/`.

The settings glyph is the Material Icons "settings" icon, Apache License 2.0.
