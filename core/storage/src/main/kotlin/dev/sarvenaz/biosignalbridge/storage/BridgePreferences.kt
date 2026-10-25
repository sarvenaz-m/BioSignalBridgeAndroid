package dev.sarvenaz.biosignalbridge.storage

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.bridgePreferences by preferencesDataStore(name = "bridge_display")

data class DisplayPreferences(
    val windowSeconds: Int = 10,
    val showGrid: Boolean = true,
    val showMarkers: Boolean = true
)

/** Only display settings are kept here. Measurements stay in the session database. */
class BridgePreferences(context: Context) {
    private val store = context.applicationContext.bridgePreferences
    val values: Flow<DisplayPreferences> = store.data.catch { error ->
        if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
    }.map { prefs ->
        DisplayPreferences(
            windowSeconds = (prefs[WINDOW] ?: 10).coerceIn(2, 60),
            showGrid = prefs[GRID] ?: true,
            showMarkers = prefs[MARKERS] ?: true
        )
    }

    suspend fun update(value: DisplayPreferences) {
        require(value.windowSeconds in 2..60) { "Choose a time window between 2 and 60 seconds" }
        store.edit { prefs ->
            prefs[WINDOW] = value.windowSeconds
            prefs[GRID] = value.showGrid
            prefs[MARKERS] = value.showMarkers
        }
    }

    private companion object {
        val WINDOW = intPreferencesKey("window_seconds")
        val GRID = booleanPreferencesKey("show_grid")
        val MARKERS = booleanPreferencesKey("show_markers")
    }
}
