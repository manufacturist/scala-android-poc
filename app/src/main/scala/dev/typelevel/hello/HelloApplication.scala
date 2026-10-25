package dev.typelevel.hello

import android.app.Application
import cats.effect.IO
import dev.typelevel.hello.app.ApplicationScope
import dev.typelevel.hello.platform.AndroidRuntime.given

class HelloApplication extends Application:

  val scope: IO[ApplicationScope] =
    ApplicationScope.create.memoize.unsafeRunSync()
