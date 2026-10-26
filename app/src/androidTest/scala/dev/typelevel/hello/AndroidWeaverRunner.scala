package dev.typelevel.hello

import org.junit.runner.Description
import org.junit.runner.notification.{Failure, RunNotifier}
import weaver.junit.WeaverRunner

import java.util.concurrent.ConcurrentHashMap

// Custom runner that finishes every test right after its failure and drops weaver's own finish for it
final class AndroidWeaverRunner(suite: Class[?]) extends WeaverRunner(suite):
  override def run(notifier: RunNotifier): Unit =
    super.run(FinishingNotifier(notifier))

private final class FinishingNotifier(underlying: RunNotifier) extends RunNotifier:
  private val finishedTestsDescriptions = ConcurrentHashMap.newKeySet[Description]()

  override def fireTestSuiteStarted(description: Description): Unit =
    underlying.fireTestSuiteStarted(description)

  override def fireTestSuiteFinished(description: Description): Unit =
    underlying.fireTestSuiteFinished(description)

  override def fireTestStarted(description: Description): Unit =
    underlying.fireTestStarted(description)

  override def fireTestIgnored(description: Description): Unit =
    underlying.fireTestIgnored(description)

  // WeaverRunner 0.13.0 runner reports a test that throws with fireTestFailure, but no fireTestFinished :(
  override def fireTestFailure(failure: Failure): Unit =
    underlying.fireTestFailure(failure)
    fireTestFinished(failure.getDescription)

  override def fireTestFinished(description: Description): Unit =
    if finishedTestsDescriptions.add(description) then underlying.fireTestFinished(description)
