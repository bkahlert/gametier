package com.bkahlert.gametier.game

import com.bkahlert.gametier.settings.AppSettings
import com.bkahlert.gametier.settings.DataStoreAppSettings
import com.bkahlert.gametier.settings.MemoryPreferencesStore
import java.nio.file.Path

internal fun isolatedPromotionManager(
    root: Path,
    settings: AppSettings = DataStoreAppSettings(MemoryPreferencesStore()),
    operations: GameFileOperations = GameFileOperations(),
    index: PromotionIndex = PromotionIndex(),
): PromotionManager = PromotionManager(settings, operations, index, BackupStore({ root.resolve("backups") }, { listOf(root) }))

internal data class SourceBackupFixture(
    val original: Path,
    val directory: Path,
    val target: Path,
    val store: BackupStore,
    val record: SourceBackup,
)

internal fun sourceBackupFixture(root: Path): SourceBackupFixture {
    val original = java.nio.file.Files.writeString(root.resolve("game"), "snapshot")
    val directory = java.nio.file.Files.createDirectory(root.resolve("games"))
    val target = directory.resolve("game")
    val store = BackupStore({ root.resolve("backups") }, { listOf(root) })
    val record = store.reserve(original, target)
    java.nio.file.Files.move(original, record.data)
    return SourceBackupFixture(original, directory, target, store, record)
}
