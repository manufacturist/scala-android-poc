package dev.typelevel.hello.domain

import weaver.SimpleIOSuite

object StateSuite extends SimpleIOSuite:

  pureTest("clicks and ticks count independently") {
    val state = List(Mutation.Click, Mutation.Tick, Mutation.Click).foldLeft(State.initial)(State.update)
    expect.same(State(clicks = 2, seconds = 1), state)
  }

  pureTest("reset clears clicks and keeps seconds") {
    val state = State.update(State(clicks = 3, seconds = 7), Mutation.Reset)
    expect.same(State(clicks = 0, seconds = 7), state)
  }
