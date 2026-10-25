# Running and debugging

Tested on macOS (Apple Silicon) with Mill 1.3.1 and an API 35 emulator in October 2026. Check [gotchas.md](gotchas.md)
when something fails.

- [Prerequisites](#prerequisites)
- [One-time setup](#one-time-setup)
- [Build loop](#build-loop)
- [Mill tasks](#mill-tasks)
- [Physical device](#physical-device)
- [Logs](#logs)
- [Debugger](#debugger)
- [Formatting](#formatting)
- [IDE](#ide)
- [APK inspection](#apk-inspection)
- [Scripted interaction](#scripted-interaction)
- [Release build (manual R8)](#release-build-manual-r8)

## Prerequisites

You need the Android SDK at `~/Library/Android/sdk`, for example from Android Studio. Mill installs missing components
on first use. No JDK setup is needed because `./mill` downloads Mill and Mill downloads Zulu 21.

Set the environment, preferably in the shell profile.

```sh
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH
```

The commands below assume both lines.

## One-time setup

1. Build once so Mill installs cmdline-tools.

   ```sh
   ./mill app.androidApk
   ```

2. Accept the SDK licenses. Without this Mill hangs on the next install.

   ```sh
   $ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager --licenses
   ```

3. Create the emulator.

   ```sh
   ./mill app.createAndroidVirtualDevice
   ```

   If it fails with `Exec failed, error: 2`, create it directly. This is the path used for this repo. The AVD name
   matches what Mill expects.

   ```sh
   $ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager --install \
     "system-images;android-35;google_apis_playstore;arm64-v8a" "emulator" < /dev/null
   echo no | $ANDROID_HOME/cmdline-tools/19.0/bin/avdmanager create avd \
     --name mill_avd_android-35_arm64-v8a_medium_phone \
     --package "system-images;android-35;google_apis_playstore;arm64-v8a" \
     --device medium_phone --force
   ```

   On Intel Macs replace `arm64-v8a` with `x86_64` in the package and the AVD name.

## Build loop

```sh
# start the emulator detached so it survives Mill daemon restarts
nohup emulator -avd mill_avd_android-35_arm64-v8a_medium_phone -port 5554 -no-metrics >/dev/null 2>&1 &
adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done'

./mill app.run                   # build, install and launch after each change

./mill app.stopAndroidEmulator   # same as adb emu kill
```

Cold boot takes 30 to 40 seconds. `./mill app.startAndroidEmulator` also works but ties the emulator to the Mill daemon.

## Mill tasks

| Task                             | Effect                                                   |
|----------------------------------|----------------------------------------------------------|
| `./mill app.compile`             | Compile Scala sources                                    |
| `./mill show app.androidApk`     | Build the debug APK to `out/app/androidApk.dest/app.apk` |
| `./mill app.androidInstall`      | Install on the running emulator or device                |
| `./mill app.androidRun`          | Launch `MainActivity`                                    |
| `./mill app.run`                 | Install and launch                                       |
| `./mill app.stopAndroidEmulator` | Kill the emulator on port 5554                           |
| `./mill shutdown`                | Restart the Mill daemon                                  |
| `./mill clean`                   | Delete `out/`                                            |

Default builds are debuggable and not shrunk. Mill signs them with `~/.mill-android/mill-debug.jks`.

## Physical device

1. Enable Developer options and USB debugging.
2. Connect the device and confirm it with `adb devices`.
3. Run `./mill app.androidInstall` and launch the app on the device.

`androidRun` and `stopAndroidEmulator` target the emulator on port 5554. With several devices attached use
`adb -s <serial>`.

## Logs

```sh
adb logcat --pid=$(adb shell pidof dev.typelevel.hello)   # this app only
adb logcat '*:E' | grep -v systemui                       # all errors
adb logcat -d | grep -A20 "FATAL EXCEPTION"               # last crash
adb logcat -c                                             # clear
```

`println` and `System.err` output appears under the tags `System.out` and `System.err`. Prefer `android.util.Log`.

Unhandled Cats Effect errors (failing finalizers, failing `Dispatcher.unsafeRunAndForget` effects) are logged by
`platform/AndroidRuntime` under the tag `IORuntime`.

```sh
adb logcat -s IORuntime
```

## Debugger

Debug builds accept JDWP connections through adb. Tested with `jdb` when `step` still lived in `Model`. The class is now
`dev.typelevel.hello.domain.State$`.

```sh
adb shell am set-debug-app -w dev.typelevel.hello              # next launch waits for a debugger
adb shell am start -n dev.typelevel.hello/.MainActivity
adb forward tcp:5005 jdwp:$(adb shell pidof dev.typelevel.hello)

jdb -attach localhost:5005
> stop in dev.typelevel.hello.domain.State$.step
> resume
# tap the button
Breakpoint hit: "thread=io-compute-0", dev.typelevel.hello.domain.State$.step(), line=11 bci=0
> where
> quit

adb shell am clear-debug-app
adb forward --remove tcp:5005
```

- With a debugger attached ART runs without compiled code. Startup took more than 8 seconds on the emulator and earlier
  taps were lost.
- Skip `set-debug-app` to attach to a running app.
- If `jdb` fails to attach after `set-debug-app -w`, the app stays on "Waiting For Debugger". Run
  `adb shell am clear-debug-app` and force-stop it.
- IntelliJ attaches with a Remote JVM Debug configuration on `localhost:5005`. Not tested.
- Fibers run on `io-compute-N` threads. View code runs on `main`.

## Formatting

scalafmt 3.11.5 with Scala 3 syntax. `.scalafmt.conf` aligns `<-` and `=` in for comprehensions (`align.preset = most`).
`align.arrowEnumeratorGenerator = false` keeps lambda bodies indented from the enclosing definition. Scalafmt also
aligns a multi-line generator's arrow with the lines above it. Put a blank line before it to start a new alignment
group.

```sh
./mill mill.scalalib.scalafmt/checkFormatAll   # report
./mill mill.scalalib.scalafmt/reformatAll      # rewrite
```

IntelliJ uses the same version when Settings, Editor, Code Style, Scala, Formatter is set to Scalafmt.

## IDE

IntelliJ with the Scala plugin imports Mill over BSP. IntelliJ regenerates `.bsp/mill-bsp.json` on every BSP refresh, so
manual edits to it are lost. Android XML resources get no IDE support through BSP.

Mill's BSP server needs `ANDROID_HOME`. IntelliJ started from the Dock or Spotlight does not read the shell profile.
Expose the variable to GUI apps and restart IntelliJ with Cmd+Q.

```sh
launchctl setenv ANDROID_HOME "$HOME/Library/Android/sdk"   # lasts until logout or reboot
```

Library sources and docs come from source jars that Mill reports over BSP. IntelliJ attaches a source jar only when it
sits in the same directory as the jar, named `<name>-sources.jar`. `android.jar` contains stubs only and its sources
ship separately in the SDK's `sources/android-35` folder. `androidSdkModule0.androidPlatformJars` copies `android.jar`
into `out/androidSdkModule0/androidPlatformJars.dest/` and packs the sources beside it as `android-sources.jar` (46 MB,
built once). The build uses that copy.

`R` reaches IntelliJ as compiled classes on the classpath and the `layouts` bindings as generated sources. After adding
a layout or an id, build once (`./mill app.compile`) and reload the BSP project.

Starting IntelliJ from a terminal with its `idea` launcher also passes the shell environment. Do not add an Android SDK
under Project Structure. The BSP sync resets the module SDK and the cats and fs2 jars come from Mill anyway.

## APK inspection

```sh
BT=$ANDROID_HOME/build-tools/35.0.0
APK=out/app/androidApk.dest/app.apk

unzip -lv $APK                                    # entries and compressed sizes
mkdir -p /tmp/dex && unzip -o -q $APK 'classes*.dex' -d /tmp/dex
for d in /tmp/dex/classes*.dex; do $BT/dexdump -d $d; done > /tmp/dex/dump.txt

# lambda bug check, must print 0
grep -cE 'check-cast v[0-9]+, [DJZIFSBC] //' /tmp/dex/dump.txt
```

Verify every class against ART without running each code path.

```sh
adb shell cmd package compile -m verify -f dev.typelevel.hello
adb logcat -d | grep -iE "dex2oat.*(Verification error|Rejecting)"   # empty output means all classes verify
```

Patched jars are written to `out/app/androidPackagedDeps.dest/`.

## Scripted interaction

```sh
adb shell input tap 540 1296                                      # "CLICK ME" on 1080x2400 portrait
adb exec-out screencap -p > screen.png
adb shell cmd uimode night yes; adb shell cmd uimode night no     # force Activity recreation
adb emu power capacity 5                                          # low battery
```

Taps sent right after launch can arrive before the first frame and get dropped.

## Release build (manual R8)

R8 is not wired into `build.mill`. These steps produce a 2.5 MB APK (2564 KB): 750 KB DEX (1.5 MB uncompressed), 1.5 MB
resources, 76 KB other. The resources are mostly unshrunk Material and AppCompat resources. The debug APK is 12.6 MB.
Before Material the release APK was 192 KB. The output is signed with the debug key and is only for measurement and
local testing.

```sh
BT=$ANDROID_HOME/build-tools/35.0.0
R8=$ANDROID_HOME/cmdline-tools/19.0/bin/r8
W=$PWD/out/r8 && rm -rf $W && mkdir -p $W/dex

./mill app.androidApk
./mill show app.androidPackagedDeps | grep -oE '/[^"]+\.jar' > $W/jars.txt
(cd out/app/compile.dest/classes && zip -qr $W/app-classes.jar .)

cp out/app/androidLinkedResources.dest/proguard/proguard-rules.pro $W/rules.pro   # aapt2 rules for manifest components
cat $(./mill show app.androidUnpackRunArchives | grep -oE '/[^"]+/proguard\.txt' | sort -u) >> $W/rules.pro   # AAR consumer rules
cat >> $W/rules.pro <<'EOF'
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-keepclassmembers class * { *** *$lzy*; *** *bitmap$*; }
EOF

$R8 --release --min-api 33 --lib $ANDROID_HOME/platforms/android-35/android.jar \
  --pg-conf $W/rules.pro --pg-map-output $W/mapping.txt --output $W/dex \
  $W/app-classes.jar $(cat $W/jars.txt)

cp out/app/androidApk.dest/app.apk $W/base.apk
zip -q -d $W/base.apk 'classes*.dex' 'META-INF/*'
(cd $W/dex && zip -q -9 $W/base.apk classes*.dex)
$BT/zipalign -p -f 4 $W/base.apk $W/aligned.apk
$BT/apksigner sign --ks ~/.mill-android/mill-debug.jks --ks-key-alias mill-android \
  --ks-pass pass:mill-android --key-pass pass:mill-android --out $W/release.apk $W/aligned.apk

ls -lh $W/release.apk
adb install -r $W/release.apk
```

R8 prints several hundred `Malformed inner-class attribute` info lines. Ignore them. [gotchas.md](gotchas.md#release-r8)
explains each rule.

Deobfuscate a crash from the shrunk build with the mapping file.

```sh
adb logcat -d | grep "E AndroidRuntime" | sed -E 's/^.*AndroidRuntime: //' > crash.txt
$ANDROID_HOME/cmdline-tools/19.0/bin/retrace $W/mapping.txt crash.txt
```

### Play Store release

A Play Store release needs three more pieces.

- R8 wired into Mill through `AndroidR8AppModule` and a `proguard-rules.pro` with the rules above.
- A private signing key.
- An app bundle (`.aab`) instead of an APK.
