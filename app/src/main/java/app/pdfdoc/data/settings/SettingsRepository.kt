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
    private val viewModeKey = stringPreferencesKey("view_mode")
    private val authorKey = stringPreferencesKey("author_name")
    private val ocrLanguagesKey = stringPreferencesKey("ocr_languages")
    private val stampsKey = stringPreferencesKey("custom_stamps")
    private val appLockKey = androidx.datastore.preferences.core.booleanPreferencesKey("app_lock")
    private val keepScreenOnKey = androidx.datastore.preferences.core.booleanPreferencesKey("keep_screen_on")
    private val readerSizeKey = androidx.datastore.preferences.core.floatPreferencesKey("reader_font_size")
    private val readerThemeKey = stringPreferencesKey("reader_theme")
    private val readerSerifKey = androidx.datastore.preferences.core.booleanPreferencesKey("reader_serif")
    private val measureKey = stringPreferencesKey("measure_scale")
    private val editorStripKey = androidx.datastore.preferences.core.booleanPreferencesKey("editor_strip")
    private val editorBarKey = stringPreferencesKey("editor_bar")
    private val editorRecentKey = stringPreferencesKey("editor_recent")
    private val editorShowRecentKey = androidx.datastore.preferences.core.booleanPreferencesKey("editor_show_recent")

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

    val viewMode: Flow<app.pdfdoc.domain.model.ViewMode> = context.dataStore.data.map { prefs ->
        prefs[viewModeKey]?.let { runCatching { app.pdfdoc.domain.model.ViewMode.valueOf(it) }.getOrNull() }
            ?: app.pdfdoc.domain.model.ViewMode.CONTINUOUS
    }

    /** Name written into comments, stamps and signatures. */
    val authorName: Flow<String> = context.dataStore.data.map { it[authorKey].orEmpty() }
    suspend fun setAuthorName(name: String) = context.dataStore.edit { it[authorKey] = name.trim() }

    /** Tesseract language codes used for OCR, e.g. "rus+eng". */
    val ocrLanguages: Flow<String> = context.dataStore.data.map { it[ocrLanguagesKey] ?: "rus+eng" }
    suspend fun setOcrLanguages(codes: String) = context.dataStore.edit { it[ocrLanguagesKey] = codes }

    /** User stamps, newest first. */
    val customStamps: Flow<List<String>> = context.dataStore.data.map { prefs ->
        prefs[stampsKey]?.let { raw -> runCatching { org.json.JSONArray(raw).let { a -> List(a.length()) { a.getString(it) } } }.getOrNull() }.orEmpty()
    }
    suspend fun setCustomStamps(stamps: List<String>) = context.dataStore.edit {
        it[stampsKey] = org.json.JSONArray(stamps.distinct().take(MAX_STAMPS)).toString()
    }

    val appLock: Flow<Boolean> = context.dataStore.data.map { it[appLockKey] ?: false }
    suspend fun setAppLock(enabled: Boolean) = context.dataStore.edit { it[appLockKey] = enabled }

    val keepScreenOn: Flow<Boolean> = context.dataStore.data.map { it[keepScreenOnKey] ?: false }
    suspend fun setKeepScreenOn(enabled: Boolean) = context.dataStore.edit { it[keepScreenOnKey] = enabled }

    val readerFontSize: Flow<Float> = context.dataStore.data.map { it[readerSizeKey] ?: 18f }
    val readerTheme: Flow<String> = context.dataStore.data.map { it[readerThemeKey] ?: "LIGHT" }
    val readerSerif: Flow<Boolean> = context.dataStore.data.map { it[readerSerifKey] ?: true }
    suspend fun setReader(size: Float, theme: String, serif: Boolean) = context.dataStore.edit {
        it[readerSizeKey] = size
        it[readerThemeKey] = theme
        it[readerSerifKey] = serif
    }

    /** Editor pages one under another (true) or one at a time. */
    val editorStrip: Flow<Boolean> = context.dataStore.data.map { it[editorStripKey] ?: true }
    suspend fun setEditorStrip(on: Boolean) = context.dataStore.edit { it[editorStripKey] = on }

    /** Editor tool bar as "ITEM,ITEM,-HIDDEN,…": order of the items, hidden ones prefixed with "-". Empty: default. */
    val editorBar: Flow<String> = context.dataStore.data.map { it[editorBarKey].orEmpty() }
    suspend fun setEditorBar(raw: String) = context.dataStore.edit { if (raw.isEmpty()) it.remove(editorBarKey) else it[editorBarKey] = raw }

    /** Recently used editor tools, most recent first, comma separated. */
    val editorRecent: Flow<String> = context.dataStore.data.map { it[editorRecentKey].orEmpty() }
    suspend fun setEditorRecent(raw: String) = context.dataStore.edit { it[editorRecentKey] = raw }
    val editorShowRecent: Flow<Boolean> = context.dataStore.data.map { it[editorShowRecentKey] ?: true }
    suspend fun setEditorShowRecent(on: Boolean) = context.dataStore.edit { it[editorShowRecentKey] = on }

    /** Last measuring scale as "value|unit": [value] units per centimeter on paper. */
    val measureScale: Flow<String> = context.dataStore.data.map { it[measureKey] ?: "1|cm" }
    suspend fun setMeasureScale(scale: String) = context.dataStore.edit { it[measureKey] = scale }

    suspend fun setViewMode(mode: app.pdfdoc.domain.model.ViewMode) = context.dataStore.edit { it[viewModeKey] = mode.name }

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

    private companion object {
        const val MAX_STAMPS = 20
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[themeKey] = mode.name }
    }

    suspend fun setSortOrder(order: SortOrder) {
        context.dataStore.edit { it[sortKey] = order.name }
    }
}
