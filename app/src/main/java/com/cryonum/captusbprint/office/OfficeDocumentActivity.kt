package com.cryonum.captusbprint.office

import android.os.Bundle
import com.cryonum.captusbprint.R
import java.io.File
import java.util.UUID

/** Owns a private intake snapshot across configuration recreation, without retaining a URI grant. */
internal class OfficeDocumentActivity : OfficePreviewActivity() {
    private lateinit var snapshotDirectory: File
    private lateinit var kind: OfficeKind
    private var valid = false
    override val sourceAvailable get() = valid
    override val officeKind get() = kind
    override val allowQueue get() = true
    override val sourceDescription get() = R.string.office_local_document
    override fun openSource() = File(snapshotDirectory, "source.bin").inputStream()

    override fun onCreate(savedInstanceState: Bundle?) {
        val accepted = runCatching {
            val id = requireNotNull(intent.getStringExtra("office-source"))
            require(UUID.fromString(id).toString() == id)
            kind = OfficeKind.valueOf(requireNotNull(intent.getStringExtra("office-kind")))
            snapshotDirectory = File(noBackupFilesDir, "office-ui/intake-$id")
            require(!java.nio.file.Files.isSymbolicLink(snapshotDirectory.toPath()))
            require(File(snapshotDirectory, "source.bin").isFile)
        }.isSuccess
        // Startup cleanup deliberately expires snapshots after a process restart.
        valid = accepted
        super.onCreate(savedInstanceState)
    }
    override fun onDestroy() {
        super.onDestroy()
        if (valid && !isChangingConfigurations) {
            val owned = snapshotDirectory
            Thread { OfficePrivateFiles.deleteTree(owned) }.start()
        }
    }
}
