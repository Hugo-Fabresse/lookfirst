package com.lookfirst.data.reputation

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException

/** `open(dir, O_RDONLY)` + `fsync` + `close`: the only way to make a rename durable on Android (API 21+). */
internal object AndroidDirectorySync : DirectorySync {
    override fun sync(directory: File) {
        try {
            val fd = Os.open(directory.path, OsConstants.O_RDONLY, 0)
            try {
                Os.fsync(fd)
            } finally {
                Os.close(fd)
            }
        } catch (@Suppress("SwallowedException") ignored: ErrnoException) {
            throw IOException("cannot sync directory")
        }
    }
}
