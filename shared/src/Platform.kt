package com.bkahlert.gametier

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform
