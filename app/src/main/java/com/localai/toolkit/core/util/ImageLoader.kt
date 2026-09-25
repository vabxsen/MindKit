package com.localai.toolkit.core.util

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import com.localai.toolkit.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/** Loading is separate from screen state so cancellation and late results can be tested. */
fun interface ImageLoader {
    suspend fun load(uri: Uri, maxDimension: Int): Bitmap?
}

class ContentImageLoader @Inject constructor(
    @param:ApplicationContext private val context: Context,
    @param:IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ImageLoader {
    override suspend fun load(uri: Uri, maxDimension: Int): Bitmap? = withContext(ioDispatcher) {
        context.decodeDownsampledBitmap(uri, maxDimension)
    }
}
