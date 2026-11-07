package com.bkahlert.gametier.settings

import com.bkahlert.gametier.files.packagedApplicationPath
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import java.io.IOException
import java.nio.file.Files

interface ExplorerIntegration {
    fun isEnabled(): Boolean
    fun setEnabled(enabled: Boolean)
}

class WindowsExplorerIntegration internal constructor(private val key: String) : ExplorerIntegration {
    override fun isEnabled(): Boolean = Memory(4).use { data ->
        val size = IntByReference(4)
        val result = api.RegGetValueW(currentUser, WString(key), value, 0x10, null, data, size)
        when (result) {
            2 -> true
            0 -> when (data.getInt(0)) {
                0 -> false
                1 -> true
                else -> throw IOException("Explorer integration preference must be enabled or disabled")
            }
            else -> throw failure(result)
        }
    }

    override fun setEnabled(enabled: Boolean) {
        val handle = PointerByReference()
        val created = api.RegCreateKeyExW(currentUser, WString(key), 0, null, 0, 0x2, null, handle, null)
        if (created != 0) throw failure(created)
        try {
            Memory(4).use { data ->
                data.setInt(0, if (enabled) 1 else 0)
                val result = api.RegSetValueExW(handle.value, value, 0, 4, data, 4)
                if (result != 0) throw failure(result)
            }
        } finally { api.RegCloseKey(handle.value) }
    }

    private fun failure(code: Int) = IOException("Explorer integration preference could not be read or saved (Windows error $code)")

    companion object {
        fun installed(): ExplorerIntegration? {
            if (!System.getProperty("os.name").startsWith("Windows")) return null
            val executable = packagedApplicationPath() ?: return null
            if (!Files.isRegularFile(executable.resolveSibling("GameTierExplorer.dll"))) return null
            return WindowsExplorerIntegration("Software\\GameTier")
        }

        private val currentUser = Pointer.createConstant(-2147483647L)
        private val value = WString("ExplorerIntegrationEnabled")
        private val api: RegistryApi by lazy { Native.load("advapi32", RegistryApi::class.java) }
    }

    internal interface RegistryApi : StdCallLibrary {
        fun RegGetValueW(key: Pointer, subKey: WString, value: WString, flags: Int, type: IntByReference?, data: Pointer, size: IntByReference): Int
        fun RegCreateKeyExW(key: Pointer, subKey: WString, reserved: Int, keyClass: WString?, options: Int, access: Int,
            security: Pointer?, result: PointerByReference, disposition: IntByReference?): Int
        fun RegSetValueExW(key: Pointer, value: WString, reserved: Int, type: Int, data: Pointer, size: Int): Int
        fun RegCloseKey(key: Pointer): Int
    }
}
