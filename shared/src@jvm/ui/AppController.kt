package com.bkahlert.gametier.ui

import com.bkahlert.gametier.game.Promotion
import com.bkahlert.gametier.game.PromotionCondition
import com.bkahlert.gametier.game.DestinationStatus
import com.bkahlert.gametier.settings.AppSettings
import com.bkahlert.gametier.settings.validatePromotionFolder
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppController internal constructor(
    private val scope: CoroutineScope,
    private val settings: AppSettings,
    private val operations: PromotionOperations,
    private val preparer: SelectionPreparer,
    private val io: CoroutineDispatcher,
) {
    constructor(scope: CoroutineScope, settings: AppSettings, operations: PromotionOperations, preparer: SelectionPreparer) :
        this(scope, settings, operations, preparer, Dispatchers.IO)

    private val mutableState = MutableStateFlow(AppState(settingsLoading = true))
    val state: StateFlow<AppState> = mutableState.asStateFlow()
    private var generation = 0L
    private var activePreparations = 0
    private var reviewDirectory: Path? = null
    private var inputRevision = 0L
    private var inputDirty = false
    private var nextId = 1L
    private var refreshRequested = false
    private var worker: Job? = null
    private var defaultLoaded = false
    private var rememberedLoaded = false
    private var defaultReadFinished = false
    private var rememberedReadFinished = false
    private var pendingReviewDirectory: Path? = null
    private var selectionAdjustments = emptyList<String>()
    private var defaultReadError: Exception? = null
    private var rememberedReadError: Exception? = null
    private var settingsWriteError: Exception? = null
    private var defaultObserving = false
    private var rememberedObserving = false
    private var unrefreshedTransfers = emptyList<TransferRequest>()

    init {
        action {
            observeDefault()
            observeRemembered()
            requestRefresh()
        }
    }

    private fun observeDefault() {
        if (defaultObserving) return
        defaultObserving = true
        defaultReadFinished = false
        edit { it.copy(settingsLoading = true) }
        scope.launch {
            try {
                settings.defaultPromotionFolder.collect { value ->
                    val directory = value?.let(Path::of)
                    defaultLoaded = true
                    defaultReadFinished = true
                    defaultReadError = null
                    edit { it.copy(defaultDirectory = directory, defaultInput = if (inputDirty) it.defaultInput else value.orEmpty(), settingsError = settingsProblem()) }
                    resumeSelection()
                    requestRefresh()
                }
            } catch (error: Exception) {
                error.rethrowCancellation()
                defaultLoaded = false
                defaultReadFinished = true
                defaultReadError = error
                invalidateSettingsReview(error)
                finishSettingsRead()
                requestRefresh()
            } finally {
                defaultObserving = false
            }
        }
    }

    private fun observeRemembered() {
        if (rememberedObserving) return
        rememberedObserving = true
        rememberedReadFinished = false
        edit { it.copy(settingsLoading = true) }
        scope.launch {
            try {
                settings.promotionFolders.collect { folders ->
                    val identities = onIo { folders.associateWith { directoryIdentity(Path.of(it)) } }
                    rememberedLoaded = true
                    rememberedReadFinished = true
                    rememberedReadError = null
                    edit { it.copy(rememberedFolders = folders.toSet(), rememberedDirectoryIdentities = identities, settingsError = settingsProblem()) }
                    resumeSelection()
                    requestRefresh()
                }
            } catch (error: Exception) {
                error.rethrowCancellation()
                rememberedLoaded = false
                rememberedReadFinished = true
                rememberedReadError = error
                invalidateSettingsReview(error)
                finishSettingsRead()
                requestRefresh()
            } finally {
                rememberedObserving = false
            }
        }
    }

    private fun retrySettingsReads() {
        if (defaultReadError != null) observeDefault()
        if (rememberedReadError != null) observeRemembered()
    }
    private fun settingsProblem(): Exception? = settingsWriteError ?: rememberedReadError ?: defaultReadError
    private fun invalidateSettingsReview(error: Exception) {
        generation++
        edit { it.copy(settingsError = settingsProblem(), closeWhenFinished = false,
            review = null, preparing = false, reviewError = if (it.pendingSelection.isNotEmpty()) error else it.reviewError) }
    }

    fun showPromotions() = action { edit { it.copy(screen = AppScreen.PROMOTIONS) } }
    fun showSettings() = action { edit { it.copy(screen = AppScreen.SETTINGS) } }
    fun setSearch(value: String) = action { edit { it.copy(search = value) } }
    fun setDefaultInput(value: String) = action { changeInput(value) }
    fun saveDefault() = action { save() }
    fun chooseDefault(directory: Path) = action {
        changeInput(directory.toString())
        save()
    }

    fun select(paths: List<Path>) = action {
        if (paths.isNotEmpty()) {
            reviewDirectory = state.value.defaultDirectory
            pendingReviewDirectory = null
            selectionAdjustments = emptyList()
            edit { it.copy(pendingSelection = paths.toList(), screen = AppScreen.PROMOTIONS) }
            prepare()
        }
    }

    fun setReviewDirectory(directory: Path) = action {
        if (!defaultLoaded || !rememberedLoaded) {
            pendingReviewDirectory = directory
            return@action
        }
        if (state.value.defaultDirectory == null) {
            changeInput(directory.toString())
            save(firstChoice = true)
        } else {
            reviewDirectory = directory
            prepare()
        }
    }

    fun removeReviewItem(original: Path) = action {
        edit { current -> current.copy(
            pendingSelection = current.pendingSelection.filterNot { it == original },
            review = current.review?.let { review -> review.copy(items = review.items.filterNot { it.original == original }) },
        ) }
        if (state.value.pendingSelection.isEmpty()) clearReview()
        else prepare()
    }

    fun cancelReview() = action { clearReview() }

    fun confirmReview() = action {
        val review = state.value.review ?: return@action
        val directory = review.directory ?: return@action
        if (!defaultLoaded || !rememberedLoaded || !state.value.inventoryLoaded || state.value.preparing || review.problem != null || state.value.savingSettings) return@action
        val requests = review.items.filter { it.status == ReviewStatus.READY }.mapNotNull { item ->
            item.destination?.let { TransferRequest(TransferKind.PROMOTE, item.original, directory, it) }
        }
        clearReview()
        requests.forEach(::enqueue)
        ensureWorker()
    }

    fun requestDemote(promotion: Promotion) = action {
        if (promotion.condition == PromotionCondition.PROMOTED) edit { it.copy(demotion = promotion) }
    }
    fun cancelDemote() = action { edit { it.copy(demotion = null) } }
    fun confirmDemote() = action {
        val promotion = state.value.demotion ?: return@action
        edit { it.copy(demotion = null) }
        enqueue(TransferRequest(TransferKind.DEMOTE, promotion.original, promotion.destination.parent, promotion.destination))
        ensureWorker()
    }
    fun refresh() = action { retrySettingsReads(); requestRefresh() }
    fun acknowledge(id: Long) = action {
        edit { it.copy(activity = it.activity.map { item -> if (item.id == id) item.copy(acknowledged = true) else item }) }
    }
    fun setActivityExpanded(value: Boolean) = action { edit { it.copy(activityExpanded = value) } }
    fun requestClose() = action {
        if (hasWork()) edit { it.copy(closePrompt = true) } else edit { it.copy(exitRequested = true) }
    }
    fun keepOpen() = action { edit { it.copy(closePrompt = false, closeWhenFinished = false) } }
    fun closeWhenFinished() = action { edit { it.copy(closePrompt = false, closeWhenFinished = true) } }
    fun reportError(error: Throwable) = action { edit { it.copy(inputError = error, closeWhenFinished = false) } }
    fun clearError() = action { edit { it.copy(inputError = null) } }

    private fun changeInput(value: String) {
        inputRevision++
        inputDirty = true
        edit { it.copy(defaultInput = value) }
    }

    private suspend fun save(firstChoice: Boolean = false) {
        if (state.value.savingSettings) return
        val input = state.value.defaultInput
        val revision = inputRevision
        settingsWriteError = null
        try {
            validatePromotionFolder(input)
            edit { it.copy(savingSettings = true, settingsError = settingsProblem()) }
            settings.setDefaultPromotionFolder(input)
            val directory = Path.of(input)
            defaultLoaded = true
            defaultReadFinished = true
            if (revision == inputRevision) inputDirty = false
            edit { it.copy(defaultDirectory = directory, settingsError = settingsProblem()) }
            if (reviewDirectory == null && state.value.pendingSelection.isNotEmpty()) {
                reviewDirectory = directory
                prepare()
            }
            retrySettingsReads()
            requestRefresh()
        } catch (error: Exception) {
            error.rethrowCancellation()
            settingsWriteError = error
            edit { it.copy(settingsError = settingsProblem(), reviewError = if (firstChoice) error else it.reviewError, closeWhenFinished = false) }
        } finally {
            edit { it.copy(savingSettings = false) }
        }
    }

    private fun clearReview() {
        generation++
        reviewDirectory = null
        pendingReviewDirectory = null
        selectionAdjustments = emptyList()
        edit { it.copy(review = null, pendingSelection = emptyList(), preparing = false, reviewError = null) }
    }

    private fun prepare() {
        val paths = state.value.pendingSelection
        if (paths.isEmpty()) return
        if (!defaultLoaded || !rememberedLoaded || !state.value.inventoryLoaded) {
            edit { it.copy(preparing = !defaultReadFinished || !rememberedReadFinished ||
                (!it.inventoryLoaded && (worker != null || refreshRequested)), reviewError = it.inventoryError ?: it.reviewError) }
            return
        }
        val currentGeneration = ++generation
        val directory = reviewDirectory
        val inventory = state.value.inventory
        val remembered = state.value.rememberedFolders
        val reserved = reservations().map { it.original to it.destination }
        activePreparations++
        edit { it.copy(preparing = true, review = null, reviewError = null) }
        scope.launch {
            try {
                val review = onIo { preparer.prepare(paths, directory, inventory, remembered, reserved) }
                if (currentGeneration == generation) {
                    selectionAdjustments = (selectionAdjustments + review.adjustments).distinct()
                    edit { it.copy(review = review.copy(adjustments = selectionAdjustments), pendingSelection = review.items.map { item -> item.original }) }
                }
            } catch (error: Exception) {
                error.rethrowCancellation()
                if (currentGeneration == generation) edit { it.copy(reviewError = error, closeWhenFinished = false) }
            } finally {
                activePreparations--
                if (currentGeneration == generation) edit { it.copy(preparing = false) }
                maybeExit()
            }
        }
    }

    private fun reserved(): List<ActivityItem> = state.value.activity.filter {
        it.status == ActivityStatus.QUEUED || it.status == ActivityStatus.RUNNING
    }
    private fun reservations(): List<TransferRequest> = reserved().map { it.request } + unrefreshedTransfers

    private fun enqueue(request: TransferRequest) {
        if (indexedConflict(request)) return
        if (reservations().any { existing ->
                pathsOverlap(existing.original, existing.destination, request.original, request.destination)
            }) return
        edit { it.copy(activity = it.activity + ActivityItem(nextId++, request, ActivityStatus.QUEUED), activityExpanded = true) }
    }

    private fun requestRefresh() {
        refreshRequested = true
        if (defaultReadFinished && rememberedReadFinished) ensureWorker()
    }

    private fun ensureWorker() {
        if (worker != null) return
        val job = scope.launch(start = CoroutineStart.LAZY) {
            try {
                while (true) {
                    val next = state.value.activity.firstOrNull { it.status == ActivityStatus.QUEUED }
                    when {
                        next != null -> {
                            transfer(next)
                            refreshRequested = true
                            readInventory()
                        }
                        refreshRequested -> readInventory()
                        else -> break
                    }
                }
            } finally {
                worker = null
                maybeExit()
            }
        }
        worker = job
        job.start()
    }

    private suspend fun transfer(item: ActivityItem) {
        if (indexedConflict(item.request)) {
            updateActivity(item.id) { it.copy(status = ActivityStatus.STOPPED,
                error = IOException("Selection overlaps an indexed promotion; inspect original and destination before proceeding")) }
            edit { it.copy(closeWhenFinished = false, activityExpanded = true) }
            return
        }
        updateActivity(item.id) { it.copy(status = ActivityStatus.RUNNING) }
        try {
            val request = item.request
            when (request.kind) {
                TransferKind.PROMOTE -> operations.promote(request.original, request.directory)
                TransferKind.DEMOTE -> operations.demote(request.directory, request.destination.fileName.toString())
            }
            unrefreshedTransfers = unrefreshedTransfers + request
            updateActivity(item.id) { it.copy(status = ActivityStatus.SUCCEEDED) }
        } catch (error: Exception) {
            error.rethrowCancellation()
            unrefreshedTransfers = unrefreshedTransfers + item.request
            updateActivity(item.id) { it.copy(status = ActivityStatus.FAILED, error = error) }
            edit { it.copy(closeWhenFinished = false, activityExpanded = true) }
            stopAffected(item.request.directory)
        }
    }

    private suspend fun stopAffected(directory: Path) {
        var inspectionError: Exception? = null
        val problem = try {
            onIo { operations.destinationProblem(directory) }
        } catch (error: Exception) {
            error.rethrowCancellation()
            inspectionError = error
            "Cannot inspect destination: ${error.message ?: error.javaClass.simpleName}"
        } ?: return
        val affected = withContext(io) {
            reserved().filter { sameDirectory(directory, it.request.directory) }.map { it.id }.toSet()
        }
        edit { current -> current.copy(activity = current.activity.map {
            if (it.id in affected) it.copy(status = ActivityStatus.STOPPED,
                message = problem, error = IOException("Stopped work for '${it.request.directory}': $problem", inspectionError)) else it
        }) }
    }

    private suspend fun readInventory() {
        refreshRequested = false
        edit { it.copy(refreshing = true) }
        try {
            val inventory = operations.inventory()
            val remembered = state.value.rememberedFolders
            val identities = onIo { remembered.associateWith { directoryIdentity(Path.of(it)) } }
            val attempted = unrefreshedTransfers
            unrefreshedTransfers = onIo {
                attempted.filter { request -> inventory.none {
                    it.status == DestinationStatus.AVAILABLE && sameDirectory(it.directory, request.directory)
                } }
            }
            edit { it.copy(inventory = inventory.toList(), inventoryError = null, inventoryLoaded = true,
                rememberedDirectoryIdentities = if (it.rememberedFolders == remembered) identities else it.rememberedDirectoryIdentities) }
            if (state.value.pendingSelection.isNotEmpty()) prepare()
        } catch (error: Exception) {
            error.rethrowCancellation()
            edit { it.copy(inventoryError = error, closeWhenFinished = false,
                preparing = if (!it.inventoryLoaded) false else it.preparing,
                reviewError = if (!it.inventoryLoaded && it.pendingSelection.isNotEmpty()) error else it.reviewError) }
        } finally {
            edit { it.copy(refreshing = false) }
        }
    }

    private fun updateActivity(id: Long, transform: (ActivityItem) -> ActivityItem) {
        edit { it.copy(activity = it.activity.map { item -> if (item.id == id) transform(item) else item }) }
    }

    private fun indexedConflict(request: TransferRequest): Boolean =
        (request.kind == TransferKind.PROMOTE && state.value.promotions.any {
            pathsOverlap(it.original, it.destination, request.original, request.destination)
        }) || unrefreshedTransfers.any {
            pathsOverlap(it.original, it.destination, request.original, request.destination)
        }

    private fun action(block: suspend () -> Unit) {
        scope.launch {
            block()
            maybeExit()
        }
    }

    private suspend fun <T> onIo(block: suspend () -> T): T = withContext(io) {
        try {
            Result.success(block())
        } catch (error: Exception) {
            error.rethrowCancellation()
            Result.failure<T>(error)
        }
    }.getOrThrow()

    private fun edit(transform: (AppState) -> AppState) { mutableState.value = transform(mutableState.value) }
    private suspend fun resumeSelection() {
        finishSettingsRead()
        if (!defaultLoaded || !rememberedLoaded || state.value.pendingSelection.isEmpty()) return
        val chosen = pendingReviewDirectory
        pendingReviewDirectory = null
        if (chosen != null && state.value.defaultDirectory == null) {
            changeInput(chosen.toString())
            save(firstChoice = true)
        } else if (chosen != null || reviewDirectory == null || (state.value.review == null && !state.value.preparing)) {
            reviewDirectory = chosen ?: reviewDirectory ?: state.value.defaultDirectory
            prepare()
        }
    }
    private fun finishSettingsRead() {
        val finished = defaultReadFinished && rememberedReadFinished
        edit { it.copy(settingsLoading = !finished, preparing = if (finished && (!defaultLoaded || !rememberedLoaded)) false else it.preparing) }
    }
    private fun hasWork(): Boolean = state.value.busy || worker != null || activePreparations > 0
    private fun maybeExit() {
        if (state.value.closeWhenFinished && !hasWork() && state.value.activity.none {
                !it.acknowledged && (it.status == ActivityStatus.FAILED || it.status == ActivityStatus.STOPPED)
            }) edit { it.copy(exitRequested = true) }
    }
}

private fun Exception.rethrowCancellation() { if (this is CancellationException) throw this }
private fun overlaps(first: Path, second: Path): Boolean {
    val left = first.toAbsolutePath().normalize()
    val right = second.toAbsolutePath().normalize()
    return left.startsWith(right) || right.startsWith(left)
}
private fun pathsOverlap(firstOriginal: Path, firstDestination: Path, secondOriginal: Path, secondDestination: Path): Boolean =
    overlaps(firstOriginal, secondOriginal) || overlaps(firstOriginal, secondDestination) ||
        overlaps(firstDestination, secondOriginal) || overlaps(firstDestination, secondDestination)
private fun directoryIdentity(path: Path): Path = try {
    path.toRealPath()
} catch (_: IOException) {
    path.toAbsolutePath().normalize()
} catch (_: SecurityException) {
    path.toAbsolutePath().normalize()
}
private fun sameDirectory(first: Path, second: Path): Boolean =
    first.toAbsolutePath().normalize() == second.toAbsolutePath().normalize() ||
        try { Files.isSameFile(first, second) } catch (_: IOException) { false }
