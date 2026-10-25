package dev.typelevel.hello.app

import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import cats.effect.std.Dispatcher
import cats.effect.{IO, Resource}
import dev.typelevel.hello.domain.*
import dev.typelevel.hello.platform.Lifecycles
import dev.typelevel.hello.screens.*
import fs2.Stream

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
      env         = Env(activity, container, dispatcher, scope.stateRef, scope.routeRef)

      ticksStream  = Stream.awakeEvery[IO](1.second).pauseWhen(hidden)
      tickerStream = ticksStream.foreach(_ => scope.stateRef.update(State.update(_, Mutation.Tick)))

      routerStream = scope.routeRef.discrete.changes.switchMap { route =>
        Stream.resource(screen(route, env)) >> Stream.never[IO]
      }

      _ <- tickerStream.compile.drain.background
      _ <- routerStream.compile.drain.background
    yield ()

  private def screen(route: Route, env: Env): Resource[IO, Unit] =
    route match
      case Route.Home     => HomeScreen(env)
      case Route.Settings => SettingsScreen(env)
