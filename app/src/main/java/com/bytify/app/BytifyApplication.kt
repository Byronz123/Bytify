package com.bytify.app

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.bytify.app.data.youtube.NewPipeDownloader
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class BytifyApplication : Application(), ImageLoaderFactory {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        NewPipe.init(
            NewPipeDownloader(container.okHttp),
            Localization("id", "ID"),
            ContentCountry("ID"),
        )
    }

    /** Satu loader gambar: cache memori + disk, koneksi berbagi dengan OkHttp. */
    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .okHttpClient(container.okHttp)
        .crossfade(true)
        .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.3).build() }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("images"))
                .maxSizeBytes(100L * 1024 * 1024)
                .build()
        }
        .respectCacheHeaders(false)
        .build()
}
