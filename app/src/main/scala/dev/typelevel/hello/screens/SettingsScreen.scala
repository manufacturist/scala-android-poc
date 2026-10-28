package dev.typelevel.hello.screens

import cats.effect.{IO, Resource}
import dev.typelevel.hello.domain.Mutation
import dev.typelevel.hello.layouts.SettingsLayout
import dev.typelevel.hello.platform.Views

object SettingsScreen:
  def apply(activityScope: ActivityScope): Resource[IO, Unit] =
    for
      layout <- Views.mount(activityScope.container, SettingsLayout)

      _ <- Views.onClick(layout.reset)(activityScope.send(Mutation.Reset))
      _ <- Views.onClick(layout.back)(activityScope.navigate(Route.Home))
      _ <- Views.onBack(activityScope.activity)(activityScope.navigate(Route.Home))
    yield ()
