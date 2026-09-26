package com.supervideo.core.upscale

/** Platform-specific loading of the realesrgan native library (and MNN backends on Android). */
fun interface NativeLibraryLoader {
    fun load()
}

object NativeLibraries {
    @Volatile
    private var loaded = false

    /** Loads the native libraries once per process; later calls are no-ops. */
    fun ensureLoaded(loader: NativeLibraryLoader) {
        if (loaded) return
        synchronized(this) {
            if (!loaded) {
                loader.load()
                loaded = true
            }
        }
    }
}
