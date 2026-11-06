package com.bkahlert.gametier.settings

import kotlinx.coroutines.flow.Flow

interface AppSettings {
    val defaultPromotionFolder: Flow<String?>
    val promotionFolders: Flow<Set<String>>

    suspend fun setDefaultPromotionFolder(path: String)

    suspend fun clearDefaultPromotionFolder()

    suspend fun rememberPromotionFolder(path: String)
}

expect fun getAppSettings(): AppSettings

internal expect fun validatePromotionFolder(path: String)
