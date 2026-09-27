package com.supervideo.core.util

import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    prettyPrint = true
}

// Writers of the same file share one `.tmp` path, so writes are serialized per file.
private val writeLocks = ConcurrentHashMap<String, Any>()

/** Writes [text] to a sibling `.tmp` file, syncs it and replaces [this] by rename. */
fun File.writeTextAtomically(text: String) {
    val lock = writeLocks.computeIfAbsent(absolutePath) { Any() }
    synchronized(lock) {
        parentFile?.mkdirs()
        val tmp = File(parentFile, "$name.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        tmp.moveTo(this)
    }
}

/**
 * Replaces [target] with [this]. Uses File.renameTo (java.nio.file needs Android API 26);
 * Windows won't rename over an existing file, so the target is deleted and the rename retried,
 * then falls back to copy + delete across filesystems.
 */
fun File.moveTo(target: File) {
    target.parentFile?.mkdirs()
    if (renameTo(target)) return
    if (target.exists() && !target.delete()) throw IOException("cannot replace $target")
    if (renameTo(target)) return
    copyTo(target, overwrite = true)
    if (!delete()) throw IOException("cannot delete $this after copying to $target")
}
