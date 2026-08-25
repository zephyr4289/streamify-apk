package com.streamify.app.util.newpipe

import com.streamify.app.data.network.NetworkEngine
import com.streamify.app.util.SLog
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import java.util.Locale

/**
 * Lazy NewPipe Extractor bootstrap. Safe to call from any thread; the first
 * caller initializes, everyone else no-ops.
 */
object NewPipeBootstrap {

    @Volatile
    private var initialized = false

    fun ensure() {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            SLog.i(TAG, "Initializing NewPipe extractor")
            NewPipe.init(
                NewPipeDownloaderImpl(NetworkEngine.client.newBuilder()),
                Localization(Locale.getDefault().language.ifBlank { "en" }, Locale.getDefault().country.ifBlank { "US" }),
                ContentCountry("US")
            )
            YoutubeStreamExtractor.setPoTokenProvider(StreamifyPoTokenGenerator())
            initialized = true
            SLog.i(TAG, "NewPipe ready — PO token provider attached")
        }
    }

    private const val TAG = "NewPipeBoot"
}
