package dev.typelevel.hello.app

import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import cats.effect.std.Dispatcher
import cats.effect.{IO, Ref, Resource}
import dev.typelevel.hello.domain.*
import dev.typelevel.hello.platform.Lifecycles
import dev.typelevel.hello.screens.*
import fs2.Stream
import fs2.concurrent.Signal

import scala.concurrent.duration.*

object App:
  def run(
      activity: AppCompatActivity,
      container: ViewGroup,
      applicationScope: IO[ApplicationScope]
  ): Resource[IO, Unit] =
    for
      scope      <- Resource.eval(applicationScope)
      dispatcher <- Dispatcher.parallel[IO]
      hidden     <- Lifecycles.hidden(activity)

      activityScope = ActivityScope(
        activity = activity,
        container = container,
        dispatcher = dispatcher,
        stateRef = scope.stateRef,
        routeRef = scope.routeRef
      )

      _ <- tickerStream(scope.stateRef, hidden).compile.drain.background
      _ <- routerStream(scope.routeRef)(pickScreen(_, activityScope)).compile.drain.background
    yield ()

  def tickerStream(stateRef: Ref[IO, State], hiddenSignal: Signal[IO, Boolean]): Stream[IO, Nothing] =
    // The goal here was to count ticks only when the app is visible
    val ticks = hiddenSignal.discrete.changes.switchMap { isHidden =>
      if isHidden then Stream.empty else Stream.awakeEvery[IO](1.second)
    }

    ticks.foreach(_ => stateRef.update(State.update(_, Mutation.Tick)))

  def routerStream(routeSignal: Signal[IO, Route])(screen: Route => Resource[IO, Unit]): Stream[IO, Nothing] =
    routeSignal.discrete.changes.switchMap { route =>
      Stream.resource(screen(route)) >> Stream.never[IO]
    }

  private def pickScreen(route: Route, activityScope: ActivityScope): Resource[IO, Unit] =
    route match
      case Route.Home     => HomeScreen(activityScope)
      case Route.Settings => SettingsScreen(activityScope)
