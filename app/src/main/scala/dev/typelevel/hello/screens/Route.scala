package dev.typelevel.hello.screens

import cats.Eq

enum Route:
  case Home, Settings

object Route:
  given Eq[Route] = Eq.fromUniversalEquals
