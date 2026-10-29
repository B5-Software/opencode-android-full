package io.github.opencodeandroid.backup

import android.system.Os
import android.system.OsConstants
import io.github.opencodeandroid.runtime.RuntimeCoordination
import io.github.opencodeandroid.runtime.RuntimePaths
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream

internal object WorkstationBackupFiles {
    fun write(paths: RuntimePaths, file: File, password: CharArray, lease: RuntimeCoordination.MaintenanceLease,
              settings: JSONObject, progress: (Long) -> Unit = {}) {
        val fd = Os.open(file.path, OsConstants.O_WRONLY or OsConstants.O_CREAT or OsConstants.O_EXCL or OsConstants.O_NOFOLLOW or OsConstants.O_CLOEXEC, 384)
        try {
            FileOutputStream(fd).use { output ->
                val managed = object : FilterOutputStream(output) {
                    // FilterOutputStream's default bulk method would write each ciphertext byte separately.
                    override fun write(bytes: ByteArray, offset: Int, length: Int) { out.write(bytes, offset, length) }
                    override fun close() { flush() }
                }
                WorkstationArchive.write(paths, managed, password, lease, settings, progress)
                output.fd.sync()
            }
        } finally { Os.close(fd) } // Android's FileOutputStream(FileDescriptor) does not own this descriptor.
    }
}
