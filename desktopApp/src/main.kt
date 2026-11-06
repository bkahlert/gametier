package com.bkahlert.gametier

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import com.bkahlert.gametier.resources.Res
import com.bkahlert.gametier.resources.gametier
import org.jetbrains.compose.resources.painterResource

fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "GameTier",
        icon = painterResource(Res.drawable.gametier),
    ) {
        App()
    }
}
