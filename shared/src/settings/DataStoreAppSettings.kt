package com.bkahlert.gametier.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class DataStoreAppSettings(private val store: DataStore<Preferences>) : AppSettings {
    override val defaultPromotionFolder = store.data.map(::readDefaultPromotionFolder).distinctUntilChanged()
    override val promotionFolders = store.data.map(::readPromotionFolders).distinctUntilChanged()

    override suspend fun setDefaultPromotionFolder(path: String) {
        validatePromotionFolder(path)
        store.edit { it[promotionFolderKey] = path }
    }

    override suspend fun clearDefaultPromotionFolder() {
        store.edit { it.remove(promotionFolderKey) }
    }

    override suspend fun rememberPromotionFolder(path: String) {
        validatePromotionFolder(path)
        store.edit { it[promotionFoldersKey] = readPromotionFolders(it) + path }
    }

    private fun readDefaultPromotionFolder(preferences: Preferences): String? = validateStored("default promotion folder") {
        preferences[promotionFolderKey]?.also(::validatePromotionFolder)
    }

    private fun readPromotionFolders(preferences: Preferences): Set<String> = validateStored("promotion folders") {
        preferences[promotionFoldersKey].orEmpty().onEach(::validatePromotionFolder)
    }

    private inline fun <T> validateStored(description: String, read: () -> T): T = try {
        read()
    } catch (cause: IllegalArgumentException) {
        throw IllegalStateException("Invalid stored $description", cause)
    } catch (cause: ClassCastException) {
        throw IllegalStateException("Invalid stored $description", cause)
    }

    private companion object {
        val promotionFolderKey = stringPreferencesKey("default_promotion_folder")
        val promotionFoldersKey = stringSetPreferencesKey("promotion_folders")
    }
}
