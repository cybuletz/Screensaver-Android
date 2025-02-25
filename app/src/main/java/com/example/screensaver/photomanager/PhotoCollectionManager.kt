package com.example.screensaver.photomanager

import android.content.Context
import androidx.preference.PreferenceManager
import com.example.screensaver.lock.LockScreenPhotoManager
import com.example.screensaver.utils.AppPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PhotoCollectionManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val lockScreenPhotoManager: LockScreenPhotoManager,
    private val preferences: AppPreferences
) {
    private val gson = Gson()
    private val _collections = MutableStateFlow<List<Collection>>(emptyList())
    val collections: StateFlow<List<Collection>> = _collections

    companion object {
        private const val PREF_COLLECTIONS = "photo_collections"
    }

    data class Collection(
        val id: String = UUID.randomUUID().toString(),
        val name: String,
        val description: String?,
        val created: Long = System.currentTimeMillis(),
        val modified: Long = System.currentTimeMillis(),
        val photoRefs: List<PhotoRef> = emptyList()
    )

    data class PhotoRef(
        val uri: String,
        val source: PhotoSource,
        val addedAt: Long = System.currentTimeMillis()
    )

    enum class PhotoSource {
        LOCAL,
        GOOGLE_PHOTOS_PICKER
    }

    init {
        loadCollections()
    }

    private fun loadCollections() {
        try {
            val json = PreferenceManager.getDefaultSharedPreferences(context)
                .getString(PREF_COLLECTIONS, "[]")
            val type = object : TypeToken<List<Collection>>() {}.type
            _collections.value = gson.fromJson(json, type) ?: emptyList()
            Timber.d("Loaded ${_collections.value.size} collections")
        } catch (e: Exception) {
            Timber.e(e, "Failed to load collections")
            _collections.value = emptyList()
        }
    }

    private suspend fun saveCollections() = withContext(Dispatchers.IO) {
        try {
            val json = gson.toJson(_collections.value)
            PreferenceManager.getDefaultSharedPreferences(context)
                .edit()
                .putString(PREF_COLLECTIONS, json)
                .apply()
            Timber.d("Saved ${_collections.value.size} collections")
        } catch (e: Exception) {
            Timber.e(e, "Failed to save collections")
        }
    }

    suspend fun createCollection(name: String, description: String? = null): Collection {
        return Collection(
            name = name,
            description = description
        ).also { collection ->
            _collections.value = _collections.value + collection
            saveCollections()
        }
    }

    suspend fun addPhotosToCollection(collectionId: String, uris: List<String>, source: PhotoSource) {
        val currentCollections = _collections.value
        val collectionIndex = currentCollections.indexOfFirst { it.id == collectionId }

        if (collectionIndex != -1) {
            val collection = currentCollections[collectionIndex]
            val newRefs = uris.map { PhotoRef(it, source) }
            val updatedCollection = collection.copy(
                photoRefs = (collection.photoRefs + newRefs).distinctBy { it.uri },
                modified = System.currentTimeMillis()
            )

            _collections.value = currentCollections.toMutableList().apply {
                set(collectionIndex, updatedCollection)
            }
            saveCollections()
        }
    }

    suspend fun removePhotosFromCollection(collectionId: String, uris: List<String>) {
        val currentCollections = _collections.value
        val collectionIndex = currentCollections.indexOfFirst { it.id == collectionId }

        if (collectionIndex != -1) {
            val collection = currentCollections[collectionIndex]
            val updatedCollection = collection.copy(
                photoRefs = collection.photoRefs.filterNot { it.uri in uris },
                modified = System.currentTimeMillis()
            )

            _collections.value = currentCollections.toMutableList().apply {
                set(collectionIndex, updatedCollection)
            }
            saveCollections()
        }
    }

    suspend fun deleteCollection(collectionId: String) {
        _collections.value = _collections.value.filterNot { it.id == collectionId }
        saveCollections()
    }

    suspend fun getCollections(): List<Collection> = _collections.value

    suspend fun getCollection(id: String): Collection? {
        return _collections.value.find { it.id == id }
    }
}