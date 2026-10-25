# Background work

Not implemented in this repo and not tested. The code below is a starting point.

Fibers live inside the app process. When the app leaves the foreground Android freezes or kills the process and its
fibers stop. Work that must outlive the UI has to be handed to Android, which then starts the process to run it.

| Requirement                                                                                                                | Mechanism                              |
|----------------------------------------------------------------------------------------------------------------------------|----------------------------------------|
| Deferred work that survives process death and reboot. Optional constraints such as network. Periodic at 15 minutes or more | WorkManager                            |
| Long-running work that starts now and is visible to the user, for example a download or tracking                           | Foreground service with a notification |
| Exact wall-clock time                                                                                                      | AlarmManager                           |

Keep the work itself as a plain `IO` and call it from every entry point.

```scala
object Sync:
  def run(ctx: Context): IO[Unit] = ???
```

## WorkManager

Add `mvn"androidx.work:work-runtime:2.12.0"` to `mvnDeps`. WorkManager is a Kotlin library with a Java API. It pulls in
the Kotlin stdlib, kotlinx-coroutines and Room. `CoroutineWorker` is Kotlin-only, so extend `Worker` from Scala.

`Worker.doWork()` runs on a WorkManager background thread where blocking is expected.

```scala
import androidx.work.*

class SyncWorker(ctx: Context, params: WorkerParameters) extends Worker(ctx, params):
  @volatile private var cancel: () => Future[Unit] = () => Future.unit

  override def doWork(): ListenableWorker.Result =
    val (result, cancelFn) = Sync.run(getApplicationContext).unsafeToFutureCancelable()
    cancel = cancelFn
    Try(Await.result(result, Duration.Inf)) match
      case Success(_) => ListenableWorker.Result.success()
      case Failure(_) => ListenableWorker.Result.retry()

  // Android calls this when it stops the work. Cancelling the fiber runs its finalizers.
  override def onStopped(): Unit = cancel()
```

Schedule it.

```scala
val request = new PeriodicWorkRequest.Builder(classOf[SyncWorker], 15, TimeUnit.MINUTES)
  .setConstraints(new Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
  .build()

WorkManager.getInstance(context)
  .enqueueUniquePeriodicWork("sync", ExistingPeriodicWorkPolicy.KEEP, request)
```

For one-off work build `new OneTimeWorkRequest.Builder(classOf[SyncWorker]).build()` instead.

A single execution gets about 10 minutes. Periodic work runs at most every 15 minutes. `Result.retry()` reschedules with
backoff.

`adb shell cmd jobscheduler run -f dev.typelevel.hello <job-id>` runs a job without waiting for the schedule.
`adb shell dumpsys jobscheduler` lists job IDs.

## Foreground service

```scala
class SyncService extends Service:
  private var cancel: () => Future[Unit] = () => Future.unit

  override def onBind(intent: Intent): IBinder = null

  override def onStartCommand(intent: Intent, flags: Int, startId: Int): Int =
    // must run within about 5 seconds of startForegroundService
    startForeground(1, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
    cancel = (Sync.run(this) *> IO(stopSelf())).unsafeRunCancelable()
    Service.START_NOT_STICKY

  override def onDestroy(): Unit =
    cancel()
    super.onDestroy()

  private def notification(): Notification =
    val nm = getSystemService(classOf[NotificationManager])
    nm.createNotificationChannel(NotificationChannel("sync", "Sync", NotificationManager.IMPORTANCE_LOW))
    Notification.Builder(this, "sync")
      .setContentTitle("Syncing")
      .setSmallIcon(android.R.drawable.stat_notify_sync)
      .setOngoing(true)
      .build()
```

Add to `AndroidManifest.xml`.

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE"/>
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC"/>
<uses-permission android:name="android.permission.POST_NOTIFICATIONS"/>
<service android:name=".SyncService" android:foregroundServiceType="dataSync" android:exported="false"/>
```

Start it while the app is in the foreground.

```scala
startForegroundService(Intent(this, classOf[SyncService]))
```

| Android | Rule                                                                                                                                   |
|---------|----------------------------------------------------------------------------------------------------------------------------------------|
| 12+     | Starting a foreground service from the background is mostly blocked                                                                    |
| 13+     | `POST_NOTIFICATIONS` is a runtime permission. Request it from the Activity. Without it the service runs and its notification is hidden |
| 14+     | Every foreground service declares a type and the matching permission                                                                   |
| 15+     | `dataSync` services get about 6 hours per 24 hours. The system then calls `onTimeout`, where the service must stop                     |

## Cats Effect integration

Each Android entry point has a start callback and a stop callback. Start the `IO` in the first and cancel it in the
second so finalizers run.

| Entry point | Start                                         | Stop                     |
|-------------|-----------------------------------------------|--------------------------|
| Activity    | `onCreate`, through `Lifecycles.whileCreated` | ON_DESTROY               |
| Worker      | `doWork`                                      | `onStopped`              |
| Service     | `onStartCommand`                              | `onDestroy`, `onTimeout` |

WorkManager can start the process with no Activity. Shared resources such as an HTTP client or a database belong in
`ApplicationScope`, which `HelloApplication` creates once per process, not in `MainActivity`. All entry points share
`AndroidRuntime`.

## Process termination

| User action                          | Effect                                                          |
|--------------------------------------|-----------------------------------------------------------------|
| Swipe from recents                   | Process usually killed. WorkManager jobs persist                |
| Force stop in Settings               | Everything cancelled. Nothing runs until the user opens the app |
| OEM builds such as Xiaomi or Samsung | Background apps killed more aggressively than on stock Android  |

## Open risks for this repo

- WorkManager brings Kotlin dependencies through Mill and `ScalaLambdaFix`. Untested.
- R8 must keep `Worker` subclass constructors. WorkManager ships consumer rules for this. Untested with the manual R8
  steps.
