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
            // Initialize with some default photos or load from preferences
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
            return if (photos.isNotEmpty() && index < photos.size) {
                photos[index]
            } else {
                // Return a default photo URL or null
                null
            }
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
                // For now, let's use a dummy list of photos
                // Replace this with your actual photo loading logic
                photos = mutableListOf(
                    "https://picsum.photos/1080/1920",
                    "https://picsum.photos/1080/1920?random=1",
                    "https://picsum.photos/1080/1920?random=2",
                    "https://picsum.photos/1080/1920?random=3",
                    "https://picsum.photos/1080/1920?random=4"
                )
                photos.shuffle() // Randomize the order
            }
        }

        fun addPhoto(url: String) {
            photos.add(url)
        }

        fun clearPhotos() {
            photos.clear()
        }
    }