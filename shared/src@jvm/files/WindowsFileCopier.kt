package com.bkahlert.gametier.files

import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.win32.StdCallLibrary
import java.io.IOException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path

internal object WindowsFileCopier {
    fun supports(path: Path): Boolean = System.getProperty("os.name").startsWith("Windows") &&
        path.fileSystem == FileSystems.getDefault()

    fun size(path: Path): Long = Memory(600).use { data ->
        val handle = api.FindFirstStreamW(nativePath(path), 0, data, 0)
        if (Pointer.nativeValue(handle) == -1L) {
            val error = Native.getLastError()
            if (error == 38) return 0L
            if (error == 87) return Files.size(path)
            throw IOException("Cannot enumerate file streams for '$path' (Windows error $error)")
        }
        try {
            var total = data.getLong(0)
            while (api.FindNextStreamW(handle, data)) total = Math.addExact(total, data.getLong(0))
            val error = Native.getLastError()
            if (error != 38) throw IOException("Cannot enumerate file streams for '$path' (Windows error $error)")
            total
        } finally {
            api.FindClose(handle)
        }
    }

    fun copy(source: Path, destination: Path, onBytes: (Long) -> Unit) {
        var callbackFailure: Throwable? = null
        val callback = CopyProgress { _, transferred, _, _, _, _, _, _, _ ->
            try {
                onBytes(transferred)
                0
            } catch (error: Throwable) {
                callbackFailure = error
                2 // PROGRESS_STOP requests stopping without deleting a partial destination.
            }
        }
        val copied = api.CopyFileExW(nativePath(source), nativePath(destination), callback, null, null, 1)
        val error = Native.getLastError()
        callbackFailure?.let { throw IOException("Copy progress callback failed", it) }
        if (!copied) throw IOException("Cannot copy '$source' to '$destination' (Windows error $error)")
    }

    private fun nativePath(path: Path): WString {
        val absolute = path.toAbsolutePath().normalize().toString()
        return WString(if (absolute.startsWith("\\\\?\\")) absolute
        else if (absolute.startsWith("\\\\")) "\\\\?\\UNC\\" + absolute.drop(2)
        else "\\\\?\\$absolute")
    }

    private val api: CopyApi by lazy { Native.load("kernel32", CopyApi::class.java) }

    internal fun interface CopyProgress : StdCallLibrary.StdCallCallback {
        fun invoke(total: Long, transferred: Long, streamSize: Long, streamTransferred: Long,
                   streamNumber: Int, reason: Int, source: Pointer?, destination: Pointer?, data: Pointer?): Int
    }

    internal interface CopyApi : StdCallLibrary {
        fun CopyFileExW(source: WString, destination: WString, progress: CopyProgress, data: Pointer?, cancel: Pointer?, flags: Int): Boolean
        fun FindFirstStreamW(path: WString, level: Int, data: Pointer, flags: Int): Pointer
        fun FindNextStreamW(handle: Pointer, data: Pointer): Boolean
        fun FindClose(handle: Pointer): Boolean
    }
}
