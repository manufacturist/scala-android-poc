package dev.typelevel.hello.platform

import android.os.{Handler, Looper}
import cats.effect.IO

import scala.concurrent.ExecutionContext

object MainThread:

  private val handler: Handler =
    Handler(Looper.getMainLooper)

  private val ec: ExecutionContext = ExecutionContext.fromExecutor(r => {
    handler.post(r); ()
  })

  def apply[A](thunk: => A): IO[A] =
    IO(thunk).evalOn(ec)
