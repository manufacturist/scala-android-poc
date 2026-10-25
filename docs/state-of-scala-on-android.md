# State of Scala on Android (October 2026)

"Tested" means reproduced in this repo. "Reported" means taken from the linked source.

## Summary

- Scala 3 with Cats Effect and fs2 runs on Android through the standard path of scalac, d8, DEX and ART. Tested.
- Published cats and Cats Effect jars need a bytecode patch before d8. Release builds need custom R8 rules. Typelevel
  has no Android CI and no Android issues filed.
- GraalVM native image does not help on Android. ART already compiles DEX to native code. The GraalVM route (Gluon)
  replaces Android UI with JavaFX and its toolchain lags behind.

## Approaches

| Approach                                   | Status                                              | Typelevel fit                                                |
|--------------------------------------------|-----------------------------------------------------|--------------------------------------------------------------|
| JVM bytecode on ART (this repo)            | Works. Mill Scala support is marked experimental    | Good with the workarounds in [gotchas.md](gotchas.md)        |
| GraalVM native image with Gluon and JavaFX | Works. Toolchain capped at GraalVM 23 and JavaFX 21 | Poor. No Android Views or Jetpack. Needs reflection metadata |
| Scala.js with Capacitor                    | Works for Tyrian and Laminar                        | Calico fits. UI runs in a WebView                            |
| Scala Native                               | No Android app support                              | n/a                                                          |

## JVM bytecode on ART

### Build tools

| Tool                                             | Status                                                                                                                                                                                                               |
|--------------------------------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Mill `androidlib`                                | Provides `AndroidAppScalaModule`. Docs call Scala support experimental and native image support work in progress. Every pipeline step is an overridable task, which is how the jar patch hooks in. Tested with 1.3.1 |
| [scalroid](https://github.com/chenakam/scalroid) | Gradle plugin for mixed Scala, Kotlin and Java on the Android Gradle Plugin. Claims Scala 3. Not tested                                                                                                              |
| sbt-android, gradle-android-scala-plugin         | Scala 2.10 and 2.11 era. Unmaintained                                                                                                                                                                                |

### Scala versions

| Version                  | Android impact                                                                                                                                                                  |
|--------------------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| < 3.3.7, 3.4 to 3.6      | Lambda bytecode that ART rejects ([scala3#15736](https://github.com/scala/scala3/issues/15736)). Jars built with these versions crash with `VerifyError` unless patched. Tested |
| 3.3.7+ LTS               | Lambda fix backported ([scala3#22632](https://github.com/scala/scala3/pull/22632)). Lazy vals use `sun.misc.Unsafe`, which Android has. Works with minSdk 26. Tested on 3.3.9   |
| 3.7.0+                   | Lambda fix                                                                                                                                                                      |
| 3.8 (January 2026)       | Requires JDK 17. Lazy vals use `VarHandle`. Stdlib compiled by Scala 3 and published as `scala-library:3.x`. `scala3-library_3` becomes an empty shim                           |
| 3.9 LTS (September 2026) | Same layout as 3.8                                                                                                                                                              |
| 3.10                     | Used here. Needs minSdk 33 and a pinned shim. Tested                                                                                                                            |

### Libraries

The Scala version a jar was built with decides whether it needs the patch.

| Library              | Built with | On Android                                                 |
|----------------------|------------|------------------------------------------------------------|
| cats 2.13.0          | 3.3.4      | Needs the patch. Tested                                    |
| cats-effect 3.7.1    | 3.3.4      | Needs the patch. Tested. The main branch builds with 3.3.7 |
| fs2-core 3.14.0      | 3.3.8      | Works unpatched. Tested                                    |
| http4s Ember, fs2-io |            | Not tested. Networking uses `java.nio`, which Android has  |
| circe                |            | Not tested. Scala 3 derivation uses no runtime reflection  |

The Cats Effect details below come from its source and from running it.

- `IOFiberConstants.java` and `Signal.java` use `MethodHandle.invokeExact` and `invoke`. d8 requires minSdk 26 for
  these.
- JMX metrics registration is wrapped in `catch Throwable`. Android has no `java.lang.management`. R8 needs `-dontwarn`
  rules for it.
- `sun.misc.Signal` is looked up by reflection. The virtual thread check falls back when `Thread.isVirtual` is missing.

### UI

| Option                       | Notes                                                                                             |
|------------------------------|---------------------------------------------------------------------------------------------------|
| Android Views                | Imperative. Access through `MainThread`                                                           |
| Material Components on Views | Used here with AppCompat. Material 3 widgets and theme from Scala. Debug and R8 builds tested     |
| XML layouts with Views       | Supported by Mill through `res/layout`                                                            |
| Jetpack Compose              | Kotlin compiler plugin. Not usable from Scala sources. Use a Kotlin UI module fed by a Scala core |
| Room, Hilt                   | KSP or kapt code generation. Use `android.database.sqlite` and constructor wiring                 |

## GraalVM native image with Gluon

- [makingthematrix/scalaonandroid](https://github.com/makingthematrix/scalaonandroid) has Scala 2.13 and Scala 3
  examples built with Maven and `gluonfx-maven-plugin` (`mvn -Pandroid gluonfx:build gluonfx:package`).
- The [official Scala tutorial](https://docs.scala-lang.org/tutorials/scala-on-android.html) uses the same stack on
  Scala 2.13 and JavaFX 16.
- The [Aalto MSc thesis "Scala 3 on Android"](https://aaltodoc.aalto.fi/items/5520b3b4-7a63-4e89-89a9-29e7ef083d14)
  (Hyttinen, April 2026) built a working app on GraalVM, Gluon, JavaFX and Maven. Its main obstacles were JPMS module
  names of Scala jars, native image metadata and build resource usage.
- Gluon supports up to GraalVM 23 CE and JavaFX 21
  ([reported](https://ennerf.github.io/2026/09/18/JavaFX-27-Native-Image-on-a-Raspberry-Pi-5.html)).
- Gluon moved to [OpenJDK Mobile](https://gluonhq.com/news/bringing-openjdk-to-mobile-a-community-effort/) in September
  2025. HelloWorld runs on iOS. Android is listed as future work.
-
Oracle [removed Native Image from Java SE products](https://blogs.oracle.com/java/post/detaching-graalvm-from-the-java-ecosystem-train)
after GraalVM for JDK 24. AOT work moves to Project Leyden. GraalVM CE still ships Native Image.
- The app is a native binary rendering JavaFX inside an Android shell. Android APIs go through Gluon Attach.
  scalaonandroid lists notifications, camera, KeyStore, intents and lifecycle handling as not done.

Use it for a JavaFX app that also targets desktop. Not for Android-first apps.

## Scala.js with Capacitor

- Write-ups exist for [Tyrian](https://blog.joaocosta.eu/articles/8cd240f/writing-an-android-app-with-scalajs)
  and [Laminar](https://medium.com/geekculture/cross-platform-mobile-dev-with-scala-and-capacitor-54e69b62b50c). Both
  are a few years old.
- No Calico write-up found. Calico runs on Cats Effect and fs2 for Scala.js.
- UI is a web page in a WebView. Native APIs go through Capacitor plugins.

## Scala Native

- [scala-native#5122](https://github.com/scala-native/scala-native/pull/5122), opened 24 October 2026, adds an
  `aarch64-unknown-linux-android28` target. Tested only in Termux.
- No NDK cross-compilation or JNI shared library output for apps.

## Community

- `#scala-android` on the Scala Discord.
- [scala3#15736](https://github.com/scala/scala3/issues/15736) has first-hand Scala 3 Android reports.

## Sources

| Topic                      | Links                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                          |
|----------------------------|----------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| Mill                       | [Android Scala projects](https://mill-build.org/mill/android/scala.html), [Android build flow](https://mill-build.org/blog/15-android-build-flow.html)                                                                                                                                                                                                                                                                                                                                                                                                         |
| Scala                      | [3.8 notes](https://www.scala-lang.org/news/3.8/), [3.9 notes](https://www.scala-lang.org/news/3.9/), [scala3#15736](https://github.com/scala/scala3/issues/15736), [scala3#22632](https://github.com/scala/scala3/pull/22632), [scala3-lts#317](https://github.com/scala/scala3-lts/pull/317)                                                                                                                                                                                                                                                                 |
| Android                    | [Java 8+ support and MethodHandle minSdk](https://developer.android.com/studio/write/java8-support), [Compose compiler](https://developer.android.com/jetpack/androidx/releases/compose-compiler)                                                                                                                                                                                                                                                                                                                                                              |
| VarHandle on older Android | [Kryo #1454](https://github.com/EsotericSoftware/kryo/pull/1454), [Peergos #40](https://github.com/Peergos/android/pull/40)                                                                                                                                                                                                                                                                                                                                                                                                                                    |
| GraalVM and Gluon          | [scalaonandroid](https://github.com/makingthematrix/scalaonandroid), [Scala on Android tutorial](https://docs.scala-lang.org/tutorials/scala-on-android.html), [Aalto thesis](https://aaltodoc.aalto.fi/items/5520b3b4-7a63-4e89-89a9-29e7ef083d14), [Gluon status](https://ennerf.github.io/2026/09/18/JavaFX-27-Native-Image-on-a-Raspberry-Pi-5.html), [OpenJDK Mobile](https://gluonhq.com/news/bringing-openjdk-to-mobile-a-community-effort/), [Oracle announcement](https://blogs.oracle.com/java/post/detaching-graalvm-from-the-java-ecosystem-train) |
| Other routes               | [scala-native#5122](https://github.com/scala-native/scala-native/pull/5122), [scalroid](https://github.com/chenakam/scalroid)                                                                                                                                                                                                                                                                                                                                                                                                                                  |
