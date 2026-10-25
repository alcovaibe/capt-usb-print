package com.cryonum.captusbprint

import com.cryonum.captusbprint.office.OfficeConversionFailure
import com.cryonum.captusbprint.office.OfficeFailure
import com.cryonum.captusbprint.office.OfficeWorkerBackend
import com.cryonum.captusbprint.office.OfficeWorkerService

/** Keeps production admission, then stops before any native backend. Debug-only, not exported. */
class OfficeAdmissionWorkerService : OfficeWorkerService() {
    internal override fun newBackend(): OfficeWorkerBackend =
        throw OfficeConversionFailure(OfficeFailure.ENGINE_UNAVAILABLE)
}
