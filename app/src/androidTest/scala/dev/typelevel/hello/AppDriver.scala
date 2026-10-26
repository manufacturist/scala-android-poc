package dev.typelevel.hello

import android.app.Activity
import android.content.Intent
import android.view.View
import android.widget.TextView
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.{ActivityLifecycleCallback, ActivityLifecycleMonitorRegistry, Stage}
import androidx.test.uiautomator.{By, BySelector, UiDevice, UiObject2, Until}
import cats.effect.std.Dispatcher
import cats.effect.{Deferred, IO, Resource}
import dev.typelevel.hello.domain.State
import dev.typelevel.hello.layouts.{HomeLayout, ViewId}
import dev.typelevel.hello.platform.MainThread
import dev.typelevel.hello.screens.Route
import weaver.Expectations.Helpers.{failure, success}
import weaver.{Expectations, SourceLocation}

import scala.concurrent.duration.*

/** Drives the app for instrumented suites. Uses:
  *   1. UI Automator for input and view lookups
  *   1. androidx.test lifecycle monitor for activity changes
  */
object AppDriver:

  private val instrumentation = InstrumentationRegistry.getInstrumentation
  private val device          = UiDevice.getInstance(instrumentation)
  private val context         = instrumentation.getTargetContext
  private val monitor         = ActivityLifecycleMonitorRegistry.getInstance

  // The first start after an install can take well over 5[s] on a busy emulator
  private val launchMaxTimeout = 30.seconds
  private val timeout          = 5.seconds

  // Relaunches the activity within the same process, by finishing existing activities first
  def launch: IO[Unit] =
    val intent = Intent(context, classOf[MainActivity])
      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK)

    for
      scope   <- context.getApplicationContext.asInstanceOf[HelloApplication].scope
      _       <- scope.stateRef.set(State.initial)
      _       <- scope.routeRef.set(Route.Home)
      resumed <- resumesActivity(IO(context.startActivity(intent)), launchMaxTimeout)
      _       <- IO.raiseUnless(resumed)(AssertionError(s"MainActivity not resumed within $launchMaxTimeout"))
      _       <- find(selector(HomeLayout.id.click), launchMaxTimeout)
    yield ()

  def tap(id: ViewId[View]): IO[Unit] =
    find(selector(id), timeout).flatMap(view => IO.blocking(view.click()))

  def pressBack: IO[Unit] =
    IO.blocking(device.pressBack()).void

  def shows(id: ViewId[View])(using SourceLocation): IO[Expectations] =
    visible(selector(id))

  def showsText(id: ViewId[TextView], prefix: String)(using SourceLocation): IO[Expectations] =
    visible(selector(id).textStartsWith(prefix))

  def rotate(using SourceLocation): IO[Expectations] =
    val turn    = IO.blocking(device.setOrientationLeft())
    // setOrientation* also freezes rotation
    val restore = IO.blocking { device.setOrientationNatural(); device.unfreezeRotation() }

    resumesActivity(turn.guarantee(restore), timeout)
      .map(recreated => if recreated then success else failure(s"MainActivity not recreated within $timeout"))

  private def selector(id: ViewId[View]): BySelector =
    By.res(context.getResources.getResourceName(id.value))

  private def visible(selector: BySelector)(using SourceLocation): IO[Expectations] =
    IO.blocking(device.wait(Until.hasObject(selector), timeout.toMillis).booleanValue)
      .map(found => if found then success else failure(s"$selector not shown within $timeout"))

  private def find(selector: BySelector, within: FiniteDuration): IO[UiObject2] =
    IO.blocking(Option(device.wait(Until.findObject(selector), within.toMillis)))
      .flatMap(IO.fromOption(_)(AssertionError(s"$selector not found within $within")))

  // We register the callback before `action` runs to capture the Activity's RESUMED state
  private def resumesActivity(action: IO[Unit], within: FiniteDuration): IO[Boolean] =
    awaitingResume.use(awaitResume => action >> awaitResume.as(true).timeoutTo(within, IO.pure(false)))

  private def awaitingResume: Resource[IO, IO[Unit]] =
    for
      dispatcher <- Dispatcher.sequential[IO]
      signal     <- Resource.eval(Deferred[IO, Unit])

      callback = new ActivityLifecycleCallback {
        override def onActivityLifecycleChanged(activity: Activity, stage: Stage): Unit =
          if stage == Stage.RESUMED then dispatcher.unsafeRunAndForget(signal.complete(()))
      }

      addCallback    = MainThread(monitor.addLifecycleCallback(callback))
      removeCallback = MainThread(monitor.removeLifecycleCallback(callback))
      _             <- Resource.make(addCallback)(_ => removeCallback)
    yield signal.get
