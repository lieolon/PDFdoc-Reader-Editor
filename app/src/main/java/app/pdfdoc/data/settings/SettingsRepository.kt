package app.pdfdoc.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import app.pdfdoc.domain.tools.CompressLevel
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.pdfdoc.domain.model.SortOrder
import app.pdfdoc.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val themeKey = stringPreferencesKey("theme_mode")
    private val sortKey = stringPreferencesKey("sort_order")
    private val dpiKey = intPreferencesKey("export_dpi")
    private val jpegKey = intPreferencesKey("jpeg_quality")
    private val compressKey = stringPreferencesKey("compress_level")
    private val folderKey = stringPreferencesKey("default_folder")

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        prefs[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    val sortOrder: Flow<SortOrder> = context.dataStore.data.map { prefs ->
        prefs[sortKey]?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() } ?: SortOrder.DATE
    }

    /** Resolution of exported page images. */
    val exportDpi: Flow<Int> = context.dataStore.data.map { it[dpiKey] ?: 150 }

    val jpegQuality: Flow<Int> = context.dataStore.data.map { it[jpegKey] ?: 90 }

    val compressLevel: Flow<CompressLevel> = context.dataStore.data.map { prefs ->
        prefs[compressKey]?.let { runCatching { CompressLevel.valueOf(it) }.getOrNull() } ?: CompressLevel.MEDIUM
    }

    /** Folder (SAF tree URI) suggested first when saving files. */
    val defaultFolder: Flow<String?> = context.dataStore.data.map { it[folderKey] }

    suspend fun setExportDpi(dpi: Int) = context.dataStore.edit { it[dpiKey] = dpi }
    suspend fun setJpegQuality(q: Int) = context.dataStore.edit { it[jpegKey] = q }
    suspend fun setCompressLevel(level: CompressLevel) = context.dataStore.edit { it[compressKey] = level.name }
    suspend fun setDefaultFolder(uri: String?) = context.dataStore.edit { if (uri == null) it.remove(folderKey) else it[folderKey] = uri }

    /** Settings as JSON for backup; signatures and documents are never included. */
    suspend fun exportJson(language: String): String {
        val prefs = context.dataStore.data.first()
        return org.json.JSONObject().apply {
            put("version", 1)
            prefs[themeKey]?.let { put("theme", it) }
            prefs[sortKey]?.let { put("sort", it) }
            prefs[dpiKey]?.let { put("dpi", it) }
            prefs[jpegKey]?.let { put("jpeg", it) }
            prefs[compressKey]?.let { put("compress", it) }
            put("language", language)
        }.toString(2)
    }

    /** Restores settings from [exportJson] output; returns the language tag to apply, or null if invalid. */
    suspend fun importJson(json: String): String? {
        val o = runCatching { org.json.JSONObject(json) }.getOrNull() ?: return null
        if (!o.has("version")) return null
        context.dataStore.edit { prefs ->
            o.optString("theme").takeIf { v -> ThemeMode.entries.any { it.name == v } }?.let { prefs[themeKey] = it }
            o.optString("sort").takeIf { v -> SortOrder.entries.any { it.name == v } }?.let { prefs[sortKey] = it }
            o.optInt("dpi", 0).takeIf { it in 72..600 }?.let { prefs[dpiKey] = it }
            o.optInt("jpeg", 0).takeIf { it in 30..100 }?.let { prefs[jpegKey] = it }
            o.optString("compress").takeIf { v -> CompressLevel.entries.any { it.name == v } }?.let { prefs[compressKey] = it }
        }
        return o.optString("language", "")
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[themeKey] = mode.name }
    }

    suspend fun setSortOrder(order: SortOrder) {
        context.dataStore.edit { it[sortKey] = order.name }
    }
}
