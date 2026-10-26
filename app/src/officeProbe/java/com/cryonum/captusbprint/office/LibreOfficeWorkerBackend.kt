package com.cryonum.captusbprint.office

import android.content.Context
import android.content.res.AssetManager
import org.json.JSONObject
import org.libreoffice.kit.Document
import org.libreoffice.kit.LibreOfficeKit
import org.libreoffice.kit.Office
import java.io.File

/** Opt-in debug backend. Not reachable from SAF/ACTION_SEND; file access is confined by the mandatory worker guard. */
internal class LibreOfficeWorkerBackend : OfficeWorkerBackend {
    override fun preload() = LibreOfficeKit.initializeLibrary()
    override fun convert(context: Context, input: File, output: File, directory: File,
                         options: OfficeOptions): List<String> {
        val engine = File(directory, "engine")
        unpack(context.assets, "unpack", engine)
        val fonts = File(engine, "etc/fonts/fonts.conf")
        val configuration = fonts.readText()
        check(configuration.contains("/data/data/org.documentfoundation.libreoffice/fontconfig"))
        check(configuration.windowed("</fontconfig>".length).count { it == "</fontconfig>" } == 1)
        val aliases = context.assets.open("office-font-aliases.xml").bufferedReader().use { it.readText() }
        fonts.writeText(configuration.replace("/data/data/org.documentfoundation.libreoffice/fontconfig",
            File(engine, "fontconfig").absolutePath).replace("</fontconfig>", "$aliases\n</fontconfig>"))
        LibreOfficeKit.init(context, engine, false)
        val office = Office(requireNotNull(LibreOfficeKit.getLibreOfficeKitHandle()))
        val document = office.documentLoad(input.toURI().toASCIIString())
            ?: throw OfficeConversionFailure(OfficeFailure.BAD_INPUT)
        try {
            if (document.getDocumentType() != if (options.kind.spreadsheet) Document.DOCTYPE_SPREADSHEET else Document.DOCTYPE_TEXT)
                throw OfficeConversionFailure(OfficeFailure.BAD_INPUT)
            val sheets = if (options.kind.spreadsheet) {
                val count = document.getParts()
                if (count !in 1..256) throw OfficeConversionFailure(OfficeFailure.BAD_INPUT)
                List(count) { document.getPartName(it).also { name ->
                    if (name.length > 256) throw OfficeConversionFailure(OfficeFailure.BAD_INPUT)
                } }
            } else emptyList()
            if (options.selectedSheets?.any { it !in sheets.indices } == true)
                throw OfficeConversionFailure(OfficeFailure.BAD_INPUT)
            if (options.fitWidth) {
                when (document.configureCalcFitWidth(options.selectedSheets?.toIntArray() ?: intArrayOf())) {
                    1 -> Unit
                    -1 -> throw OfficeConversionFailure(OfficeFailure.FIT_WIDTH_UNSUPPORTED)
                    else -> throw OfficeConversionFailure(OfficeFailure.UNSUPPORTED_LAYOUT)
                }
            }
            val filter = if (options.kind.spreadsheet) JSONObject()
                .put("SinglePageSheets", JSONObject().put("type", "boolean").put("value", false))
                .apply { options.selectedSheets?.let { selected ->
                    put("SheetRange", JSONObject().put("type", "string")
                        .put("value", selected.joinToString(",") { (it + 1).toString() }))
                } }.toString() else ""
            check(!output.exists())
            if (document.saveAs(output.toURI().toASCIIString(), "pdf", filter) == 0)
                throw OfficeConversionFailure(OfficeFailure.ENGINE_FAILED)
            return sheets
        } finally { document.destroy() }
    }

    private fun unpack(assets: AssetManager, path: String, directory: File) {
        val entries = requireNotNull(assets.list(path))
        check(entries.isNotEmpty() && directory.mkdirs())
        for (name in entries) {
            val asset = "$path/$name"
            val target = File(directory, name)
            if (!assets.list(asset).isNullOrEmpty()) unpack(assets, asset, target)
            else assets.open(asset).use { input -> target.outputStream().use { input.copyTo(it) } }
        }
    }
}
