package com.lagradost

import android.content.Context
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import java.net.URLEncoder

const val YOUTUBE_REQUEST =
    "uaflixkids://youtube"

const val YOUTUBE_VIDEO_PREFIX =
    "uaflixkids://youtube/video/"

data class YouTubeSource(
    val title: String,
    val value: String
)

object YouTubeSettingsStore {

    private const val PREFS =
        "uaflixkids_settings"

    private const val KEY =
        "youtube_sources"

    fun raw(context: Context): String {
        return context
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .getString(KEY, "")
            .orEmpty()
    }

    fun save(
        context: Context,
        value: String
    ) {
        context
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .edit()
            .putString(
                KEY,
                value.trim()
            )
            .apply()
    }

    fun read(
        context: Context
    ): List<YouTubeSource> {

        return raw(context)
            .lineSequence()
            .mapNotNull { line ->

                val clean =
                    line.trim()

                if (clean.isBlank()) {
                    return@mapNotNull null
                }

                val parts =
                    clean
                        .split(
                            "|",
                            limit = 2
                        )
                        .map {
                            it.trim()
                        }

                if (
                    parts.size == 2 &&
                    parts[0].isNotBlank() &&
                    parts[1].isNotBlank()
                ) {
                    YouTubeSource(
                        title = parts[0],
                        value = parts[1]
                    )
                } else {
                    null
                }
            }
            .distinctBy {
                it.value
            }
            .toList()
    }
}

class YouTubeCatalog(
    private val context: Context
) {

    private fun extractPlaylistId(
        value: String
    ): String? {

        return Regex(
            "[?&]list=([A-Za-z0-9_-]+)"
        )
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
    }

    private fun extractChannelId(
        value: String
    ): String? {

        return Regex(
            "(?:channel/|^)(UC[A-Za-z0-9_-]{20,})"
        )
            .find(value)
            ?.groupValues
            ?.getOrNull(1)
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

        val html =
            app.get(value).text

        val patterns = listOf(
            Regex(
                "\"channelId\":\"(UC[^\"]+)\""
            ),
            Regex(
                "channel_id=(UC[A-Za-z0-9_-]+)"
            ),
            Regex(
                "youtube.com/channel/(UC[A-Za-z0-9_-]+)"
            )
        )

        return patterns
            .firstNotNullOfOrNull { pattern ->

                pattern
                    .find(html)
                    ?.groupValues
                    ?.getOrNull(1)
            }
    }

    private suspend fun buildFeedUrl(
        value: String
    ): String? {

        val playlistId =
            extractPlaylistId(value)

        if (playlistId != null) {

            val encoded =
                URLEncoder.encode(
                    playlistId,
                    "UTF-8"
                )

            return "https://www.youtube.com/feeds/videos.xml?playlist_id=$encoded"
        }

        val channelId =
            resolveChannelId(value)
                ?: return null

        val encoded =
            URLEncoder.encode(
                channelId,
                "UTF-8"
            )

        return "https://www.youtube.com/feeds/videos.xml?channel_id=$encoded"
    }

    suspend fun home(
        api: MainAPI,
        request: MainPageRequest
    ): HomePageResponse {

        val items =
            mutableListOf<SearchResponse>()

        for (
            source in
            YouTubeSettingsStore.read(context)
        ) {
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
                                ?.trim()
                                .orEmpty()

                        if (videoId.isBlank()) {
                            return@forEach
                        }

                        val videoTitle =
                            entry
                                .selectFirst("title")
                                ?.text()
                                ?.trim()
                                .orEmpty()

                        val title =
                            if (
                                source.title.isBlank()
                            ) {
                                videoTitle
                            } else {
                                "${source.title}: $videoTitle"
                            }

                        val thumbnail =
                            entry
                                .selectFirst(
                                    "media|thumbnail, thumbnail"
                                )
                                ?.attr("url")
                                ?.takeIf {
                                    it.isNotBlank()
                                }
                                ?: "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

                        val item =
                            with(api) {

                                newMovieSearchResponse(
                                    title,
                                    "$YOUTUBE_VIDEO_PREFIX$videoId",
                                    TvType.Movie
                                ) {
                                    posterUrl =
                                        thumbnail
                                }
                            }

                        items.add(item)
                    }
            }
        }

        return newHomePageResponse(
            request,
            items.distinctBy {
                it.url
            }
        )
    }

    suspend fun load(
        api: MainAPI,
        url: String
    ): LoadResponse {

        val videoId =
            url.removePrefix(
                YOUTUBE_VIDEO_PREFIX
            )

        val watchUrl =
            "https://www.youtube.com/watch?v=$videoId"

        val document =
            runCatching {
                app.get(watchUrl).document
            }.getOrNull()

        val title =
            document
                ?.selectFirst(
                    "meta[name=title]"
                )
                ?.attr("content")
                ?.takeIf {
                    it.isNotBlank()
                }
                ?: "YouTube"

        val description =
            document
                ?.selectFirst(
                    "meta[name=description]"
                )
                ?.attr("content")
                ?.takeIf {
                    it.isNotBlank()
                }

        return with(api) {

            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                watchUrl
            ) {
                posterUrl =
                    "https://i.ytimg.com/vi/$videoId/hqdefault.jpg"

                plot =
                    description
            }
        }
    }
}
