package dev.typelevel.hello.app

import cats.effect.IO
import dev.typelevel.hello.domain.State
import dev.typelevel.hello.screens.Route
import fs2.concurrent.SignallingRef

final case class ApplicationScope(
    stateRef: SignallingRef[IO, State],
    routeRef: SignallingRef[IO, Route]
)

object ApplicationScope:
  val create: IO[ApplicationScope] =
    for
      stateRef <- SignallingRef[IO].of(State.initial)
      routeRef <- SignallingRef[IO].of(Route.Home)
    yield ApplicationScope(stateRef, routeRef)
