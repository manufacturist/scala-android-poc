# Hello Typelevel on Android (experiment)

Android app in Scala 3.10 with Cats Effect and fs2. Built with Mill. Runs on ART like a Java or Kotlin app. No GraalVM
and no JavaFX.

It has two screens. Home shows a click counter and an uptime ticker. The settings screen allows the counter to be reset.
Clicks go through a Cats Effect `Dispatcher` into an fs2 `SignallingRef`. The ticker is a `Stream.awakeEvery` fiber that
runs while the app is visible. `HomeScreen` runs `stateUpdateStream`, which updates the visible text from
`stateRef.discrete` on the main thread.

Tested on an API 35 emulator in October 2026. The debug build passes full ART verification. The R8 release build is 2.5
MB and runs. Both need the workarounds in [docs/gotchas.md](docs/gotchas.md).

## Quick start

```sh
export ANDROID_HOME=$HOME/Library/Android/sdk
export PATH=$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH

nohup emulator -avd mill_avd_android-35_arm64-v8a_medium_phone -port 5554 -no-metrics >/dev/null 2>&1 &
adb wait-for-device shell 'while [ "$(getprop sys.boot_completed)" != 1 ]; do sleep 1; done'

./mill app.run           # build, install and launch
./mill app.test          # JVM suites, no emulator
./mill app.androidTest   # instrumented suite on the emulator
```

First-time setup is in [docs/running-and-debugging.md](docs/running-and-debugging.md). Mill downloads its own JDK (Zulu
21).

## Stack

| Layer             | Choice                                                                                   |
|-------------------|------------------------------------------------------------------------------------------|
| UI                | Material 3 Views with XML layouts. Material Components 1.14.0, AppCompat 1.8.0           |
| Effects and state | Cats Effect 3.7.1, fs2 3.14.0                                                            |
| Language          | Scala 3.10.0                                                                             |
| Build             | Mill 1.3.1 `AndroidAppScalaModule`, `ScalaLambdaFix` jar patch, d8                       |
| Runtime           | ART. minSdk 33, target and compile SDK 35                                                |
| Tests             | weaver 0.13.0, cats-effect-testkit 3.7.1, androidx.test runner 1.7.0, UI Automator 2.4.0 |
| IDE               | IntelliJ over BSP with library and `android.jar` sources                                 |

## Code

The application scope holds state and route and outlives Activity recreation. The Activity scope runs the ticker and
the router. The router mounts one screen `Resource` at a time. The ticker pauses between `onStop` and `onStart`.

```
HelloApplication                             
  ApplicationScope                           stateRef: SignallingRef[State], routeRef: SignallingRef[Route]
MainActivity.onCreate
  Lifecycles.whileCreated(App.run)           released at ON_DESTROY
    Dispatcher.parallel                      Android callbacks into IO
    Lifecycles.hidden                        true until onStart and after onStop
    tickerStream                             Mutation.Tick into stateRef every second while visible
    routerStream                             releases the old screen and mounts the new one on each route change
      HomeScreen                             HomeLayout, click and settings listeners, stateUpdateStream
      SettingsScreen                         SettingsLayout, reset and back listeners, back gesture handler
```

### Main sources

Paths are relative to `app/src/main/scala/dev/typelevel/hello`.

| File                                                       | Role                                                                                                                                                      |
|------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|
| `HelloApplication.scala`, `MainActivity.scala`             | Classes the manifest names. Process start and the UI entry point                                                                                          |
| `app/App.scala`                                            | Activity scope. `tickerStream` and `routerStream`                                                                                                         |
| `app/ApplicationScope.scala`                               | State and route refs that outlive any Activity. Holds no views                                                                                            |
| `domain/State.scala`                                       | `State`, `Mutation` and `update`. Pure, no Android code                                                                                                   |
| `screens/Route.scala`, `screens/ActivityScope.scala`       | Screens the app can show and the context each screen gets                                                                                                 |
| `screens/HomeScreen.scala`, `screens/SettingsScreen.scala` | One `Resource` per screen. `HomeScreen` also renders `State` to text                                                                                      |
| `platform/MainThread.scala`                                | `MainThread(f)` runs `f` on the main looper                                                                                                               |
| `platform/AndroidRuntime.scala`                            | The single `IORuntime`. Logs unhandled errors under the tag `IORuntime`                                                                                   |
| `platform/Lifecycles.scala`                                | `whileCreated` runs a `Resource` until ON_DESTROY. `hidden` is a signal that is true while the Activity is stopped                                        |
| `platform/Views.scala`                                     | `mount`, `onClick` and `onBack` as `Resource`s. Release removes the view or listener                                                                      |
| `layouts` (generated)                                      | `ActivityLayout`, `HomeLayout` and `SettingsLayout`. One final class per layout with a typed field per `@+id` and typed ids such as `HomeLayout.id.click` |

### Other files

| File                                  | Role                                                                                                                                            |
|---------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------|
| `build.mill`                          | Versions and dependencies. Hooks for the jar patch, layout bindings, test APK contents and IDE sources (were not showing correctly in IntelliJ) |
| `mill-build/src/ScalaLambdaFix.scala` | ASM rewrite of dependency jars so d8 emits lambdas that ART accepts                                                                             |
| `mill-build/src/LayoutBindings.scala` | Generates the `layouts` package into `out/app/layoutBindings.dest` on every build. A renamed or removed id yields a compile error :heart:       |
| `app/src/main/res/layout`             | `activity`, `home` and `settings` layouts                                                                                                       |
| `app/src/test/scala`                  | Weaver suites for `State`, `tickerStream` and `routerStream`                                                                                    |
| `app/src/androidTest/scala`           | `AppFlowSuite` runs in the app process on the emulator. `AppDriver` taps and checks views by the generated typed ids                            |

## Docs (LLM generated)

| File                                                              | Content                                                           |
|-------------------------------------------------------------------|-------------------------------------------------------------------|
| [architecture.md](docs/architecture.md)                           | Entry points, scopes, threads, navigation, layouts, release       |
| [state-of-scala-on-android.md](docs/state-of-scala-on-android.md) | Available approaches and their status                             |
| [running-and-debugging.md](docs/running-and-debugging.md)         | Setup, build loop, tests, logs, debugger, IDE, APK inspection, R8 |
| [gotchas.md](docs/gotchas.md)                                     | Problems hit and their fixes                                      |
| [background-work.md](docs/background-work.md)                     | WorkManager and foreground services with Cats Effect              |

## Limitations

- State and route survive Activity recreation but not process death. Restoring them needs `onSaveInstanceState` or
  disk
- R8 is not wired into `build.mill`. The running guide has manual steps
- Instrumented tests run against the debug APK. The R8 build and process death are untested.
- `ScalaLambdaFix` stays until cats and Cats Effect are rebuilt with a compiler that has
  [scala3#22632](https://github.com/scala/scala3/pull/22632)
- minSdk is 33 (Android 13) because Scala 3.8+ lazy vals need `VarHandle`
- Android XML resources get no IntelliJ support over BSP
