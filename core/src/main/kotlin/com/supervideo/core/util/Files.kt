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

/** Replaces [target] with [this]: atomic when the filesystem allows it, copy + delete across filesystems. */
fun File.moveTo(target: File) {
    target.parentFile?.mkdirs()
    try {
        NioMove.move(this, target)
    } catch (e: NoClassDefFoundError) {
        // java.nio.file is missing below Android API 26: rename is atomic on Android's filesystems.
        legacyMove(target)
    }
}

/** Isolated so that java.nio.file classes are only resolved when this is called. */
private object NioMove {
    fun move(source: File, target: File) {
        val from = source.toPath()
        val to = target.toPath()
        try {
            java.nio.file.Files.move(
                from, to,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (e: IOException) {
            // AtomicMoveNotSupportedException (e.g. across filesystems) and similar: plain replace.
            java.nio.file.Files.move(from, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        }
    }
}

private fun File.legacyMove(target: File) {
    if (renameTo(target)) return
    if (target.exists() && !target.delete()) throw IOException("cannot replace $target")
    if (renameTo(target)) return
    copyTo(target, overwrite = true)
    if (!delete()) throw IOException("cannot delete $this after copying to $target")
}
