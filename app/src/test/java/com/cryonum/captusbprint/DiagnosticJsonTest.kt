package com.cryonum.captusbprint

import com.cryonum.captusbprint.core.DiagnosticJson
import org.junit.Assert.*
import org.junit.Test

class DiagnosticJsonTest {
    @Test fun encodesStructuredEventsWithTypesAndNulls() {
        val value = linkedMapOf<String, Any?>(
            "schemaVersion" to 1,
            "scan" to mapOf("available" to true, "devices" to emptyList<Any>()),
            "commands" to listOf(mapOf(
                "action" to "GetPrinterInfo",
                "rawResponseFrameHex" to "A1 A1 0E 00",
                "decoded" to mapOf("blockSize" to 65520, "safeChunkBytes" to null)
            ))
        )
        assertEquals(
            """{"schemaVersion":1,"scan":{"available":true,"devices":[]},"commands":[{"action":"GetPrinterInfo","rawResponseFrameHex":"A1 A1 0E 00","decoded":{"blockSize":65520,"safeChunkBytes":null}}]}""",
            DiagnosticJson.stringify(value)
        )
    }

    @Test fun escapesTextAndControlCharactersInsteadOfBreakingJson() {
        val input = "\"\\\n\t\r\b" + '\u0000' + "Тест"
        assertEquals(
            "\"" + "\\\"" + "\\\\" + "\\n" + "\\t" + "\\r" + "\\b" + "\\u0000" + "Тест" + "\"",
            DiagnosticJson.stringify(input)
        )
    }

    @Test fun rejectsImplicitObjectsOrInvalidNumbersInExport() {
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticJson.stringify(mapOf("device" to Any()))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticJson.stringify(mapOf(42 to "not a JSON string key"))
        }
        assertThrows(IllegalArgumentException::class.java) {
            DiagnosticJson.stringify(Double.NaN)
        }
    }
}
