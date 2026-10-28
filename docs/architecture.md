# Architecture

How the app is put together and why. Code paths are relative to `app/src/main/scala/dev/typelevel/hello`.

## Entry points

Android has no `main`. The system starts the components declared in the manifest.

| Class              | Manifest                                | Role                                                                                                   |
|--------------------|-----------------------------------------|--------------------------------------------------------------------------------------------------------|
| `HelloApplication` | `<application android:name>`            | Created once per process before any component. Process initialization, not something the user launches |
| `MainActivity`     | `<activity>` with `MAIN` and `LAUNCHER` | The UI entry point the launcher icon starts                                                            |

Both are `Context`s and separate objects. `MainActivity` reaches the single `HelloApplication` through `getApplication`.
The cast to `HelloApplication` is safe only because the manifest names it.

## Scopes

| Scope       | Started by                                                    | Lifetime                 | Holds                                                         |
|-------------|---------------------------------------------------------------|--------------------------|---------------------------------------------------------------|
| Application | `HelloApplication`                                            | The process              | `ApplicationScope`: `stateRef`, `routeRef`                    |
| Activity    | `Lifecycles.whileCreated(App.run)` in `MainActivity.onCreate` | `onCreate` to ON_DESTROY | `Dispatcher`, `ActivityScope`, `tickerStream`, `routerStream` |
| Visible     | `Lifecycles.hidden`                                           | `onStart` to `onStop`    | The ticker. A fresh `awakeEvery` per visible period           |
| Screen      | `routerStream`                                                | Until the route changes  | Inflated layout, listeners, `stateUpdateStream`               |

`ApplicationScope.create.memoize.unsafeRunSync()` only creates the memo cell. The first run of `HelloApplication.scope`
allocates the refs and later runs reuse them. State and route therefore survive rotation, dark mode and other Activity
recreation. They do not survive process death.

The application scope never holds views. A View or Activity referenced from it would leak after recreation.

## Threads

| Thread         | Runs                                                                                                                                                                  |
|----------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `main`         | Lifecycle callbacks, input, view measure, layout and draw. Code reaches it through `MainThread(f)`, which is `IO(f).evalOn(MainThread.ec)` posting to the main looper |
| `io-compute-N` | All fibers. One worker per CPU core. A breakpoint in `State.update` stops on `io-compute-0`                                                                           |
| `RenderThread` | GPU rendering, owned by Android                                                                                                                                       |

Android callbacks enter `IO` through a `Dispatcher` (`ActivityScope.send`, `ActivityScope.navigate`,
`Lifecycles.hidden`). Nothing blocks the main thread. `unsafeRunSync` there deadlocks as soon as the effect needs
`MainThread`.

`platform/AndroidRuntime` is the single `IORuntime`. Its failure reporter logs unhandled errors under the tag
`IORuntime`.

## Navigation

```scala
routeSignal.discrete.changes.switchMap { route =>
  Stream.resource(screen(route)) >> Stream.never[IO]
}
```

```
routeRef.discrete.changes   Home ──────────────────── Settings ─────────────────── Home
inner (Home)                [mount, listeners, fibers][never]  interrupted, released
inner (Settings)                                               waits for release, [mount][never]  interrupted
```

- `routeRef.discrete` emits the current route, then every update. `.changes` drops repeats, so navigating to the current
  route does nothing.
- `Stream.resource(screen)` acquires the screen and would release it right after emitting. `>> Stream.never` keeps it
  acquired.
- `switchMap` interrupts the current inner stream when a new route arrives. fs2 guarantees its finalizers finish before
  the next inner stream starts.
- `ActivityScope.navigate(route)` sets `routeRef`. `SettingsScreen` registers a back handler with `Views.onBack` for as
  long as it is mounted. Without one, back finishes the Activity.

## Screens and layouts

- Each screen has an XML layout in `res/layout` and a `Resource` in `screens/`. `Views.mount` inflates the layout into
  the Activity's container with a fade and removes it on release.
- `mill-build/src/LayoutBindings.scala` generates one `final class` per layout in the `layouts` package, with a typed
  field per `@+id`. A renamed or removed id is a compile error. Ids missing from some layout variants become `Option`
  fields.
- Each binding companion also has an `id` object with one `ViewId[V]` per `@+id`, typed with the view class.
  `HomeLayout.id.click` is `R.id.click` scoped to the home layout. Code without an inflated layout, such as the
  instrumented tests, refers to views through it. The layout resource itself is `HomeLayout.layoutId`.
- `R.id` is one flat namespace per app. The generated bindings scope lookups to their layout's root view.
- `Views.onClick` and `Views.onBack` register in acquire and unregister in release.

## Release

`Lifecycles.whileCreated` cancels the Activity program at ON_DESTROY without waiting.

- Finalizers that use `MainThread` run after `onDestroy` returns because the main thread is busy until then. Waiting in
  `onDestroy` would deadlock.
- A failing finalizer does not fail the cancellation. The error goes to `AndroidRuntime`'s failure reporter.
- Work that must complete before the Activity is gone (persisting state, releasing a camera) cannot rely on an
  asynchronous finalizer.

## Packages

| Package    | Contents                                                 | Imports from                    |
|------------|----------------------------------------------------------|---------------------------------|
| root       | `HelloApplication`, `MainActivity`                       | `app`, `platform`, `layouts`    |
| `app`      | `App`, `ApplicationScope`                                | `domain`, `screens`, `platform` |
| `domain`   | `State`, `Mutation`, `update`                            | nothing                         |
| `screens`  | `Route`, `ActivityScope`, `HomeScreen`, `SettingsScreen` | `domain`, `platform`, `layouts` |
| `platform` | `AndroidRuntime`, `MainThread`, `Lifecycles`, `Views`    | `layouts`                       |
| `layouts`  | Generated bindings                                       | `R`                             |
