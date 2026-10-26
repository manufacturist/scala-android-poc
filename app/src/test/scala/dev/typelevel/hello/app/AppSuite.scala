package dev.typelevel.hello.app

import cats.effect.testkit.TestControl
import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import dev.typelevel.hello.domain.State
import dev.typelevel.hello.screens.Route
import fs2.concurrent.SignallingRef
import weaver.SimpleIOSuite

import scala.concurrent.duration.*

object AppSuite extends SimpleIOSuite:

  test("ticker counts visible seconds only") {
    val program =
      for
        stateRef <- SignallingRef[IO].of(State.initial)
        hidden   <- SignallingRef[IO].of(false)

        seconds <- App.tickerStream(stateRef, hidden).compile.drain.background.surround {
          for
            _       <- IO.sleep(3500.millis)
            visible <- stateRef.get

            _      <- hidden.set(true)
            _      <- IO.sleep(3.seconds)
            paused <- stateRef.get
            _      <- hidden.set(false)

            _       <- IO.sleep(2500.millis)
            resumed <- stateRef.get
          yield List(visible, paused, resumed).map(_.seconds)
        }
      yield expect.same(List(3L, 3L, 5L), seconds)

    TestControl.executeEmbed(program)
  }

  test("router remounts on route change only and releases before acquiring") {
    val expectedEvents =
      Vector("mount Home", "unmount Home", "mount Settings", "unmount Settings", "mount Home", "unmount Home")

    val program =
      for
        routeRef <- SignallingRef[IO].of(Route.Home)
        log      <- Ref[IO].of(Vector.empty[String])

        screen = (route: Route) =>
          Resource.make(log.update(_ :+ s"mount $route"))(_ => log.update(_ :+ s"unmount $route"))

        _ <- App.routerStream(routeRef)(screen).compile.drain.background.surround {
          val routesOrder = List(Route.Settings, Route.Settings, Route.Home)
          routesOrder.traverse_(route => IO.sleep(10.millis) >> routeRef.set(route)) >> IO.sleep(10.millis)
        }

        events <- log.get
      yield expect.same(expectedEvents, events)

    TestControl.executeEmbed(program)
  }
