package com.moodify.app

import android.app.Application
import android.net.Uri
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.intercept.Interceptor
import coil.request.ImageResult
import coil.size.pxOrElse
import com.moodify.app.data.ArtworkUrls

@androidx.media3.common.util.UnstableApi
class MoodifyApplication : Application(), ImageLoaderFactory {

    override fun onCreate() {
        super.onCreate()
        AppGraph.init(this)
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .components { add(ArtworkSizeInterceptor()) }
        .crossfade(true)
        .respectCacheHeaders(false)
        .build()
}

/**
 * Asks Google's image CDNs for artwork at the size Coil is about to draw, so
 * a 48dp row thumbnail and a full-width player cover each get a sharp image
 * without every call site having to know about URL formats.
 */
private class ArtworkSizeInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        val url = when (val data = request.data) {
            is String -> data
            is Uri -> data.toString()
            else -> return chain.proceed(request)
        }
        val px = maxOf(
            chain.size.width.pxOrElse { 0 },
            chain.size.height.pxOrElse { 0 },
        ).takeIf { it > 0 } ?: DEFAULT_PX
        val sized = ArtworkUrls.sized(url, px)
        if (sized == url) return chain.proceed(request)
        return chain.proceed(request.newBuilder().data(sized).build())
    }

    private companion object {
        const val DEFAULT_PX = 544
    }
}
