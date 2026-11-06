package com.bkahlert.gametier.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

actual fun getAppSettings(): AppSettings = processSettings

private val processSettings: AppSettings by lazy {
    createJvmAppSettings(
        appSettingsFile(System.getenv("LOCALAPPDATA"), System.getProperty("gametier.localAppData")),
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )
}

internal fun createJvmAppSettings(file: Path, scope: CoroutineScope): AppSettings = DataStoreAppSettings(
    PreferenceDataStoreFactory.create(storage = appSettingsStorage(file), scope = scope),
)

internal fun appSettingsFile(localAppData: String?, originalLocalAppData: String? = null): Path {
    val localAppData = originalLocalAppData ?: localAppData
    check(!localAppData.isNullOrBlank()) { "LOCALAPPDATA is unavailable" }
    val root = Path.of(localAppData)
    check(root.isAbsolute) { "LOCALAPPDATA must be an absolute path" }
    return root.resolve("GameTier/settings.preferences_pb")
}
