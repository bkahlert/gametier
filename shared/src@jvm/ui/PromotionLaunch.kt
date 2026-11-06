package com.bkahlert.gametier.ui

import java.nio.file.Path

data class PromotionLaunch private constructor(val original: Path) {
    fun open(controller: AppController) = controller.select(listOf(original))

    companion object {
        fun parse(arguments: Array<String>): PromotionLaunch? {
            if (arguments.firstOrNull() != "--promote") return null
            require(arguments.size == 2) { "Invalid promotion arguments" }
            val path = try { Path.of(arguments[1]) } catch (error: IllegalArgumentException) {
                throw IllegalArgumentException("Invalid promotion path", error)
            }
            require(path.isAbsolute && path.normalize().parent != null) { "Invalid promotion path: select an absolute file or folder path" }
            return PromotionLaunch(path.normalize())
        }
    }
}
