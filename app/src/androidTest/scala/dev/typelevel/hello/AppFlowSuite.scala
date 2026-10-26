package dev.typelevel.hello

import dev.typelevel.hello.AppDriver.*
import dev.typelevel.hello.layouts.{HomeLayout, SettingsLayout}
import org.junit.runner.RunWith
import weaver.{GlobalRead, SimpleIOSuite}

@RunWith(classOf[AndroidWeaverRunner])
class AppFlowSuite(global: GlobalRead) extends SimpleIOSuite:

  // These tests run on a single screen (the emulator). Gotta run them sequentially
  override def maxParallelism: Int = 1

  test("home starts at zero clicks") {
    for
      _     <- launch
      shown <- showsText(HomeLayout.id.status, "Clicked 0 times")
    yield shown
  }

  test("click increments the counter") {
    for
      _     <- launch
      _     <- tap(HomeLayout.id.click)
      _     <- tap(HomeLayout.id.click)
      shown <- showsText(HomeLayout.id.status, "Clicked 2 times")
    yield shown
  }

  test("settings button opens settings and back button returns home") {
    for
      _        <- launch
      _        <- tap(HomeLayout.id.settings)
      settings <- shows(SettingsLayout.id.reset)
      _        <- tap(SettingsLayout.id.back)
      home     <- shows(HomeLayout.id.click)
    yield settings and home
  }

  test("system back on settings returns home") {
    for
      _        <- launch
      _        <- tap(HomeLayout.id.settings)
      settings <- shows(SettingsLayout.id.reset)
      _        <- pressBack
      home     <- shows(HomeLayout.id.click)
    yield settings and home
  }

  test("reset on settings clears clicks") {
    for
      _       <- launch
      _       <- tap(HomeLayout.id.click)
      clicked <- showsText(HomeLayout.id.status, "Clicked 1 times")
      _       <- tap(HomeLayout.id.settings)
      _       <- tap(SettingsLayout.id.reset)
      _       <- tap(SettingsLayout.id.back)
      cleared <- showsText(HomeLayout.id.status, "Clicked 0 times")
    yield clicked and cleared
  }

  test("clicks survive rotation") {
    for
      _         <- launch
      _         <- tap(HomeLayout.id.click)
      clicked   <- showsText(HomeLayout.id.status, "Clicked 1 times")
      recreated <- rotate
      stateKept <- showsText(HomeLayout.id.status, "Clicked 1 times")
    yield clicked and recreated and stateKept
  }
