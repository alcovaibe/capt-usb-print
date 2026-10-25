package com.cryonum.captusbprint.updates

import android.content.Context
import android.os.Build
import com.cryonum.captusbprint.BuildConfig
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Retained by MainActivity across rotation; contains only application context. */
internal class UpdateSession(context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app = context.applicationContext
    private val source = SiteUpdateSource()
    private val downloader = GithubUpdateDownloader(app)
    private val prefs = app.getSharedPreferences("app_update_check", Context.MODE_PRIVATE)
    val controller = UpdateController(scope, source, object : UpdatePreferences {
        override var lastAutomaticAttempt: Long
            get() = prefs.getLong("last_automatic_attempt", 0)
            set(value) { prefs.edit().putLong("last_automatic_attempt", value).apply() }
        override var deferredCode: Int
            get() = prefs.getInt("deferred_code", 0)
            set(value) { prefs.edit().putInt("deferred_code", value).apply() }
    }, BuildConfig.PUBLIC_UPDATE_CHECKS && app.packageName == UpdateUrls.APPLICATION_ID,
        BuildConfig.VERSION_CODE.toLong(), Build.VERSION.SDK_INT, downloader = downloader)

    init {
        // Keep recently granted APKs readable even if the installer outlives our Activity.
        scope.launch(Dispatchers.IO) {
            val cutoff = System.currentTimeMillis() - 24 * 60 * 60 * 1000L
            File(app.cacheDir, "updates").listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }

    fun close() {
        controller.close()
        scope.cancel()
        source.close()
        downloader.close()
    }
}
