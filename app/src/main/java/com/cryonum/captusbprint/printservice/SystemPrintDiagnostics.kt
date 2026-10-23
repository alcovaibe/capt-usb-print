package com.cryonum.captusbprint.printservice

import android.content.Context
import android.os.Build
import android.util.AtomicFile
import com.cryonum.captusbprint.BuildConfig
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Small private routing/intake journal, including failures that occur before a queue job exists. */
internal object SystemPrintDiagnostics {
    fun file(context: Context) = File(context.noBackupFilesDir, "system-print-latest.json")
    @Synchronized fun record(context: Context, event: String, request: SystemPrintRequest? = null,
                             queueId: Long? = null, error: String? = null) {
        runCatching {
            val atomic = AtomicFile(file(context))
            val previous = runCatching {
                check(file(context).length() <= 256 * 1024)
                JSONObject(atomic.openRead().bufferedReader().use { it.readText() }).getJSONArray("events")
            }.getOrElse { JSONArray() }
            val events = JSONArray()
            for (i in maxOf(0, previous.length() - 23) until previous.length()) events.put(previous.get(i))
            events.put(JSONObject().put("atMillis", System.currentTimeMillis()).put("event", event)
                .put("queueId", queueId ?: JSONObject.NULL).put("errorCode", error ?: JSONObject.NULL)
                .put("request", request?.let { JSONObject(it.diagnostic()) } ?: JSONObject.NULL))
            val json = JSONObject().put("schemaVersion", 1).put("appVersion", BuildConfig.VERSION_NAME)
                .put("sourceRevision", BuildConfig.SOURCE_REVISION).put("apiLevel", Build.VERSION.SDK_INT)
                .put("phoneModel", Build.MODEL).put("automaticRetry", false).put("events", events)
            val output = atomic.startWrite()
            try { output.write((json.toString() + "\n").toByteArray()); atomic.finishWrite(output) }
            catch (error: Throwable) { atomic.failWrite(output); throw error }
        }
    }
}
