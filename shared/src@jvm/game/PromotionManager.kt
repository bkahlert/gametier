package com.bkahlert.gametier.game

import com.bkahlert.gametier.files.FileMover
import com.bkahlert.gametier.files.FileTreeSnapshot
import com.bkahlert.gametier.files.TransferProgress
import com.bkahlert.gametier.files.TransferPhase
import com.bkahlert.gametier.settings.AppSettings
import com.bkahlert.gametier.settings.appSettingsFile
import com.bkahlert.gametier.settings.getAppSettings
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Owns protected application transfers and independent source snapshots.
 * Standalone [Promoter] and [Demoter] remain low-level transfers without snapshot management.
 * [promote] takes a parent directory, unlike [Promoter.promote]'s full destination path.
 */
class PromotionManager internal constructor(
    private val settings: AppSettings,
    private val operations: GameFileOperations,
    private val index: PromotionIndex,
    private val backups: BackupStore = BackupStore(),
    private val transferLock: TransferLock? = null,
) {
    constructor(settings: AppSettings = getAppSettings(), backups: BackupStore = BackupStore()) :
        this(settings, GameFileOperations(), PromotionIndex(), backups, TransferLock(
            appSettingsFile(System.getenv("LOCALAPPDATA"), System.getProperty("gametier.localAppData"))
                .resolveSibling("transfers.lock")))

    suspend fun registerDirectory(directory: Path) {
        serialized { register(directory) }
    }

    suspend fun inventory(): List<DestinationInventory> = serialized {
        val discovered = backups.discover()
        val directories = try {
            settings.promotionFolders.first() + listOfNotNull(settings.defaultPromotionFolder.first())
        } catch (error: Exception) {
            if (error is CancellationException || discovered.isEmpty()) throw error
            emptySet<String>()
        }
        val inspected = directories.map { PromotionInventory.inspect(Path.of(it)) }.distinctBy { it.directory }
        val all = inspected + discovered.map { it.destination.parent }.distinct().filter { path -> inspected.none { it.directory == path } }
            .map { PromotionInventory.inspect(it).copy(directory = it) }
        all.map { inventory ->
            val sourceRecords = discovered.filter { it.destination.parent == inventory.directory }
            val promotions = inventory.promotions.map { promotion ->
                try {
                    val record = backups.read(promotion.original)
                    if (record == null) promotion
                    else if (record.destination != promotion.destination) promotion.copy(
                        condition = PromotionCondition.NEEDS_ATTENTION, problem = "Source backup belongs to another destination",
                        backup = backups.dataLocation(promotion.original))
                    else promotion.withBackup(record)
                } catch (error: Exception) {
                    promotion.copy(condition = PromotionCondition.NEEDS_ATTENTION,
                        problem = "Cannot read source backup: ${error.message}", backup = backups.dataLocation(promotion.original))
                }
            }
            val recovered = sourceRecords.filter { record -> promotions.none { it.original == record.original } }.map { record ->
                Promotion(record.original, record.destination, record.state, PromotionCondition.NEEDS_ATTENTION,
                    "Source backup found without destination metadata; inspect original and destination").withBackup(record)
            }
            inventory.copy(promotions = promotions + recovered)
        }.sortedBy { it.directory.toString() }
    }

    suspend fun promote(original: Path, directory: Path): Unit = promote(original, directory) {}

    suspend fun promote(original: Path, directory: Path, onProgress: (TransferProgress) -> Unit): Unit = serialized {
        onProgress(TransferProgress(TransferPhase.PREPARING))
        PromotionEligibility.check(original)?.let { throw IOException(it) }
        val origin = canonicalGameEntry(original).toRealPath()
        requireOrdinaryParents(origin.parent)
        val root = register(directory)
        val target = root.resolve(origin.fileName.toString())
        val records = index.read(root)
        val pending = IndexedPromotion(target.fileName.toString(), origin, TransferState.PROMOTING)
        if (records.any { root.resolve(it.name) == target || it.origin == origin }) {
            throw IOException("Promotion is already indexed; inspect original and destination before retrying")
        }
        if (gameEntryExists(target)) throw IOException("Destination already exists: $target")
        FileMover.validateSource(origin)
        val expectedOriginal = FileMover.snapshot(origin)
        if (target.startsWith(origin.toRealPath())) throw IOException("Destination must not be inside the original game")
        preflight(origin, target)
        val backup = backups.reserve(origin, target)
        index.write(root, records + pending)
        var copiedTarget: FileTreeSnapshot? = null
        var relocated = false
        var phase = "copy"
        try {
            operations.copy(origin, target, onProgress)
            copiedTarget = FileMover.snapshot(target)
            phase = "snapshot relocation"
            operations.move(origin, backup.data, onProgress)
            relocated = true
            phase = "link creation"
            onProgress(TransferProgress(TransferPhase.FINALIZING))
            operations.createLink(origin, target)
        } catch (error: Exception) {
            val failure = transferFailure("promotion", origin, target, phase, error)
            if (relocated && !gameEntryExists(origin)) recoverTransfer(failure) {
                val report = recoveryProgress(failure, onProgress)
                report(TransferProgress(TransferPhase.PREPARING))
                operations.move(backup.data, origin, report)
                report(TransferProgress(TransferPhase.FINALIZING))
            }
            val expectedTarget = copiedTarget
            if (expectedTarget != null && isRealGameEntry(origin) && !gameEntryExists(backup.data)) recoverTransfer(failure) {
                if (FileMover.snapshot(origin) == expectedOriginal) {
                    FileMover.deleteTree(target, expectedTarget)
                    backups.remove(backup)
                }
            }
            throw failure
        }
        backups.write(backup.copy(state = TransferState.PROMOTED))
        index.write(root, records + pending.copy(state = TransferState.PROMOTED))
    }

    suspend fun demote(directory: Path, name: String): Unit = demote(directory, name) {}

    suspend fun demote(directory: Path, name: String, onProgress: (TransferProgress) -> Unit): Unit = serialized {
        onProgress(TransferProgress(TransferPhase.PREPARING))
        val root = directory.toRealPath()
        val records = index.read(root)
        settings.rememberPromotionFolder(root.toString())
        val record = records.singleOrNull { it.name == name }
            ?: throw IOException("No indexed promotion named '$name' in '$root'")
        if (record.state != TransferState.PROMOTED) throw IOException("Promotion needs attention before demoting")
        val target = root.resolve(record.name)
        requireOrdinaryParents(record.origin.parent)
        relationshipProblem(record.origin, target)?.let { throw IOException("Cannot demote '$target': $it") }
        FileMover.validateSource(target)
        val backup = backups.read(record.origin)
        if (backup != null && backup.destination != target) throw IOException("Backup belongs to a different destination")
        val pending = records.map { if (it == record) it.copy(state = TransferState.DEMOTING) else it }
        index.write(root, pending)
        if (backup == null) {
            Demoter.demote(record.origin, operations, onProgress)
        } else {
            if (gameEntryExists(backup.staging)) throw IOException("Demotion staging already exists: ${backup.staging}")
            backups.write(backup.copy(state = TransferState.DEMOTING))
            var removedLink = false
            var installed = false
            try {
                val expectedTarget = FileMover.snapshot(target)
                operations.copy(target, backup.staging, onProgress)
                requireOrdinaryParents(record.origin.parent)
                relationshipProblem(record.origin, target)?.let { throw IOException("Cannot install returned data: $it") }
                operations.delete(record.origin)
                removedLink = true
                operations.move(backup.staging, record.origin, onProgress)
                installed = true
                val expectedOriginal = FileMover.snapshot(record.origin)
                onProgress(TransferProgress(TransferPhase.FINALIZING))
                requireOrdinaryParents(record.origin.parent)
                if (FileMover.snapshot(record.origin) != expectedOriginal) throw IOException("Returned game changed before cleanup")
                FileMover.deleteTree(target, expectedTarget)
                backups.remove(backup)
            } catch (error: Exception) {
                val failure = transferFailure("demotion", record.origin, target, "return", error)
                if (removedLink && !installed && !gameEntryExists(record.origin) && isRealGameEntry(target)) recoverTransfer(failure) {
                    operations.createLink(record.origin, target)
                }
                throw failure
            }
        }
        index.write(root, records.filterNot { it == record })
    }

    suspend fun restoreBackup(original: Path, onProgress: (TransferProgress) -> Unit = {}): Unit = serialized {
        onProgress(TransferProgress(TransferPhase.PREPARING))
        val origin = canonicalGameEntry(original)
        requireOrdinaryParents(origin.parent)
        val backup = backups.read(origin) ?: throw IOException("No source backup for '$origin'")
        restoreProblem(backup)?.let { throw IOException("Cannot restore backup: $it") }
        FileMover.validateSource(backup.data)
        val hadLink = Files.isSymbolicLink(origin)
        val linkIdentity = if (hadLink) Files.readAttributes(origin,
            java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey() else null
        var removed = false
        try {
            onProgress(TransferProgress(TransferPhase.FINALIZING))
            restoreProblem(backup)?.let { throw IOException("Cannot restore backup: $it") }
            if (hadLink && (!Files.isSymbolicLink(origin) || Files.readAttributes(origin,
                    java.nio.file.attribute.BasicFileAttributes::class.java, java.nio.file.LinkOption.NOFOLLOW_LINKS).fileKey() != linkIdentity)) {
                throw IOException("Original link changed before restoration")
            }
            if (hadLink) { operations.delete(origin); removed = true }
            operations.move(backup.data, origin, onProgress)
        } catch (error: Exception) {
            val failure = transferFailure("backup restoration", origin, backup.data, "installation", error)
            if (removed && !gameEntryExists(origin)) recoverTransfer(failure) { operations.createLink(origin, backup.destination) }
            throw failure
        }
        backups.remove(backup)
        val root = backup.destination.parent
        if (gameEntryExists(root.resolve(PROMOTION_INDEX_NAME))) {
            val records = try { index.read(root) } catch (_: IOException) { null }
            if (records != null) index.write(root, records.filterNot { it.origin == origin && root.resolve(it.name) == backup.destination })
        }
    }

    /** The caller must establish that the supplied copy is complete and no longer being written. */
    suspend fun adoptBackup(original: Path, suppliedCopy: Path): Unit = serialized {
        val origin = canonicalGameEntry(original)
        if (!Files.isSymbolicLink(origin)) throw IOException("Original must be the expected promotion link")
        val target = canonicalGameEntry(origin.parent.resolve(Files.readSymbolicLink(origin)).normalize())
        relationshipProblem(origin, target)?.let { throw IOException("Cannot adopt backup: $it") }
        val records = index.read(target.parent)
        if (records.none { it.origin == origin && it.name == target.fileName.toString() && it.state == TransferState.PROMOTED }) {
            throw IOException("No matching completed promotion for '$origin'")
        }
        val supplied = canonicalGameEntry(suppliedCopy)
        requireOrdinaryParents(supplied.parent)
        FileMover.validateSource(supplied)
        if (Files.isDirectory(supplied, java.nio.file.LinkOption.NOFOLLOW_LINKS) !=
            Files.isDirectory(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) throw IOException("Supplied snapshot type does not match promoted data")
        if (supplied.startsWith(target) || target.startsWith(supplied) || supplied == origin || origin.startsWith(supplied)) {
            throw IOException("Supplied snapshot must not overlap active promoted data")
        }
        val backup = backups.reserve(origin, target)
        if (supplied.startsWith(backup.folder) || backup.folder.startsWith(supplied)) throw IOException("Supplied snapshot overlaps backup")
        if (Files.getFileStore(supplied) != Files.getFileStore(backup.folder)) throw IOException("Supplied snapshot must be on the source filesystem")
        Files.move(supplied, backup.data)
        backups.write(backup.copy(state = TransferState.PROMOTED))
        settings.rememberPromotionFolder(target.parent.toString())
    }

    private fun preflight(origin: Path, target: Path) {
        val probe = origin.resolveSibling(".gametier-symlink-${UUID.randomUUID()}")
        try { operations.createLink(probe, origin) } catch (error: Exception) {
            throw SymlinkPreflightException(origin, target, "preflight creation at '$probe'", error)
        }
        try { operations.delete(probe) } catch (error: Exception) {
            throw transferFailure("promotion", origin, target, "preflight cleanup at '$probe'", error, false)
        }
    }

    private suspend fun register(directory: Path): Path {
        val root = directory.toRealPath()
        backups.validateDestination(root)
        if (!Files.isDirectory(root)) throw IOException("Destination must be a directory: $root")
        val remembered = settings.promotionFolders.first().map { Path.of(it).toAbsolutePath().normalize() }
        val known = remembered.any { path -> path == root || try { Files.isSameFile(path, root) } catch (_: IOException) { false } }
        val file = root.resolve(PROMOTION_INDEX_NAME)
        if (gameEntryExists(file)) index.read(root)
        else if (known) throw IOException("Remembered destination has lost its promotion index: $file")
        else index.write(root, emptyList())
        settings.rememberPromotionFolder(root.toString())
        return root
    }

    private suspend fun <T> serialized(action: suspend () -> T): T = withContext(Dispatchers.IO) {
        processMutex.withLock {
            if (transferLock == null) action() else transferLock.withLock(action)
        }
    }

    private companion object { val processMutex = Mutex() }
}

private fun Promotion.withBackup(record: SourceBackup): Promotion = copy(
    state = record.state.takeIf { it != TransferState.PROMOTED } ?: state,
    condition = if (record.state != TransferState.PROMOTED) PromotionCondition.NEEDS_ATTENTION else condition,
    problem = if (record.state != TransferState.PROMOTED) "Interrupted ${record.state.name.lowercase()} operation; inspect source backup and destination" else problem,
    backup = record.data.takeIf { isRealGameEntry(it) },
    canRestoreBackup = restoreProblem(record) == null,
)

private fun restoreProblem(record: SourceBackup): String? = try {
    requireOrdinaryParents(record.original.parent)
    if (!isRealGameEntry(record.data)) throw IOException("Snapshot data is missing or is not ordinary")
    if (gameEntryExists(record.staging)) throw IOException("Interrupted demotion staging exists; inspect '${record.staging}' before recovery")
    if (gameEntryExists(record.destination)) throw IOException("Promoted destination still exists")
    if (gameEntryExists(record.original)) {
        if (!Files.isSymbolicLink(record.original)) throw IOException("Original path is occupied")
        val linked = record.original.parent.resolve(Files.readSymbolicLink(record.original)).normalize()
        if (linked != record.destination) throw IOException("Original link points elsewhere")
    }
    null
} catch (error: Exception) { error.message ?: error.javaClass.simpleName }
