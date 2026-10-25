package dev.typelevel.hello.domain

final case class State(
    clicks: Int,
    seconds: Long
)

enum Mutation:
  case Click, Tick, Reset

object State:
  val initial: State = State(clicks = 0, seconds = 0)

  def update(model: State, mutation: Mutation): State = mutation match
    case Mutation.Click => model.copy(clicks = model.clicks + 1)
    case Mutation.Tick  => model.copy(seconds = model.seconds + 1)
    case Mutation.Reset => model.copy(clicks = 0)
