package dev.typelevel.hello.platform

import android.util.Log
import cats.effect.unsafe.IORuntime

object AndroidRuntime:
  given runtime: IORuntime =
    IORuntime.builder().setFailureReporter(e => Log.e("IORuntime", "unhandled error", e)).build()
