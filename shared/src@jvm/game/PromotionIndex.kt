package com.bkahlert.gametier.game

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.attribute.DosFileAttributeView
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

enum class TransferState { PROMOTING, PROMOTED, DEMOTING }

internal data class IndexedPromotion(val name: String, val origin: Path, val state: TransferState)

internal const val PROMOTION_INDEX_NAME = ".gametier.json"

internal class PromotionIndex(
    private val replace: (Path, Path) -> Unit = ::replacePromotionIndex,
) {
    fun read(directory: Path): List<IndexedPromotion> {
        val file = directory.resolve(PROMOTION_INDEX_NAME)
        try {
            if (!isRealGameEntry(file) || !Files.isRegularFile(file, NOFOLLOW_LINKS)) {
                // Read attributes to preserve missing/access-denied errors instead of treating them as an empty index.
                Files.readAttributes(file, java.nio.file.attribute.BasicFileAttributes::class.java, NOFOLLOW_LINKS)
                throw IOException("Promotion index must be a regular file")
            }
            val text = Files.readString(file)
            val document = Json.parseToJsonElement(text) as? JsonObject
                ?: throw IOException("Promotion index must be an object")
            rejectDuplicateProperties(text)
            require(document.keys == setOf("version", "promotions")) { "Unexpected index fields" }
            require((document["version"] as? JsonPrimitive)?.let { !it.isString && it.intOrNull == 1 } == true) {
                "Unsupported promotion index version"
            }
            val entries = document["promotions"] as? JsonArray ?: error("Missing promotions array")
            return entries.map { element ->
                val entry = element as? JsonObject ?: error("Promotion must be an object")
                require(entry.keys == setOf("destination", "origin", "state")) { "Unexpected promotion fields" }
                IndexedPromotion(
                    entry.string("destination"), directory.fileSystem.getPath(entry.string("origin")),
                    TransferState.valueOf(entry.string("state")),
                )
            }.also { validate(directory, it) }
        } catch (error: java.nio.file.NoSuchFileException) {
            throw error
        } catch (error: Exception) {
            throw IOException("Cannot read promotion index '$file': ${error.message}", error)
        }
    }

    fun write(directory: Path, records: List<IndexedPromotion>) {
        val file = directory.resolve(PROMOTION_INDEX_NAME)
        validate(directory, records)
        if (gameEntryExists(file) && (!isRealGameEntry(file) || !Files.isRegularFile(file, NOFOLLOW_LINKS))) {
            throw IOException("Promotion index must be a regular file: $file")
        }
        val document = buildJsonObject {
            put("version", 1)
            put("promotions", JsonArray(records.map { record ->
                buildJsonObject {
                    put("destination", record.name)
                    put("origin", record.origin.toString())
                    put("state", record.state.name)
                }
            }))
        }
        val temporary = Files.createTempFile(directory, ".gametier-index-", ".tmp")
        try {
            FileChannel.open(temporary, WRITE).use { channel ->
                val bytes = ByteBuffer.wrap(document.toString().toByteArray(Charsets.UTF_8))
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            Files.getFileAttributeView(temporary, DosFileAttributeView::class.java)?.setHidden(true)
            replace(temporary, file)
        } catch (error: Exception) {
            val failure = IOException("Cannot save promotion index '$file': ${error.message}", error)
            try { Files.deleteIfExists(temporary) } catch (cleanup: Exception) { failure.addSuppressed(cleanup) }
            throw failure
        }
    }

    private fun validate(directory: Path, records: List<IndexedPromotion>) {
        try {
            val destinations = records.map { record ->
                val name = directory.fileSystem.getPath(record.name)
                require(record.name.isNotBlank() && !name.isAbsolute && name.nameCount == 1 &&
                    record.name != "." && record.name != ".." && name != directory.fileSystem.getPath(PROMOTION_INDEX_NAME) &&
                    !record.name.endsWith('.') && !record.name.endsWith(' ') &&
                    !record.name.contains(':') && !record.name.contains('/') && !record.name.contains('\\')) {
                    "Destination must be a direct child other than the index"
                }
                require(record.origin.isAbsolute) { "Origin must be absolute" }
                require(record.origin.fileName != null) { "Origin must not be a filesystem root" }
                require(record.origin.normalize() != directory.resolve(record.name).toAbsolutePath().normalize()) {
                    "Origin must differ from destination"
                }
                name
            }
            require(destinations.distinct().size == destinations.size) { "Duplicate promotion destinations" }
            require(records.map { it.origin.normalize() }.distinct().size == records.size) { "Duplicate promotion origins" }
        } catch (error: IllegalArgumentException) {
            throw IOException("Invalid promotion index: ${error.message}", error)
        }
    }
}

private fun JsonObject.string(name: String): String =
    (get(name) as? JsonPrimitive)?.takeIf { it.isString }?.content ?: error("Missing string '$name'")

internal fun replacePromotionIndex(temporary: Path, file: Path) {
    Files.move(temporary, file, ATOMIC_MOVE, REPLACE_EXISTING)
}

private val jsonObjectTokens = Regex("\"(?:\\\\.|[^\"\\\\])*+\"|[{}]")

internal fun rejectDuplicateProperties(validJson: String) {
    val objects = ArrayDeque<MutableSet<String>>()
    for (token in jsonObjectTokens.findAll(validJson)) {
        when (token.value) {
            "{" -> objects.addLast(mutableSetOf())
            "}" -> objects.removeLast()
            else -> {
                var next = token.range.last + 1
                while (next < validJson.length && validJson[next].isWhitespace()) next++
                if (next < validJson.length && validJson[next] == ':') {
                    val key = (Json.parseToJsonElement(token.value) as JsonPrimitive).content
                    require(objects.last().add(key)) { "Duplicate index property '$key'" }
                }
            }
        }
    }
}
