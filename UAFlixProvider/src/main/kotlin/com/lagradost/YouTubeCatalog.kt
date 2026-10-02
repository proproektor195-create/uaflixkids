
package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import java.net.URLEncoder

const val YOUTUBE_REQUEST = "uaflixkids://youtube"
const val YOUTUBE_VIDEO_PREFIX = "uaflixkids://youtube/video/"

data class YouTubeSource(
    val title: String,
    val value: String
)

object YouTubeSettingsStore {

    private const val PREFS = "uaflixkids_settings"
    private const val KEY = "youtube_sources"

    fun raw(context: Context): String {
        return context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "")
            .orEmpty()
    }

    fun save(
        context: Context,
        value: String
    ) {
        context
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, value.trim())
            .apply()
    }

    fun read(context: Context): List<YouTubeSource> {
        return raw(context)
            .lineSequence()
            .mapNotNull { line ->

                val clean = line.trim()

                if (clean.isBlank()) {
                    return@mapNotNull null
                }

                val parts = clean
                    .split("|", limit = 2)
                    .map { it.trim() }

                if (
                    parts.size == 2 &&
                    parts[0].isNotBlank() &&
                    parts[1].isNotBlank()
                ) {
                    YouTubeSource(
                        parts[0],
                        parts[1]
                    )
                } else {
                    null
                }
            }
            .distinctBy { it.value }
            .toList()
    }
}

class YouTubeCatalog(
    private val context: Context
) {

    private fun extractPlaylistId(
        url: String
    ): String? {
        return Regex(
            "[?&]list=([A-Za-z0-9_-]+)"
        )
            .find(url)
            ?.groupValues
            ?.get(1)
    }

    private fun extractChannelId(
        url: String
    ): String? {
        return Regex(
            "(?:channel/|^)(UC[A-Za-z0-9_-]{20,})"
        )
            .find(url)
            ?.groupValues
            ?.get(1)
    }

    private suspend fun resolveChannelId(
        value: String
    ): String? {

        extractChannelId(value)?.let {
            return it
        }

        if (!value.startsWith("http")) {
            return null
        }

        val html = app.get(value).text

        return Regex(
            "\"channelId\":\"(UC[^\"]+)\""
        )
            .find(html)
            ?.groupValues
            ?.get(1)
    }

    private suspend fun buildFeedUrl(
        value: String
    ): String? {

        val playlistId = extractPlaylistId(value)

        if (playlistId != null) {
            return "https://www.youtube.com/feeds/videos.xml?playlist_id=${
                URLEncoder.encode(
                    playlistId,
                    "UTF-8"
                )
            }"
        }

        val channelId =
            resolveChannelId(value)
                ?: return null

        return "https://www.youtube.com/feeds/videos.xml?channel_id=${
            URLEncoder.encode(
                channelId,
                "UTF-8"
            )
        }"
    }

    suspend fun home(
        request: MainPageRequest
    ): HomePageResponse {

        val items =
            mutableListOf<SearchResponse>()

        for (source in YouTubeSettingsStore.read(context)) {

            runCatching {

                val feedUrl =
                    buildFeedUrl(source.value)
                        ?: return@runCatching

                val document =
                    app.get(feedUrl).document

                document
                    .select("entry")
                    .forEach { entry ->

                        val videoId =
                            entry
                                .selectFirst(
                                    "yt|videoId, videoId"
                                )
                                ?.text()
                                .orEmpty()

                        if (videoId.isBlank()) {
                            return@forEach
                        }

                        val title =
                            entry
                                .selectFirst("title")
                                ?.text()
                                .orEmpty()

                        items.add(
                            newMovieSearchResponse(
                                title,
                                "$YOUTUBE_VIDEO_PREFIX$videoId",
                                TvType.Movie
                            ) {
                                posterUrl =
                                    "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
                            }
                        )
                    }
            }
        }

        return newHomePageResponse(
            request,
            items.distinctBy { it.url }
        )
    }

    suspend fun load(
        url: String
    ): LoadResponse {

        val videoId =
            url.removePrefix(
                YOUTUBE_VIDEO_PREFIX
            )

        val watchUrl =
            "https://www.youtube.com/watch?v=$videoId"

        return newMovieLoadResponse(
            "YouTube",
            url,
            TvType.Movie,
            watchUrl
        ) {
            posterUrl =
                "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"
        }
    }
}
