package com.lagradost

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.M3u8Helper
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.models.PlayerJson
import com.lagradost.nicehttp.Session
import okhttp3.FormBody
import org.jsoup.nodes.Element

class UAFlixProvider(
    private val youtubeCatalog: YouTubeCatalog
) : MainAPI() {

    override var mainUrl = "https://uafix.net"
    override var name = "UAFlix Kids"
    override val hasMainPage = true
    override var lang = "uk"
    override val hasQuickSearch = true
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.Cartoon,
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        mainPage(
            "$mainUrl/cartoons/page/",
            "Мультфільми",
            horizontalImages = true
        ),
        mainPage(
            "$mainUrl/serials/multseial/page/",
            "Мультсеріали",
            horizontalImages = true
        ),
        mainPage(
            YOUTUBE_REQUEST,
            "YouTube",
            horizontalImages = true
        )
    )

    private val session by lazy {
        Session(app.baseClient)
    }

    private val fileRegex =
        "file\\s*:\\s*['\"]([^'\"]+)['\"]".toRegex()

    private val subtitleRegex =
        "subtitle\\s*:\\s*['\"]([^'\"]*)['\"]".toRegex()

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        if (request.data == YOUTUBE_REQUEST) {
            return youtubeCatalog.home(
                api = this,
                request = request,
                page = page
            )
        }

        val baseUrl =
            request.data.replace("/page/", "/")

        val postBody = FormBody.Builder()
            .add("xf_sort", "get")
            .add("xf_field", "default")
            .add("xf_value", "date")
            .build()

        session.post(
            url = baseUrl,
            requestBody = postBody
        )

        val document =
            session.get(request.data + page).document

        val home = document
            .select(".video-item")
            .filterNot { item ->
                request.name == "Мультфільми" &&
                    item
                        .selectFirst(".vi-img,.sres-wrap")
                        ?.attr("href")
                        .orEmpty()
                        .contains("/serials/")
            }
            .map { item ->
                item.toSearchResponse()
            }

        return newHomePageResponse(
            request,
            home
        )
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val title =
            selectFirst(".vi-img,.sres-img img")
                ?.attr("alt")
                ?.trim()
                .orEmpty()

        val href =
            selectFirst(".vi-img,.sres-wrap")
                ?.attr("href")
                .orEmpty()

        val poster = fixUrl(
            select(".img-resp-h img,.sres-img img")
                .attr("src")
        )

        val type =
            if (href.contains("multseial")) {
                TvType.TvSeries
            } else {
                TvType.Cartoon
            }

        return newMovieSearchResponse(
            title,
            href,
            type
        ) {
            posterUrl = poster
        }
    }

    override suspend fun quickSearch(
        query: String
    ): List<SearchResponse> = search(query)

    override suspend fun search(
        query: String
    ): List<SearchResponse> {
        return app.get(
            "$mainUrl/index.php?do=search&subaction=search&search_start=0&story=$query"
        )
            .document
            .select(".sres-wrap")
            .map { item ->
                item.toSearchResponse()
            }
    }

    override suspend fun load(
        url: String
    ): LoadResponse {

        if (url.startsWith(YOUTUBE_SOURCE_PREFIX)) {
            return youtubeCatalog.load(
                api = this,
                url = url
            )
        }

        val document = app.get(url).document

        val title = document
            .select(".fright h1")
            .text()
            .trim()
            .replace("дивитись онлайн", "")

        val poster = fixUrl(
            document
                .select(".img-box img")
                .attr("data-src")
                .ifBlank {
                    document
                        .select(".img-box img")
                        .attr("src")
                }
        )

        val tags = document
            .select("span[itemprop=genre]")
            .map { element ->
                element.text()
            }

        val actors = document
            .select("span[itemprop=actor]")
            .map { element ->
                element.text()
            }

        val year = document
            .select(".year")
            .text()
            .toIntOrNull()

        val description = document
            .selectFirst("#fdesc")
            ?.text()
            ?.trim()

        val rating = document
            .select(".mediablock .rat-imdb")
            .text()

        val trailer =
            extractUAFlixTrailer(document)

        val isSeries =
            url.contains("/serials/")

        val episodes =
            mutableListOf<Episode>()

        val playerUrl = document
            .select(".video-box iframe")
            .attr("src")

        if (isSeries) {
            if (playerUrl.isBlank()) {
                document
                    .select(".video-item")
                    .forEach { item ->
                        val numbers = Regex("\\d+")
                            .findAll(
                                item.select(".vi-title").text()
                            )
                            .toList()

                        episodes += newEpisode(
                            item.select(".vi-img").attr("href")
                        ) {
                            name =
                                item.select(".vi-rate").text()

                            season =
                                numbers
                                    .getOrNull(0)
                                    ?.value
                                    ?.toIntOrNull()

                            episode =
                                numbers
                                    .getOrNull(1)
                                    ?.value
                                    ?.toIntOrNull()

                            posterUrl = fixUrl(
                                item
                                    .select(".img-resp-h img")
                                    .attr("data-src")
                            )
                        }
                    }
            } else {
                val raw = fileRegex
                    .find(
                        app.get(
                            playerUrl,
                            referer = mainUrl
                        )
                            .document
                            .select("script")
                            .html()
                    )
                    ?.groupValues
                    ?.getOrNull(1)
                    .orEmpty()

                tryParseJson<List<PlayerJson>>(raw)
                    ?.forEach { dub ->
                        dub.folder.forEach { season ->
                            season.folder.forEach { item ->
                                val numbers = parseEpisodeNumbers(
                                    season.title,
                                    item.title
                                )

                                episodes += newEpisode(
                                    "${season.title}, ${item.title}, $playerUrl"
                                ) {
                                    name = item.title
                                    this.season = numbers.first
                                    episode = numbers.second
                                    posterUrl = item.poster
                                }
                            }
                        }
                    }
            }

            return newAnimeLoadResponse(
                title,
                url,
                TvType.TvSeries
            ) {
                posterUrl = poster
                this.year = year
                plot = description
                this.tags = tags
                score = Score.from10(rating)

                addEpisodes(
                    DubStatus.Dubbed,
                    episodes.sortedBy { episode ->
                        episode.episode
                    }
                )

                addActors(actors)
                trailer?.let { value ->
                    addTrailer(value)
                }
            }
        }

        return newMovieLoadResponse(
            title,
            url,
            TvType.Cartoon,
            url
        ) {
            posterUrl = poster
            this.year = year
            plot = description
            this.tags = tags
            score = Score.from10(rating)
            addActors(actors)

            trailer?.let { value ->
                addTrailer(value)
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        if (data.startsWith(YOUTUBE_PLAY_PREFIX)) {
            val videoId = data
                .removePrefix(YOUTUBE_PLAY_PREFIX)
                .trim()

            if (videoId.isBlank()) {
                return false
            }

            return loadExtractor(
                "https://youtube.com/watch?v=$videoId",
                subtitleCallback,
                callback
            )
        }

        val parts = data.split(", ")

        if (parts.size == 1) {
            var playerUrl = app.get(data)
                .document
                .select(".video-box iframe")
                .attr("src")

            if (playerUrl.startsWith("//")) {
                playerUrl = "https:$playerUrl"
            }

            val playerDocument = app.get(
                playerUrl,
                referer = mainUrl
            ).document

            val scripts = playerDocument
                .select("script")
                .html()

            val raw = fileRegex
                .find(scripts)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()

            val subtitle = subtitleRegex
                .find(scripts)
                ?.groupValues
                ?.getOrNull(1)
                .orEmpty()

            if (playerUrl.contains("/vod/")) {
                M3u8Helper.generateM3u8(
                    source = "UAFlix",
                    streamUrl = raw,
                    referer = "https://tortuga.wtf/"
                )
                    .dropLast(1)
                    .forEach(callback)

                parseUAFlixSubtitle(subtitle)?.let { item ->
                    subtitleCallback(
                        newSubtitleFile(
                            item.language,
                            item.url
                        )
                    )
                }
            } else {
                tryParseJson<List<PlayerJson>>(raw)
                    ?.forEach { dub ->
                        dub.folder.forEach { season ->
                            season.folder
                                .firstOrNull()
                                ?.let { item ->
                                    if (item.file.isNotBlank()) {
                                        M3u8Helper.generateM3u8(
                                            source = dub.title,
                                            streamUrl = item.file,
                                            referer = "https://tortuga.wtf/"
                                        )
                                            .dropLast(1)
                                            .forEach(callback)
                                    }
                                }
                        }
                    }
            }

            return true
        }

        if (parts.size < 3) {
            return false
        }

        val raw = fileRegex
            .find(
                app.get(
                    parts[2],
                    referer = mainUrl
                )
                    .document
                    .select("script")
                    .html()
            )
            ?.groupValues
            ?.getOrNull(1)
            .orEmpty()

        tryParseJson<List<PlayerJson>>(raw)
            ?.forEach { dub ->
                dub.folder
                    .filter { season ->
                        season.title == parts[0]
                    }
                    .forEach { season ->
                        season.folder
                            .filter { item ->
                                item.title == parts[1]
                            }
                            .forEach { item ->
                                M3u8Helper.generateM3u8(
                                    source = dub.title,
                                    streamUrl = item.file,
                                    referer = "https://tortuga.wtf/"
                                )
                                    .dropLast(1)
                                    .forEach(callback)

                                parseUAFlixSubtitle(
                                    item.subtitle
                                )?.let { subtitle ->
                                    subtitleCallback(
                                        newSubtitleFile(
                                            subtitle.language,
                                            subtitle.url
                                        )
                                    )
                                }
                            }
                    }
            }

        return true
    }
}

internal fun parseEpisodeNumbers(
    seasonTitle: String,
    episodeTitle: String
): Pair<Int?, Int?> {
    val regex = Regex("\\d+")

    return regex
        .find(seasonTitle)
        ?.value
        ?.toIntOrNull() to
        regex
            .find(episodeTitle)
            ?.value
            ?.toIntOrNull()
}
