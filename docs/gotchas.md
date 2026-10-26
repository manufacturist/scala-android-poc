# Gotchas

Problems hit while building this app. Each entry shows the failure, explains it and gives the fix. Tested in this repo
in October 2026 unless marked "reported".

- [Build (d8)](#build-d8)
- [Runtime (ART)](#runtime-art)
- [Release (R8)](#release-r8)
- [Toolchain](#toolchain)
- [Versions](#versions)

## Build (d8)

### VerifyError from lambdas in cats and Cats Effect

d8 prints `Invalid stack map table` warnings and the app crashes at launch.

```
java.lang.VerifyError: Verifier rejected class cats.effect.std.Dispatcher$$$ExternalSyntheticLambda5 ...
  check-cast on unexpected class Boolean
    at cats.effect.std.Dispatcher$.parallel(Dispatcher.scala:166)
```

Scala 3 compilers older than 3.3.7, and 3.4 to 3.6, emit `LambdaMetafactory` calls whose instantiated method type
returns a primitive where the interface method returns an object
([scala3#15736](https://github.com/scala/scala3/issues/15736)). The JVM accepts this. d8 turns it into a `check-cast` to
a primitive and ART rejects the class. cats 2.13.0 and Cats Effect 3.7.1 were built with Scala 3.3.4. The unpatched APK
had 256 affected lambda classes. The project Scala version is irrelevant because the bytecode comes from the published
jars.

`mill-build/src/ScalaLambdaFix.scala` rewrites dependency jars before d8 runs. It applies the compiler fix
([scala3#22632](https://github.com/scala/scala3/pull/22632)) and boxes the primitive return type in the instantiated
method type and in `altMetafactory` bridge types. `build.mill` hooks it into `androidPackagedDeps`. Jars without
affected lambdas pass through unchanged.

To check whether the patch is still needed, disable the override and rebuild. Zero `Invalid stack map table` warnings
means it can go.

### Type ... is defined multiple times

With Scala 3.8 and later d8 fails on duplicate classes.

```
Type scala.$times$colon$ is defined multiple times:
  .../scala-library-3.10.0.jar:scala/$times$colon$.class,
  .../scala3-library_3-3.3.8.jar:scala/$times$colon$.class
```

From 3.8 the standard library ships as `scala-library:3.x` and `scala3-library_3` is an empty shim. fs2 depends on the
old non-empty `scala3-library_3:3.3.8`. The JVM loads the first class on the classpath. d8 rejects duplicates.

Pin the shim in `mvnDeps`.

```scala
mvn"org.scala-lang:scala3-library_3:${scalaVersion()}"
```

### minSdk

| minSdk | Reason                                                                                                                                                                                                                                                                                                                   |
|--------|--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| 26     | Cats Effect calls `MethodHandle.invokeExact`. d8 rejects it below API 26                                                                                                                                                                                                                                                 |
| 33     | Scala 3.8+ lazy vals use `VarHandle`. This APK has 366 call sites, mostly in the stdlib. Android has `VarHandle` from API 33. d8 desugaring for older APIs is partial ([Kryo #1454](https://github.com/EsotericSoftware/kryo/pull/1454), [Peergos #40](https://github.com/Peergos/android/pull/40)). Not tested below 33 |

For Android 8 to 12 use Scala 3.3.7+ LTS with minSdk 26. Tested on 3.3.9.

## Runtime (ART)

### No IOApp

Android owns `main` and the process lifecycle. The app has one `IORuntime`, `platform/AndroidRuntime`. Each screen is a
`Resource[IO, Unit]`. `MainActivity` runs `App.run` through `Lifecycles.whileCreated`, which starts it in `onCreate` and
cancels it at ON_DESTROY. See [architecture.md](architecture.md).

### Views on the main thread only

View access from a Cats Effect thread throws `CalledFromWrongThreadException` or corrupts state. Wrap it in
`MainThread(...)`, which is `IO(...).evalOn(MainThread.ec)`.

### Blocking the main thread

`unsafeRunSync()` on the main thread deadlocks when the effect uses `MainThread(...)`. Slow work on the main thread
causes an ANR. Use `unsafeRunCancelable()` or `unsafeRunAndForget()`.

### State resets on Activity recreation

The counter and timer restart at 0 after a configuration change such as rotation or dark mode. The process ID stays the
same and nothing crashes.

Android destroys and recreates the Activity. State that lives in the Activity's `Resource` goes with it.

Fixed. `stateRef` and `routeRef` live in `ApplicationScope`, which `HelloApplication` creates once per process. Each
Activity reads them and binds only its views. State is still lost on process death. Restoring it after that needs
`onSaveInstanceState` or disk.

### Release finishes after onDestroy

`Lifecycles.whileCreated` cancels the program in `onDestroy` and returns without waiting. Finalizers that touch views
run on the main thread after `onDestroy` returns, because they are queued behind it. Waiting in `onDestroy` would
deadlock for the same reason. This is fine for cleanup of the dead Activity's own objects. Resources other code competes
for, such as camera or microphone, belong between `onStart` and `onStop`. State that must persist has to be written
before, not in a finalizer.

### Finalizer errors do not fail the release

A finalizer that throws during cancellation does not fail the `Future` returned by `unsafeRunCancelable`. It completes
with `Success(())`. Cats Effect passes the error to the runtime's failure reporter instead. Errors from
`Dispatcher.unsafeRunAndForget` go there too. `AndroidRuntime` logs both under the tag `IORuntime` at error level.
Tested on the JVM.

### Stream.resource releases as soon as it emits

`Stream.resource(r)` acquires `r`, emits once and completes, which releases `r` immediately. A screen mounted this way
disappears at once. `App` appends `>> Stream.never[IO]` so the screen stays until `switchMap` interrupts it.

### Setting the same route remounts the screen

`SignallingRef.discrete` emits on every update, including updates to the same value. Without `.changes`,
`navigate(Route.Home)` while on Home remounts Home and restarts its fibers. `App` uses `routeRef.discrete.changes` with
`given Eq[Route]` in the `Route` companion.

### pauseWhen over awakeEvery over-counts

`Stream.awakeEvery(1.second).pauseWhen(hidden)` added two seconds per hide and show cycle. `pauseWhen` checks the
signal before pulling, so the tick already in flight passes after `hidden` turns true. On resume `awakeEvery` emits
one catch-up tick for the missed time. Found by `AppSuite`.

`tickerStream` starts a fresh timer per visible period instead.

```scala
hidden.discrete.changes.switchMap(isHidden => if isHidden then Stream.empty else Stream.awakeEvery[IO](1.second))
```

### Background fiber errors are silent

`.compile.drain.background` discards the fiber `Outcome`. A failing ticker or render stream logs nothing and the UI
stops updating. Add error handling.

```scala
.handleErrorWith(e => IO(android.util.Log.e("hello", "render failed", e)))
```

### Missing JDK APIs fail at call time

ART does not implement the full JDK. Missing APIs include JMX (tested), `sun.misc.Signal`, virtual threads,
`ProcessHandle` and `java.net.http`. Code referencing them builds and loads and fails when the call runs. Cats Effect
guards its own uses. Test other libraries on a device.

### Edge-to-edge

With targetSdk 35 Android 15 draws content under the system bars. `res/layout/activity.xml` sets
`android:fitsSystemWindows="true"` on the container.

### Kotlin-only Android libraries

Jetpack Compose is a Kotlin compiler plugin. Room and Hilt generate code with KSP or kapt. None work from Scala sources.
Use one of these instead.

- Views or XML layouts.
- A Kotlin UI module driven by the Scala core.
- `android.database.sqlite` wrapped in `IO.blocking`.

## Release (R8)

R8 is not wired into `build.mill`. Manual steps are
in [running-and-debugging.md](running-and-debugging.md#release-build-manual-r8). R8 needs these rules.

```
-dontwarn java.lang.management.**
-dontwarn javax.management.**
-keepclassmembers class * { *** *$lzy*; *** *bitmap$*; }
```

### Missing class java.lang.management.ManagementFactory

R8 fails on Cats Effect JMX code in `IORuntimeCompanionPlatform`. At runtime that code sits in `catch Throwable`. The
two `-dontwarn` rules fix it.

### NoSuchFieldException on lazy val fields

The shrunk APK crashes at launch.

```
java.lang.ExceptionInInitializerError
Caused by: java.lang.NoSuchFieldException: No field _tailUpdater$lzy1 in class Lv/I;
    at java.lang.invoke.MethodHandles$Lookup.findVarHandle(...)
```

Scala 3.8+ lazy vals look up their storage field by name with `findVarHandle(cls, "x$lzy1", ...)`. R8 renames the field.
With `-keepclassmembernames` R8 still removes fields that are never read directly, for example `opportunistic$lzy1` in
`scala.concurrent.ExecutionContext`.

`-keepclassmembers class * { *** *$lzy*; *** *bitmap$*; }` keeps the fields. The `bitmap$` pattern targets lazy vals in
jars built with Scala 3.3. It is a precaution and not proven necessary.

### ClassNotFoundException for an androidx.startup initializer

With AppCompat the shrunk APK crashed at launch.

```
java.lang.RuntimeException: Unable to get provider androidx.startup.InitializationProvider:
  java.lang.ClassNotFoundException: androidx.emoji2.text.EmojiCompatInitializer
```

AppCompat pulls in emoji2, which registers its initializer in the merged manifest. The keep rule for initializers ships
in the `startup-runtime` AAR. `./mill show app.androidKnownProguardRulesFile` misses that AAR because it covers 33
compile-time archives only. `androidUnpackRunArchives` includes runtime dependencies and yields 18 rule files,
`startup-runtime` among them. The manual R8 steps collect rules from it.

### R8 can mask the lambda bug

scala3#15736 reports that enabling minification avoided the `VerifyError`. The outcome depends on which lambdas R8
rewrites. Keep `ScalaLambdaFix` for debug and release builds.

### d8 warns about Kotlin metadata

With AndroidX dependencies d8 from build-tools 35.0.0 prints about 900 warnings starting with
`An error occurred when parsing kotlin metadata`. The libraries were compiled with a newer Kotlin than this d8 knows. d8
only converts bytecode and does not need the metadata. The app passes full ART verification and runs. R8 from
cmdline-tools 19.0 prints no such warnings.

### Malformed inner-class attribute info lines

R8 prints several hundred of these for Scala nested objects. They are harmless. Only `Error` and `Warning` lines matter.

## Instrumented tests

### Test APK classes shadow the app

Android runs the test APK and the app APK in one process with one class loader, test APK first
(`LoadedApk.makePaths`). By default Mill packs every runtime dependency, the app's classes and an `R.jar` into the
test APK. The unpatched cats jars then load instead of the app's patched copies, d8 prints the stack map warnings
again, and R ids come from a separate resource link.

`app.androidTest` overrides `androidPackagedDeps` and `androidPackagedClassfiles` to keep the suites and test-only
jars. A jar the app packs at the same artifact and version is dropped. Newer versions stay.

One version differs. The app resolves kotlinx-coroutines 1.6.4, the highest version its libraries ask for
(`lifecycle-common` 2.6.2 through AppCompat and Material). UI Automator 2.4.0 pulls `androidx.test:core` 1.7.0,
which asks for 1.8.1, so the test APK carries 1.8.1 and it shadows the app's copy. This is harmless here. Only
`lifecycle-common`, `lifecycle-viewmodel-savedstate`, `activity` and `core` reference coroutines, for Kotlin APIs
such as `lifecycleScope` that the app never calls. R8 keeps no coroutines class in the release build.

### Weaver passes tests that throw

`WeaverRunner` reports a test that ends in an exception with `fireTestFailure` and no `fireTestFinished`. Assertion
failures get both. `AndroidJUnitRunner` sends a result to `am instrument` only on finish, so Mill reported
`SUCCESS`, the run counted 5 of 6 tests, and the failure appeared only in logcat. Present in weaver 0.13.0 and on
main. `AndroidWeaverRunner` wraps the notifier and finishes each test right after its failure.

### Weaver suites must be classes

`WeaverRunner` loads the class that carries `@RunWith`. For a Scala `object` that is the mirror class, which holds
no instance, and the run fails with `Could not find class`. A class without the expected constructor fails with
`Could not find a suitable constructor that takes GlobalResourceF.Read`. The working shape is
`class AppFlowSuite(global: GlobalRead) extends SimpleIOSuite` with `@RunWith(classOf[AndroidWeaverRunner])`.

### TestModule.Weaver replaces the instrumentation runner

Mill writes `testFramework` into the test manifest as the instrumentation class. `TestModule.Weaver` sets it to
`weaver.framework.CatsEffect` instead of `AndroidJUnitRunner`. Add weaver as a plain dependency.

### Failure messages are only in logcat

Mill's report says `No Exception or message provided`. Read `adb logcat -s TestRunner`.

### d8 warns about Kotlin metadata

UI Automator 2.4.0 is built with a newer Kotlin than build-tools 35 d8 knows. The warnings do not affect the dex.

## Toolchain

### Android SDK location not found

Mill reads `ANDROID_HOME` or `ANDROID_SDK_ROOT` on every Android task. Export it in the shell profile.

```sh
export ANDROID_HOME=$HOME/Library/Android/sdk
```

### IntelliJ marks every import red

IntelliJ resolves neither `android.*` nor cats and fs2 imports. Its `app` module contains only the Scala SDK.
`out/mill-bsp/err.log` repeats `androidSdkModule0.sdkPath task failed`.

IntelliJ launched from the Dock or Spotlight does not inherit shell variables. It starts the Mill BSP server without
`ANDROID_HOME`, so every classpath and compile request fails. Editing `.bsp/mill-bsp.json` does not help because
IntelliJ rewrites it on refresh. Adding an Android SDK in Project Structure does not help either because the module SDK
is reset on sync and the library jars come from Mill.

Set the variable for GUI apps, quit IntelliJ with Cmd+Q, reopen it and refresh the BSP project.

```sh
launchctl setenv ANDROID_HOME "$HOME/Library/Android/sdk"
```

The setting lasts until logout or reboot.

### IntelliJ flags every android: attribute in layouts

Layout XML shows `Attribute android:layout_width is not allowed here` on every attribute and the design editor asks for
a Gradle sync. IntelliJ validates layouts against an Android facet, which only a Gradle import creates. Over BSP the
module has none. The warnings are false positives. aapt2 validates the XML on every build and fails with file and line
on a real error.

### IntelliJ shows decompiled TASTy instead of library sources

Navigating into cats, fs2 or the Scala standard library opens `.tasty` stubs without Scaladoc. Mill's `AndroidModule`
overrides `bspMvnDependencySources` to report sources of AAR dependencies only, so source jars of plain jar dependencies
never reach the IDE. `build.mill` restores them by appending the resolved source jars of
`mandatoryMvnDeps() ++ mvnDeps()`. Reload the BSP project in IntelliJ after changing it.

### IntelliJ BSP sync fails after ./mill clean

`./mill clean` deletes `out/` under the running BSP server. Every request then fails with
`Can not respond to workspaceBuildTargets request before receiving the initialize request`, and IntelliJ reports
cleaning as unsupported. IntelliJ only initializes a new server. Restart BSP or kill the server
(`pgrep -f "mill.*--bsp"`) and reload the project.

### IntelliJ shows Stub! in Android framework classes

`android.jar` contains stubs only. IntelliJ attaches a source jar only when it sits in the same directory as the jar
under the name `<name>-sources.jar`. `androidSdkModule0.androidPlatformJars` copies `android.jar` next to an
`android-sources.jar` packed from the SDK's `sources/android-35`, and the build uses that copy.

### Mill hangs while installing an SDK package

The task prints nothing and generates no network traffic. sdkmanager waits on stdin for license acceptance. Mill does
not show the prompt and auto-accepts only on CI. Accept the licenses once.

```sh
$ANDROID_HOME/cmdline-tools/19.0/bin/sdkmanager --licenses
```

### sdkmanager fails with exec error 2

After a failed or killed SDK install every task that calls sdkmanager fails immediately with
`Cannot run program .../sdkmanager ... Exec failed, error: 2`. Mill 1.3.1 runs sdkmanager on a long-lived worker thread
whose working directory was deleted. Run `./mill shutdown`, or call sdkmanager and avdmanager directly as
in [running-and-debugging.md](running-and-debugging.md#one-time-setup).

### Emulator dies with the Mill daemon

`./mill app.startAndroidEmulator` starts the emulator with os-lib `spawn`, which kills child processes when the parent
exits. `./mill shutdown` or a daemon restart stops the emulator. adb then hangs at `- waiting for device -`. Start the
emulator detached instead.

```sh
nohup $ANDROID_HOME/emulator/emulator -avd mill_avd_android-35_arm64-v8a_medium_phone \
  -port 5554 -no-metrics >/dev/null 2>&1 &
```

### androidInstall fails with device not found

`adb: device 'emulator-5554' not found` means no emulator is running, for example after its window was closed. Start it
again as in [running-and-debugging.md](running-and-debugging.md#build-loop).

### Edge swipe is sometimes Home instead of Back

In the emulator the same edge swipe was once handled as back (the Activity finished) and once as Home (the task moved to
the background). For reproducible tests use `adb shell input keyevent KEYCODE_BACK`.

### First launch after install is slow

The first cold start after an install took 9 to 28 seconds while later ones took about 1.3 seconds. The app runs from
the APK without AOT compilation (`run-from-apk`) until background dexopt runs. On an emulator that has been up for an
hour memory pressure adds to it (2.25 of 2.5 GB used, 495 MB swapped). A fresh emulator showed release cold starts of
1.0 to 1.2 seconds.

### System UI keeps stopping on low battery

This is an emulator bug in the Google Play system image, not an app crash. logcat shows the System UI process failing.

```
FATAL EXCEPTION: SystemUIBg-4
Process: com.android.systemui
java.lang.IllegalArgumentException: Unknown authority com.google.android.flipendo.api
    at com.google.android.systemui.power.LowPowerWarningsController.onBatteryEventUpdate
```

The app keeps its process. Check with `adb shell pidof dev.typelevel.hello`.

### Disk usage

On first use Mill installs these SDK packages.

- cmdline-tools 19.0
- build-tools 35.0.0
- platform android-35

The API 35 Play system image takes 2.2 GB. `out/` takes about 140 MB.

## Versions

| Component           | Version              | Notes                                                        |
|---------------------|----------------------|--------------------------------------------------------------|
| Scala               | 3.10.0               | Not LTS. 3.9 is the LTS. 3.3.x LTS works with minSdk 26      |
| Cats Effect         | 3.7.1                | Built with 3.3.4, patched                                    |
| fs2                 | 3.14.0               | Built with 3.3.8, unpatched                                  |
| cats                | 2.13.0               | Transitive. Built with 3.3.4, patched                        |
| AppCompat           | 1.8.0                | AAR. Requires compileSdk 34 or newer                         |
| Material Components | 1.14.0               | AAR. Material 3 theme `Theme.Material3.DayNight.NoActionBar` |
| Mill                | 1.3.1                | Scala Android support marked experimental                    |
| weaver              | 0.13.0               | Built with 3.3.7, unpatched. JVM and instrumented tests      |
| androidx.test       | runner 1.7.0         | `AndroidJUnitRunner`, `ActivityLifecycleMonitorRegistry`     |
| UI Automator        | 2.4.0                | Instrumented tests. Kotlin, pulls kotlinx-coroutines 1.8.1   |
| Android             | minSdk 33, target 35 | build-tools 35.0.0                                           |
