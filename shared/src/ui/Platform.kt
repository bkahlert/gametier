package com.bkahlert.gametier.ui

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
