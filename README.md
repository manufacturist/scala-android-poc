# Hello Typelevel on Android

Android app in Scala 3.10 with Cats Effect and fs2. Built with Mill. Runs on ART like a Java or Kotlin app. No GraalVM
and no JavaFX.

The screen shows a click counter and an uptime ticker. Clicks go through a Cats Effect `Dispatcher` into an fs2
`SignallingRef`. The ticker is a `Stream.awakeEvery` fiber. A render fiber reads `state.discrete` and updates the views
on the main thread.

The project is experimental. It was tested on an API 35 emulator in October 2026. The debug build passes full ART
verification. The R8 release build is 2.5 MB with Material and runs. Both depend on the workarounds
in [docs/gotchas.md](docs/gotchas.md).

## Quick start

```sh
export ANDROID_HOME=$HOME/Library/Android/sdk
nohup $ANDROID_HOME/emulator/emulator -avd mill_avd_android-35_arm64-v8a_medium_phone \
  -port 5554 -no-metrics >/dev/null 2>&1 &
./mill app.run
```

First-time setup is in [docs/running-and-debugging.md](docs/running-and-debugging.md). Mill downloads its own JDK (Zulu
21).

## Stack

| Layer             | Choice                                                                       |
|-------------------|------------------------------------------------------------------------------|
| UI                | Material 3 Views (Material Components 1.14.0, AppCompat 1.8.0) built in code |
| Effects and state | Cats Effect 3.7.1, fs2 3.14.0                                                |
| Language          | Scala 3.10.0                                                                 |
| Build             | Mill 1.3.1 `AndroidAppScalaModule`, `ScalaLambdaFix` jar patch, d8           |
| Runtime           | ART. minSdk 33, target and compile SDK 35                                    |

## Code

Three scopes. The application scope holds state and route, so both survive Activity recreation. The Activity scope holds
views and screen switching. Visible-only work pauses between `onStop` and `onStart`.

```
HelloApplication                             one per process, never released
  ApplicationScope                           stateRef: SignallingRef[State], routeRef: SignallingRef[Route]
MainActivity.onCreate
  Lifecycles.whileCreated(App.run)           released at ON_DESTROY
    Dispatcher.parallel                      Android callbacks into IO
    Lifecycles.hidden                        true between onStop and onStart
    ticker fiber                             Stream.awakeEvery(1.second).pauseWhen(hidden)
    screen fiber                             routeRef.discrete.switchMap(screen)
      HomeScreen                             R.layout.home, click and settings listeners, render fiber
      SettingsScreen                         R.layout.settings, reset and back listeners, back gesture handler
```

| File                                                       | Role                                                                                                                                                                |
|------------------------------------------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `HelloApplication.scala`, `MainActivity.scala`             | Classes the manifest names. Process start and the UI entry point                                                                                                    |
| `app/App.scala`                                            | Activity scope. Ticker and screen switching on top of the application scope                                                                                         |
| `app/ApplicationScope.scala`                               | State and route refs that outlive any Activity. Never holds views                                                                                                   |
| `domain/State.scala`                                       | Pure state. `State`, `Message` and `step`. No Android or UI code                                                                                                    |
| `screens/Route.scala`, `screens/Env.scala`                 | The screens the app can show and the context passed to each                                                                                                         |
| `screens/HomeScreen.scala`, `screens/SettingsScreen.scala` | One `Resource` per screen. `HomeScreen` also renders `State` to text                                                                                                |
| `platform/MainThread.scala`                                | Main looper as an `ExecutionContext`. `MainThread(f)` is `IO(f).evalOn(MainThread.ec)`                                                                              |
| `platform/AndroidRuntime.scala`                            | The single `IORuntime`. Logs unhandled errors under the tag `IORuntime`                                                                                             |
| `platform/Lifecycles.scala`                                | `whileCreated` runs a `Resource` until ON_DESTROY. `hidden` is a signal that is true while stopped                                                                  |
| `platform/Views.scala`                                     | `mount` inflates a layout, `onClick` and `onBack` register listeners. All as `Resource`                                                                             |
| `res/layout/*.xml`                                         | `activity`, `home` and `settings` layouts                                                                                                                           |
| `layouts` (generated)                                      | `HomeLayout`, `SettingsLayout`, `ActivityLayout`. One final class per layout with a typed field per `@+id`. Written to `out/app/layoutBindings.dest` on every build |
| `build.mill`                                               | Versions, dependencies, patch hook                                                                                                                                  |
| `mill-build/src/ScalaLambdaFix.scala`                      | ASM rewrite of dependency jars so d8 emits lambdas ART accepts                                                                                                      |
| `mill-build/src/LayoutBindings.scala`                      | Generator for the `layouts` package. A renamed or removed id becomes a compile error                                                                                |

## Docs

| File                                                              | Content                                                            |
|-------------------------------------------------------------------|--------------------------------------------------------------------|
| [architecture.md](docs/architecture.md)                           | Entry points, scopes, threads, navigation, layouts, release        |
| [state-of-scala-on-android.md](docs/state-of-scala-on-android.md) | Available approaches and their status                              |
| [running-and-debugging.md](docs/running-and-debugging.md)         | Setup, build loop, logs, debugger, APK inspection, manual R8 build |
| [gotchas.md](docs/gotchas.md)                                     | Problems hit and their fixes                                       |
| [background-work.md](docs/background-work.md)                     | WorkManager and foreground services with Cats Effect               |

## Limitations

- State and route survive Activity recreation but not process death. Restoring them after that needs
  `onSaveInstanceState` or disk.
- R8 is not wired into `build.mill`. The running guide has manual steps.
- `ScalaLambdaFix` stays required until cats and Cats Effect publish builds made with Scala 3.3.7 or later.
- minSdk is 33 (Android 13) because Scala 3.8+ lazy vals need `VarHandle`.
