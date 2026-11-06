package com.bkahlert.gametier.settings

import kotlinx.coroutines.flow.Flow

interface AppSettings {
    val defaultPromotionFolder: Flow<String?>

    suspend fun setDefaultPromotionFolder(path: String)

    suspend fun clearDefaultPromotionFolder()
}

expect fun getAppSettings(): AppSettings

internal expect fun validatePromotionFolder(path: String)
