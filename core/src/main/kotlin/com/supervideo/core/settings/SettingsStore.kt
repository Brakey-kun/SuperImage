package com.supervideo.core.settings

import com.supervideo.core.util.AppJson
import com.supervideo.core.util.writeTextAtomically
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * App settings persisted as JSON at `<dataDir>/settings.json`.
 * A missing or corrupt file yields [defaults] (platform-specific, e.g. a faster encoder preset on Android).
 */
class SettingsStore(dataDir: File, private val defaults: AppSettings = AppSettings()) {
    private val file = File(dataDir, "settings.json")
    private val state = MutableStateFlow(load())

    val settings: StateFlow<AppSettings> = state.asStateFlow()

    @Synchronized
    fun update(transform: (AppSettings) -> AppSettings) {
        val next = transform(state.value)
        file.writeTextAtomically(AppJson.encodeToString(AppSettings.serializer(), next))
        state.value = next
    }

    private fun load(): AppSettings = try {
        if (file.isFile) AppJson.decodeFromString(AppSettings.serializer(), file.readText()) else defaults
    } catch (e: Exception) {
        defaults
    }
}
