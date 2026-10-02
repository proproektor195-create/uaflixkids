package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import java.net.URLEncoder

const val YOUTUBE_REQUEST = "uaflixkids://youtube"
const val YOUTUBE_SOURCE_PREFIX = "uaflixkids://youtube/source/"
const val YOUTUBE_PLAY_PREFIX = "uaflixkids://youtube/play/"

data class YouTubeSource(
    val title: String,
    val value: String
)

object YouTubeSettingsStore {
    private const val PREFS = "uaflixkids_settings"
    private const val KEY = "youtube_sources"

    fun raw(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "")
            .orEmpty()

    fun save(context: Context, value: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, value.trim())
            .apply()
    }

    fun read(context: Context): List<YouTubeSource> =
        raw(context)
            .lineSequence()
            .mapNotNull { line ->
                val clean = line.trim()
                if (clean.isBlank()) return@mapNotNull null

                val parts = clean.split("|", limit = 2).map { it.trim() }
                if (parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank()) {
                    YouTubeSource(parts[0], parts[1])
                } else {
                    null
                }
            }
            .distinctBy { it.value }
            .toList()
}

class YouTubeCatalog(
    private val context: Context
) {
    private fun extractPlaylistId(value: String): String? =
        Regex("[?&]list=([A-Za-z0-9_-]+)")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)

    private fun extractChannelId(value: String): String? =
        Regex("(?:channel/|^)(UC[A-Za-z0-9_-]{20,})")
            .find(value)
            ?.groupValues
            ?.getOrNull(1)

    private suspend fun resolveChannelId(value: String): String? {
        extractChannelId(value)?.let { return it }
        if (!value.startsWith("http")) return null

        val html = app.get(value).text
        val patterns = listOf(
            Regex("\"channelId\":\"(UC[^\"]+)\""),
            Regex("channel_id=(UC[A-Za-z0-9_-]+)"),
            Regex("youtube.com/channel/(UC[A-Za-z0-9_-]+)")
        )

        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(html)?.groupValues?.getOrNull(1)
        }
    }

    private suspend fun buildFeedUrl(value: String): String? {
        extractPlaylistId(value)?.let { playlistId ->
            val encoded = URLEncoder.encode(playlistId, "UTF-8")
            return "https://www.youtube.com/feeds/videos.xml?playlist_id=$encoded"
        }

        val channelId = resolveChannelId(value) ?: return null
        val encoded = URLEncoder.encode(channelId, "UTF-8")
        return "https://www.youtube.com/feeds/videos.xml?channel_id=$encoded"
    }

    private suspend fun loadFeed(source: YouTubeSource): List<YouTubeVideo> {
        val feedUrl = buildFeedUrl(source.value) ?: return emptyList()
        val document = app.get(feedUrl).document

        return document.select("entry").mapNotNull { entry ->
            val videoId = entry
                .selectFirst("yt|videoId, videoId")
                ?.text()
                ?.trim()
                .orEmpty()

            if (videoId.isBlank()) return@mapNotNull null

            val title = entry.selectFirst("title")?.text()?.trim().orEmpty()
            val thumbnail = entry
                .selectFirst("media|thumbnail, thumbnail")
                ?.attr("url")
                ?.takeIf { it.isNotBlank() }
                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

            YouTubeVideo(videoId, title, thumbnail)
        }.distinctBy { it.id }
    }

    suspend fun home(
        api: MainAPI,
        request: MainPageRequest
    ): HomePageResponse {
        val sources = YouTubeSettingsStore.read(context)

        val channels: List<SearchResponse> = sources.mapIndexed { index, source ->
            val poster = runCatching {
                loadFeed(source).firstOrNull()?.thumbnail
            }.getOrNull()

            with(api) {
                newMovieSearchResponse(
                    source.title,
                    "$YOUTUBE_SOURCE_PREFIX$index",
                    TvType.TvSeries
                ) {
                    posterUrl = poster
                }
            }
        }

        return newHomePageResponse(
            request,
            channels
        )
    }

    suspend fun load(
        api: MainAPI,
        url: String
    ): LoadResponse {
        val index = url.removePrefix(YOUTUBE_SOURCE_PREFIX).toIntOrNull()
            ?: throw IllegalArgumentException("Неправильний індекс YouTube-каналу")

        val source = YouTubeSettingsStore.read(context).getOrNull(index)
            ?: throw IllegalArgumentException("YouTube-канал не знайдено")

        val videos = loadFeed(source)
        val episodes: List<Episode> = videos.mapIndexed { position, video ->
            newEpisode("$YOUTUBE_PLAY_PREFIX${video.id}") {
                name = video.title
                episode = position + 1
                posterUrl = video.thumbnail
            }
        }

        return with(api) {
            newTvSeriesLoadResponse(
                source.title,
                url,
                TvType.TvSeries,
                episodes
            ) {
                posterUrl = videos.firstOrNull()?.thumbnail
                plot = "Відео з YouTube-каналу ${source.title}"
            }
        }
    }
}

private data class YouTubeVideo(
    val id: String,
    val title: String,
    val thumbnail: String
)
