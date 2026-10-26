package com.cryonum.captusbprint.office

import android.app.Activity
import android.app.AlertDialog
import android.widget.ScrollView
import android.widget.TextView
import com.cryonum.captusbprint.R
import org.json.JSONArray

/** Only bundled third-party notices. No document metadata, network or external paths. */
internal object OfficeLicenses {
    fun show(activity: Activity) {
        try {
            val index = activity.assets.open("office-licenses/index.json").bufferedReader().use { JSONArray(it.readText()) }
            val files = (0 until index.length()).map { index.getJSONObject(it).getString("file") }
                .filter { !it.endsWith(".sfd") && !it.endsWith(".fodt") }
            val preferred = listOf("ABOUT.txt", "LICENSE", "fonts.json", "CAPT-COPYING", "COPYING.MPL", "COPYING.LGPL", "source-inputs.json")
            val ordered = preferred.filter { it in files } + files.filter { it !in preferred }.sorted()
            AlertDialog.Builder(activity).setTitle(R.string.office_licenses)
                .setItems(ordered.toTypedArray()) { _, which ->
                    val name = ordered[which]
                    // Names originate only from our generated APK index, still reject traversal.
                    check(name.split('/').none { it.isEmpty() || it == "." || it == ".." })
                    val body = activity.assets.open("office-licenses/$name").use { stream ->
                        val output = java.io.ByteArrayOutputStream()
                        val bytes = ByteArray(8192)
                        while (true) {
                            val count = stream.read(bytes)
                            if (count < 0) break
                            check(output.size() <= 2 * 1024 * 1024 - count)
                            output.write(bytes,0,count)
                        }
                        output.toString("UTF-8")
                    }
                    val text = TextView(activity).apply { setPadding(24,16,24,16); this.text = body; setTextIsSelectable(true) }
                    AlertDialog.Builder(activity).setTitle(name)
                        .setView(ScrollView(activity).apply { addView(text) })
                        .setPositiveButton(android.R.string.ok,null).show()
                }.setNegativeButton(android.R.string.cancel,null).show()
        } catch (_: Exception) {
            AlertDialog.Builder(activity).setMessage(R.string.office_error_engine)
                .setPositiveButton(android.R.string.ok,null).show()
        }
    }
}
