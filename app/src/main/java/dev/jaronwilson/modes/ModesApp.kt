package dev.jaronwilson.modes

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import dev.jaronwilson.modes.apply.ModeApplier
import dev.jaronwilson.modes.core.repo.ModeRepository
import dev.jaronwilson.modes.schedule.ModeScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class ModesApp : Application() {
    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
        createChannels()
        AppGraph.scope.launch {
            AppGraph.repo.seedIfEmpty()
            AppGraph.scheduler.reevaluate("app start")
            AppGraph.scheduler.scheduleNextBoundary()
            AppGraph.scheduler.ensurePeriodicSync()
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CH_DIGEST,
                "Digest",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Batched notifications, released on your schedule." }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CH_STATUS,
                "Current mode",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Quiet reminder of which mode is running."
                setShowBadge(false)
            }
        )
    }

    companion object {
        const val CH_DIGEST = "digest"
        const val CH_STATUS = "status"
    }
}

/**
 * Hand-rolled service locator. A DI framework would be more ceremony than this
 * app earns, and services like [dev.jaronwilson.modes.notify.NotificationGate]
 * are constructed by the system, so they need a static entry point anyway.
 */
object AppGraph {
    lateinit var appContext: Context
        private set

    val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var repo: ModeRepository
        private set
    lateinit var applier: ModeApplier
        private set
    lateinit var scheduler: ModeScheduler
        private set

    private var initialized = false

    @Synchronized
    fun init(context: Context) {
        if (initialized) return
        appContext = context.applicationContext
        repo = ModeRepository(appContext, scope)
        applier = ModeApplier(appContext, repo)
        scheduler = ModeScheduler(appContext, repo, applier)
        repo.startCaching()
        initialized = true
    }

    /** For components the system may start before Application.onCreate has run. */
    fun ensure(context: Context) = init(context)
}
