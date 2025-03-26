package com.photostreamr.music

import android.content.Context
import android.content.SharedPreferences
import androidx.preference.PreferenceManager
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import com.example.screensaver.data.SecureStorage
import org.json.JSONArray
import org.json.JSONObject

@Singleton
class RadioPreferences @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureStorage: SecureStorage
) {
    companion object {
        private const val PREF_RADIO_ENABLED = "radio_enabled"
        private const val PREF_LAST_STATION = "radio_last_station"
        private const val PREF_FAVORITE_STATIONS = "radio_favorite_stations"
        private const val PREF_RECENT_STATIONS = "radio_recent_stations"
        private const val MAX_RECENT_STATIONS = 10
    }

    private val preferences: SharedPreferences by lazy {
        PreferenceManager.getDefaultSharedPreferences(context)
    }

    fun isEnabled(): Boolean =
        preferences.getBoolean(PREF_RADIO_ENABLED, false)

    fun setEnabled(enabled: Boolean) {
        preferences.edit()
            .putBoolean(PREF_RADIO_ENABLED, enabled)
            .apply()
    }

    fun setWasPlaying(wasPlaying: Boolean) {
        preferences.edit()
            .putBoolean("radio_was_playing", wasPlaying)
            .apply()
    }

    fun wasPlaying(): Boolean =
        preferences.getBoolean("radio_was_playing", false)

    fun getLastStation(): RadioManager.RadioStation? {
        return secureStorage.getSecurely(PREF_LAST_STATION)?.let { json ->
            try {
                JSONObject(json).let { obj ->
                    RadioManager.RadioStation(
                        id = obj.getString("id"),
                        name = obj.getString("name"),
                        url = obj.getString("url"),
                        genre = obj.optString("genre").takeIf { it.isNotEmpty() },
                        country = obj.optString("country").takeIf { it.isNotEmpty() },
                        favicon = obj.optString("favicon").takeIf { it.isNotEmpty() }
                    )
                }
            } catch (e: Exception) {
                null
            }
        }
    }

    fun setLastStation(station: RadioManager.RadioStation?) {
        if (station != null) {
            val json = JSONObject().apply {
                put("id", station.id)
                put("name", station.name)
                put("url", station.url)
                station.genre?.let { put("genre", it) }
                station.country?.let { put("country", it) }
                station.favicon?.let { put("favicon", it) }
            }.toString()
            preferences.edit()
                .putString(PREF_LAST_STATION, json)
                .apply()
        } else {
            preferences.edit()
                .remove(PREF_LAST_STATION)
                .apply()
        }
    }

    fun getRecentStations(): List<RadioManager.RadioStation> {
        return secureStorage.getSecurely(PREF_RECENT_STATIONS)?.let { json ->
            try {
                JSONArray(json).let { array ->
                    List(array.length()) { i ->
                        val obj = array.getJSONObject(i)
                        RadioManager.RadioStation(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            url = obj.getString("url"),
                            genre = obj.optString("genre").takeIf { it.isNotEmpty() },
                            country = obj.optString("country").takeIf { it.isNotEmpty() },
                            favicon = obj.optString("favicon").takeIf { it.isNotEmpty() }
                        )
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }
        } ?: emptyList()
    }

    fun addToRecentStations(station: RadioManager.RadioStation) {
        val recent = getRecentStations().toMutableList()
        // Remove if already exists
        recent.removeAll { it.id == station.id }
        // Add to beginning
        recent.add(0, station)
        // Keep only last MAX_RECENT_STATIONS
        while (recent.size > MAX_RECENT_STATIONS) {
            recent.removeAt(recent.size - 1)
        }

        val json = JSONArray().apply {
            recent.forEach { station ->
                put(JSONObject().apply {
                    put("id", station.id)
                    put("name", station.name)
                    put("url", station.url)
                    station.genre?.let { put("genre", it) }
                    station.country?.let { put("country", it) }
                    station.favicon?.let { put("favicon", it) }
                })
            }
        }.toString()
        secureStorage.saveSecurely(PREF_RECENT_STATIONS, json)
    }

    fun getFavoriteStations(): List<RadioManager.RadioStation> {
        return secureStorage.getSecurely(PREF_FAVORITE_STATIONS)?.let { json ->
            try {
                JSONArray(json).let { array ->
                    List(array.length()) { i ->
                        val obj = array.getJSONObject(i)
                        RadioManager.RadioStation(
                            id = obj.getString("id"),
                            name = obj.getString("name"),
                            url = obj.getString("url"),
                            genre = obj.optString("genre").takeIf { it.isNotEmpty() },
                            country = obj.optString("country").takeIf { it.isNotEmpty() },
                            favicon = obj.optString("favicon").takeIf { it.isNotEmpty() }
                        )
                    }
                }
            } catch (e: Exception) {
                emptyList()
            }
        } ?: emptyList()
    }

    fun saveFavoriteStations(stations: List<RadioManager.RadioStation>) {
        val json = JSONArray().apply {
            stations.forEach { station ->
                put(JSONObject().apply {
                    put("id", station.id)
                    put("name", station.name)
                    put("url", station.url)
                    station.genre?.let { put("genre", it) }
                    station.country?.let { put("country", it) }
                    station.favicon?.let { put("favicon", it) }
                })
            }
        }.toString()
        secureStorage.saveSecurely(PREF_FAVORITE_STATIONS, json)
    }
}