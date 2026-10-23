package app.pdfdoc.data.settings

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.pdfdoc.domain.model.SortOrder
import app.pdfdoc.domain.model.ThemeMode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val themeKey = stringPreferencesKey("theme_mode")
    private val sortKey = stringPreferencesKey("sort_order")

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        prefs[themeKey]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: ThemeMode.SYSTEM
    }

    val sortOrder: Flow<SortOrder> = context.dataStore.data.map { prefs ->
        prefs[sortKey]?.let { runCatching { SortOrder.valueOf(it) }.getOrNull() } ?: SortOrder.DATE
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[themeKey] = mode.name }
    }

    suspend fun setSortOrder(order: SortOrder) {
        context.dataStore.edit { it[sortKey] = order.name }
    }
}
