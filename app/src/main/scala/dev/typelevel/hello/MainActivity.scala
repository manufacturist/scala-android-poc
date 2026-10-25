package dev.typelevel.hello

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import dev.typelevel.hello.app.App
import dev.typelevel.hello.layouts.ActivityLayout
import dev.typelevel.hello.platform.AndroidRuntime.given
import dev.typelevel.hello.platform.Lifecycles

class MainActivity extends AppCompatActivity:

  override def onCreate(savedInstanceState: Bundle): Unit =
    super.onCreate(savedInstanceState)

    val layout = ActivityLayout.inflate(this.getLayoutInflater)
    this.setContentView(layout.rootView)

    val applicationScope = this.getApplication.asInstanceOf[HelloApplication].scope
    Lifecycles.whileCreated(this)(App.run(this, layout.container, applicationScope))
