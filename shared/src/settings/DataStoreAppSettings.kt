package com.bkahlert.gametier.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

internal class DataStoreAppSettings(private val store: DataStore<Preferences>) : AppSettings {
    override val defaultPromotionFolder = store.data.map { preferences ->
        try {
            preferences[promotionFolderKey]?.also(::validatePromotionFolder)
        } catch (cause: IllegalArgumentException) {
            throw IllegalStateException("Invalid stored default promotion folder", cause)
        } catch (cause: ClassCastException) {
            throw IllegalStateException("Invalid stored default promotion folder", cause)
        }
    }.distinctUntilChanged()

    override suspend fun setDefaultPromotionFolder(path: String) {
        validatePromotionFolder(path)
        store.edit { it[promotionFolderKey] = path }
    }

    override suspend fun clearDefaultPromotionFolder() {
        store.edit { it.remove(promotionFolderKey) }
    }

    private companion object {
        val promotionFolderKey = stringPreferencesKey("default_promotion_folder")
    }
}
