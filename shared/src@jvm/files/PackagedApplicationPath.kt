package com.bkahlert.gametier.files

import java.nio.file.Path

internal fun packagedApplicationPath(): Path? =
    (System.getProperty("gametier.appPath") ?: System.getProperty("jpackage.app-path"))?.let(Path::of)
