package dev.typelevel.hello.platform

import androidx.lifecycle.{DefaultLifecycleObserver, LifecycleOwner}
import cats.effect.std.Dispatcher
import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Resource}
import fs2.concurrent.{Signal, SignallingRef}

object Lifecycles:

  def whileCreated(owner: LifecycleOwner)(resource: Resource[IO, Unit])(using IORuntime): Unit =
    val cancel = resource.useForever.unsafeRunCancelable()

    // TODO: Learn more about these lifecycles
    owner.getLifecycle.addObserver(new DefaultLifecycleObserver {
      override def onDestroy(owner: LifecycleOwner): Unit =
        cancel()
    })

  def hidden(owner: LifecycleOwner): Resource[IO, Signal[IO, Boolean]] =
    for
      dispatcher <- Dispatcher.sequential[IO]
      hiddenRef  <- Resource.eval(SignallingRef[IO].of(true))

      observer = new DefaultLifecycleObserver {
        override def onStart(owner: LifecycleOwner): Unit =
          dispatcher.unsafeRunAndForget(hiddenRef.set(false))

        override def onStop(owner: LifecycleOwner): Unit =
          dispatcher.unsafeRunAndForget(hiddenRef.set(true))
      }

      addObserver    = MainThread(owner.getLifecycle.addObserver(observer))
      removeObserver = MainThread(owner.getLifecycle.removeObserver(observer))
      _             <- Resource.make(addObserver)(_ => removeObserver)
    yield hiddenRef
