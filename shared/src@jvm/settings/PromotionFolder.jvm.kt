package com.bkahlert.gametier.settings

import java.nio.file.Path

internal actual fun validatePromotionFolder(path: String) {
    require(path.isNotBlank()) { "The default promotion folder must not be blank" }
    require(Path.of(path).isAbsolute) { "The default promotion folder must be an absolute path" }
}
