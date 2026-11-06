package com.bkahlert.gametier.testsupport.workflow

internal fun withSystemProperties(vararg properties: Pair<String, String?>, action: () -> Unit) {
    val previous = properties.associate { (name, _) -> name to System.getProperty(name) }
    try {
        properties.forEach { (name, value) ->
            if (value == null) System.clearProperty(name) else System.setProperty(name, value)
        }
        action()
    } finally {
        previous.forEach { (name, value) ->
            if (value == null) System.clearProperty(name) else System.setProperty(name, value)
        }
    }
}
