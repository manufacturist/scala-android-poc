package dev.typelevel.hello.screens

import cats.effect.{IO, Resource}
import dev.typelevel.hello.domain.{Mutation, State}
import dev.typelevel.hello.layouts.HomeLayout
import dev.typelevel.hello.platform.{MainThread, Views}

object HomeScreen:
  def apply(env: Env): Resource[IO, Unit] =
    for
      layout <- Views.mount(env.container, HomeLayout)

      _ <- Views.onClick(layout.click)(env.send(Mutation.Click))
      _ <- Views.onClick(layout.settings)(env.navigate(Route.Settings))

      stateUpdateStream = env.stateRef.discrete.foreach(state => MainThread(layout.status.setText(render(state))))
      _                <- stateUpdateStream.compile.drain.background
    yield ()

  private def render(state: State): String =
    s"Clicked ${state.clicks} times\nRunning for ${state.seconds}s"
