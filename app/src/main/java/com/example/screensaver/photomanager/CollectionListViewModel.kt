package com.example.screensaver.photomanager

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class CollectionListViewModel @Inject constructor(
    private val photoCollectionManager: PhotoCollectionManager
) : ViewModel() {

    private val _collections = MutableStateFlow<List<PhotoCollectionManager.Collection>>(emptyList())
    val collections: StateFlow<List<PhotoCollectionManager.Collection>> = _collections

    private val _events = MutableSharedFlow<Event>()
    val events: SharedFlow<Event> = _events

    private var deletedCollection: PhotoCollectionManager.Collection? = null

    init {
        loadCollections()
    }

    private fun loadCollections() {
        viewModelScope.launch {
            try {
                _collections.value = photoCollectionManager.getCollections()
            } catch (e: Exception) {
                _events.emit(Event.ShowError(e.message ?: "Error loading collections"))
            }
        }
    }

    fun createCollection(name: String, description: String? = null) {
        if (name.isBlank()) {
            viewModelScope.launch {
                _events.emit(Event.ShowError("Collection name cannot be empty"))
            }
            return
        }

        viewModelScope.launch {
            try {
                val collection = photoCollectionManager.createCollection(name, description)
                _collections.value = photoCollectionManager.getCollections()
                _events.emit(Event.CollectionCreated(collection))
            } catch (e: Exception) {
                _events.emit(Event.ShowError(e.message ?: "Error creating collection"))
            }
        }
    }

    fun deleteCollection(collection: PhotoCollectionManager.Collection) {
        viewModelScope.launch {
            try {
                deletedCollection = collection
                photoCollectionManager.deleteCollection(collection.id)
                _collections.value = photoCollectionManager.getCollections()
                _events.emit(Event.CollectionDeleted(collection))
            } catch (e: Exception) {
                _events.emit(Event.ShowError(e.message ?: "Error deleting collection"))
            }
        }
    }

    fun undoDeleteCollection(collection: PhotoCollectionManager.Collection) {
        viewModelScope.launch {
            try {
                val restoredCollection = photoCollectionManager.createCollection(
                    name = collection.name,
                    description = collection.description
                )
                collection.photoRefs.groupBy { it.source }.forEach { (source, refs) ->
                    photoCollectionManager.addPhotosToCollection(
                        restoredCollection.id,
                        refs.map { it.uri },
                        source
                    )
                }
                _collections.value = photoCollectionManager.getCollections()
            } catch (e: Exception) {
                _events.emit(Event.ShowError(e.message ?: "Error restoring collection"))
            }
        }
    }

    fun selectCollection(collection: PhotoCollectionManager.Collection) {
        // Will be implemented in Step 4
    }

    sealed class Event {
        data class ShowError(val message: String) : Event()
        data class CollectionCreated(val collection: PhotoCollectionManager.Collection) : Event()
        data class CollectionDeleted(val collection: PhotoCollectionManager.Collection) : Event()
    }
}