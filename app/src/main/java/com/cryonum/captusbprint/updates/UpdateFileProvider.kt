package com.cryonum.captusbprint.updates

import androidx.core.content.FileProvider

/** Separate component identity from the existing diagnostics provider in the same process. */
class UpdateFileProvider : FileProvider()
