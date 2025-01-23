    // File: app/src/main/java/com/example/screensaver/lock/PhotoManager.kt
    package com.example.screensaver.lock

    import android.content.Context
    import android.content.SharedPreferences
    import androidx.preference.PreferenceManager
    import com.bumptech.glide.Glide
    import com.bumptech.glide.load.engine.DiskCacheStrategy
    import kotlinx.coroutines.CoroutineScope
    import kotlinx.coroutines.Dispatchers
    import kotlinx.coroutines.launch

    class PhotoManager private constructor(private val context: Context) {
        private var photos = mutableListOf<String>()
        private val preferences: SharedPreferences = PreferenceManager.getDefaultSharedPreferences(context)
        private val scope = CoroutineScope(Dispatchers.IO)

        init {
            loadPhotos()
        }

        companion object {
            @Volatile
            private var instance: PhotoManager? = null

            fun getInstance(context: Context): PhotoManager {
                return instance ?: synchronized(this) {
                    instance ?: PhotoManager(context).also { instance = it }
                }
            }
        }

        fun getPhotoCount(): Int = photos.size

        fun getPhotoUrl(index: Int): String? {
            return if (photos.isNotEmpty()) {
                photos[index % photos.size]
            } else null
        }

        fun preloadNextPhoto(index: Int) {
            getPhotoUrl(index)?.let { url ->
                scope.launch {
                    try {
                        Glide.with(context)
                            .load(url)
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .preload()
                    } catch (e: Exception) {
                        // Handle preloading error
                    }
                }
            }
        }

        fun loadPhotos() {
            scope.launch {
                // Get photos from your existing PhotosManager
                val photosManager = com.example.screensaver.utils.PhotoManager.getInstance(context)

                // Get the photos based on selected albums
                val selectedAlbums = context.getSharedPreferences("screensaver_prefs", Context.MODE_PRIVATE)
                    .getStringSet("selected_albums", emptySet()) ?: emptySet()

                if (selectedAlbums.isNotEmpty()) {
                    // Use the existing photos from your PhotosManager
                    photos = photosManager.getPhotoUrls().toMutableList()
                    photos.shuffle() // Randomize the order
                }
            }
        }

        fun addPhoto(url: String) {
            photos.add(url)
        }

        fun clearPhotos() {
            photos.clear()
        }
    }