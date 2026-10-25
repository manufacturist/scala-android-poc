package dev.typelevel.hello.screens

import cats.effect.{IO, Resource}
import dev.typelevel.hello.domain.Mutation
import dev.typelevel.hello.layouts.SettingsLayout
import dev.typelevel.hello.platform.Views

object SettingsScreen:
  def apply(env: Env): Resource[IO, Unit] =
    for
      layout <- Views.mount(env.container, SettingsLayout)

      _ <- Views.onClick(layout.reset)(env.send(Mutation.Reset))
      _ <- Views.onClick(layout.back)(env.navigate(Route.Home))
      _ <- Views.onBack(env.activity)(env.navigate(Route.Home))
    yield ()
