package dev.typelevel.hello.screens

import android.view.ViewGroup
import androidx.appcompat.app.AppCompatActivity
import cats.effect.IO
import cats.effect.std.Dispatcher
import dev.typelevel.hello.domain.{Mutation, State}
import fs2.concurrent.SignallingRef

final case class Env(
    activity: AppCompatActivity,
    container: ViewGroup,
    dispatcher: Dispatcher[IO],
    stateRef: SignallingRef[IO, State],
    routeRef: SignallingRef[IO, Route]
):
  def send(mutation: Mutation): Unit =
    dispatcher.unsafeRunAndForget(stateRef.update(State.update(_, mutation)))

  def navigate(route: Route): Unit =
    dispatcher.unsafeRunAndForget(routeRef.set(route))
