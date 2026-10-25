package dev.typelevel.hello.platform

import android.transition.TransitionManager
import android.view.{View, ViewGroup}
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import cats.effect.{IO, Resource}
import dev.typelevel.hello.layouts.{Layout, LayoutBinding}

object Views:

  def mount[A <: Layout](container: ViewGroup, binding: LayoutBinding[A]): Resource[IO, A] =
    val attach = MainThread {
      val layout = binding.inflate(container)
      TransitionManager.beginDelayedTransition(container)
      container.addView(layout.rootView)
      layout
    }

    val detach = (layout: A) => MainThread(container.removeView(layout.rootView))

    Resource.make(attach)(detach)

  def onClick(view: View)(handler: => Unit): Resource[IO, Unit] =
    val register   = MainThread(view.setOnClickListener(_ => handler))
    val unregister = MainThread(view.setOnClickListener(null))
    Resource.make(register)(_ => unregister)

  def onBack(activity: AppCompatActivity)(handler: => Unit): Resource[IO, Unit] =
    val onBackPressedCallback = new OnBackPressedCallback(true) {
      override def handleOnBackPressed(): Unit = handler
    }

    val register   = MainThread(activity.getOnBackPressedDispatcher.addCallback(onBackPressedCallback))
    val unregister = MainThread(onBackPressedCallback.remove())
    Resource.make(register)(_ => unregister)
