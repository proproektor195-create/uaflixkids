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
    override val supportedTypes = setOf(TvType.Cartoon, TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        mainPage("$mainUrl/cartoons/page/", "Мультфільми", horizontalImages = true),
        mainPage("$mainUrl/serials/multseial/page/", "Мультсеріали", horizontalImages = true),
        mainPage(YOUTUBE_REQUEST, "YouTube", horizontalImages = true),
    )

    private val session by lazy { Session(app.baseClient) }
    private val fileRegex = "file\\s*:\\s*['\"]([^'\"]+)['\"]".toRegex()
    private val subtitleRegex = "subtitle\\s*:\\s*['\"]([^'\"]*)['\"]".toRegex()

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.data == YOUTUBE_REQUEST) return youtubeCatalog.home(request)
        val baseUrl = request.data.replace("/page/", "/")
        val postBody = FormBody.Builder()
            .add("xf_sort", "get").add("xf_field", "default").add("xf_value", "date").build()
        session.post(url = baseUrl, requestBody = postBody)
        val home = session.get(request.data + page).document.select(".video-item")
            .filterNot { request.name == "Мультфільми" && it.selectFirst(".vi-img,.sres-wrap")?.attr("href").orEmpty().contains("/serials/") }
            .map { it.toSearchResponse() }
        return newHomePageResponse(request, home)
    }

    private fun Element.toSearchResponse(): SearchResponse {
        val title = selectFirst(".vi-img,.sres-img img")?.attr("alt")?.trim().orEmpty()
        val href = selectFirst(".vi-img,.sres-wrap")?.attr("href").orEmpty()
        val poster = fixUrl(select(".img-resp-h img,.sres-img img").attr("src"))
        return newMovieSearchResponse(title, href, if (href.contains("multseial")) TvType.TvSeries else TvType.Cartoon) {
            posterUrl = poster
        }
    }

    override suspend fun quickSearch(query: String) = search(query)
    override suspend fun search(query: String): List<SearchResponse> =
        app.get("$mainUrl/index.php?do=search&subaction=search&search_start=0&story=$query")
            .document.select(".sres-wrap").map { it.toSearchResponse() }

    override suspend fun load(url: String): LoadResponse {
        if (url.startsWith(YOUTUBE_VIDEO_PREFIX)) return youtubeCatalog.load(url)
        val document = app.get(url).document
        val title = document.select(".fright h1").text().trim().replace("дивитись онлайн", "")
        val poster = fixUrl(document.select(".img-box img").attr("data-src").ifBlank { document.select(".img-box img").attr("src") })
        val tags = document.select("span[itemprop=genre]").map { it.text() }
        val actors = document.select("span[itemprop=actor]").map { it.text() }
        val year = document.select(".year").text().toIntOrNull()
        val description = document.selectFirst("#fdesc")?.text()?.trim()
        val rating = document.select(".mediablock .rat-imdb").text()
        val trailer = extractUAFlixTrailer(document)
        val isSeries = url.contains("/serials/")
        val episodes = mutableListOf<Episode>()
        val playerUrl = document.select(".video-box iframe").attr("src")

        if (isSeries) {
            if (playerUrl.isBlank()) {
                document.select(".video-item").forEach { item ->
                    val numbers = Regex("\\d+").findAll(item.select(".vi-title").text()).toList()
                    episodes += newEpisode(item.select(".vi-img").attr("href")) {
                        name = item.select(".vi-rate").text()
                        season = numbers.getOrNull(0)?.value?.toIntOrNull()
                        episode = numbers.getOrNull(1)?.value?.toIntOrNull()
                        posterUrl = fixUrl(item.select(".img-resp-h img").attr("data-src"))
                    }
                }
            } else {
                val raw = fileRegex.find(app.get(playerUrl, referer = mainUrl).document.select("script").html())?.groupValues?.get(1).orEmpty()
                tryParseJson<List<PlayerJson>>(raw)?.forEach { dub -> dub.folder.forEach { season -> season.folder.forEach { ep ->
                    val numbers = parseEpisodeNumbers(season.title, ep.title)
                    episodes += newEpisode("${season.title}, ${ep.title}, $playerUrl") { name = ep.title; this.season = numbers.first; episode = numbers.second; posterUrl = ep.poster }
                } } }
            }
            return newAnimeLoadResponse(title, url, TvType.TvSeries) {
                posterUrl = poster; this.year = year; plot = description; this.tags = tags; score = Score.from10(rating)
                addEpisodes(DubStatus.Dubbed, episodes.sortedBy { it.episode }); addActors(actors); trailer?.let { addTrailer(it) }
            }
        }

        return newMovieLoadResponse(title, url, TvType.Cartoon, url) {
            posterUrl = poster; this.year = year; plot = description; this.tags = tags; score = Score.from10(rating)
            addActors(actors); trailer?.let { addTrailer(it) }
        }
    }

    override suspend fun loadLinks(data: String, isCasting: Boolean, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit): Boolean {
        if (data.startsWith("https://www.youtube.com/watch")) return loadExtractor(data, subtitleCallback, callback)
        val parts = data.split(", ")
        if (parts.size == 1) {
            var playerUrl = app.get(data).document.select(".video-box iframe").attr("src")
            if (playerUrl.startsWith("//")) playerUrl = "https:$playerUrl"
            val playerDocument = app.get(playerUrl, referer = mainUrl).document
            val raw = fileRegex.find(playerDocument.select("script").html())?.groupValues?.get(1).orEmpty()
            val subtitle = subtitleRegex.find(playerDocument.select("script").html())?.groupValues?.get(1).orEmpty()
            if (playerUrl.contains("/vod/")) {
                M3u8Helper.generateM3u8("UAFlix", raw, "https://tortuga.wtf/").dropLast(1).forEach(callback)
                parseUAFlixSubtitle(subtitle)?.let { subtitleCallback(newSubtitleFile(it.language, it.url)) }
            } else {
                tryParseJson<List<PlayerJson>>(raw)?.forEach { dub -> dub.folder.forEach { season -> season.folder.firstOrNull()?.let { ep ->
                    if (ep.file.isNotBlank()) M3u8Helper.generateM3u8(dub.title, ep.file, "https://tortuga.wtf/").dropLast(1).forEach(callback)
                } } }
            }
            return true
        }
        val raw = fileRegex.find(app.get(parts[2], referer = mainUrl).document.select("script").html())?.groupValues?.get(1).orEmpty()
        tryParseJson<List<PlayerJson>>(raw)?.forEach { dub -> dub.folder.filter { it.title == parts[0] }.forEach { season -> season.folder.filter { it.title == parts[1] }.forEach { ep ->
            M3u8Helper.generateM3u8(dub.title, ep.file, "https://tortuga.wtf/").dropLast(1).forEach(callback)
            parseUAFlixSubtitle(ep.subtitle)?.let { subtitleCallback(newSubtitleFile(it.language, it.url)) }
        } } }
        return true
    }
}

internal fun parseEpisodeNumbers(seasonTitle: String, episodeTitle: String): Pair<Int?, Int?> {
    val regex = Regex("\\d+")
    return regex.find(seasonTitle)?.value?.toIntOrNull() to regex.find(episodeTitle)?.value?.toIntOrNull()
}
