package com.lagradost

import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.stream.StreamInfo

object YouTubeStreamExtractor {

    /**
     * Отримує muxed-потоки YouTube, де відео та звук уже об'єднані,
     * і передає їх у вбудований плеєр CloudStream.
     */
    suspend fun load(
        videoId: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val cleanId = videoId.trim()
        if (cleanId.isBlank()) return false

        val watchUrl = "https://www.youtube.com/watch?v=$cleanId"

        return runCatching {
            val extractor = ServiceList.YouTube.getStreamExtractor(watchUrl)
            extractor.fetchPage()

            val info = StreamInfo.getInfo(extractor)
            var linksAdded = 0

            // videoStreams у NewPipeExtractor є muxed-потоками:
            // відео та звук містяться в одному файлі.
            info.videoStreams
                .asSequence()
                .filter { stream -> stream.isUrl }
                .filter { stream -> !stream.url.isNullOrBlank() }
                .distinctBy { stream -> stream.url }
                .sortedByDescending { stream -> stream.height }
                .forEach { stream ->
                    val streamUrl = stream.url ?: return@forEach
                    val quality = qualityFromHeight(stream.height)
                    val label = stream.resolution
                        ?.takeIf { it.isNotBlank() }
                        ?: if (stream.height > 0) "${stream.height}p" else "YouTube"

                    callback(
                        ExtractorLink(
                            source = "YouTube",
                            name = "YouTube $label",
                            url = streamUrl,
                            referer = "https://www.youtube.com/",
                            quality = quality,
                            isM3u8 = false,
                            headers = mapOf(
                                "User-Agent" to USER_AGENT,
                                "Referer" to "https://www.youtube.com/"
                            )
                        )
                    )

                    linksAdded++
                }

            linksAdded > 0
        }.getOrDefault(false)
    }

    private fun qualityFromHeight(height: Int): Int {
        return when {
            height >= 2160 -> Qualities.P2160.value
            height >= 1440 -> Qualities.P1440.value
            height >= 1080 -> Qualities.P1080.value
            height >= 720 -> Qualities.P720.value
            height >= 480 -> Qualities.P480.value
            height >= 360 -> Qualities.P360.value
            height >= 240 -> Qualities.P240.value
            height >= 144 -> Qualities.P144.value
            else -> Qualities.Unknown.value
        }
    }

    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
}
