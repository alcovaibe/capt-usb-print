package com.cryonum.captusbprint

import android.os.Bundle
import android.os.Looper
import com.cryonum.captusbprint.office.OfficeKind
import com.cryonum.captusbprint.office.OfficePreviewActivity
import java.lang.ref.WeakReference

/** Opt-in, non-exported: only fixed assets, no content/file URI or arbitrary Intent path. */
internal class OfficeFixturePreviewActivity : OfficePreviewActivity() {
    companion object { var current = WeakReference<OfficeFixturePreviewActivity>(null) }
    override val officeKind get() = OfficeKind.entries.singleOrNull { it.extension == intent.getStringExtra("kind") } ?: OfficeKind.XLS
    override val allowQueue get() = true // Only the synthetic assets below; still explicit USB permission/confirmation.
    override fun openSource(): java.io.InputStream {
        check(Looper.myLooper() != Looper.getMainLooper())
        return applicationContext.assets.open("office-fixtures/${if (officeKind.spreadsheet) "workbook" else "word"}.${officeKind.extension}")
    }
    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); current = WeakReference(this) }
}
