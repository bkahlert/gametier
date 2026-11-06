package com.bkahlert.gametier

import com.bkahlert.gametier.ui.App

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "gametier") {
        App()
    }
}
