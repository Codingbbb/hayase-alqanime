package com.alqanime

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import org.jsoup.nodes.Element
import org.jsoup.select.Elements

class AlQanimeProvider : MainAPI() {
    override var mainUrl = "https://alqanime.net"
    override var name = "AlQanime"
    override var lang = "id"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val hasQuickSearch = false
    
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    companion object {
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Anime Terbaru",
        "$mainUrl/ongoing-anime/page/" to "Sedang Tayang",
        "$mainUrl/complete-anime/page/" to "Selesai",
        "$mainUrl/movies/page/" to "Movie",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data + page).document
        val home = document.select("article.bs").mapNotNull {
            it.toSearchResult()
        }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val title = this.selectFirst("div.bsx > a")?.attr("title")?.trim() ?: return null
        val href = fixUrlNull(this.selectFirst("div.bsx > a")?.attr("href")) ?: return null
        val posterUrl = fixUrlNull(
            this.selectFirst("div.bsx > a > div.limit > img")?.attr("src")
        )
        val episode = this.selectFirst("div.bt > span.epx")?.text()?.replace(Regex("[^0-9]"), "")?.toIntOrNull()

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            addSub(episode)
        }
    }

    override suspend fun search(query: String): List<AnimeSearchResponse> {
        val searchResponse = app.get("$mainUrl/?s=$query").document
        return searchResponse.select("article.bs").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val title = document.selectFirst("h1.entry-title")?.text()?.trim() ?: "Unknown"
        val poster = fixUrlNull(document.selectFirst("div.thumb > img")?.attr("src"))
        val tags = document.select("div.genxed > a").map { it.text() }
        val year = document.selectFirst("span.year")?.text()?.trim()?.toIntOrNull()
        val tvType = if (url.contains("/movie/")) TvType.AnimeMovie else TvType.Anime
        val description = document.selectFirst("div.entry-content.entry-content-single")?.text()?.trim()
        val rating = document.selectFirst("div.rating > strong")?.text()?.toRatingInt()
        val duration = document.selectFirst("span.duration")?.text()?.trim()

        val episodes = document.select("div.eplister > ul > li").mapNotNull { ep ->
            val epHref = fixUrlNull(ep.selectFirst("a")?.attr("href")) ?: return@mapNotNull null
            val epTitle = ep.selectFirst("div.epl-title")?.text()?.trim()
            val epNum = ep.selectFirst("div.epl-num")?.text()?.trim()?.toIntOrNull()
            val epDate = ep.selectFirst("div.epl-date")?.text()?.trim()

            Episode(
                data = epHref,
                name = epTitle,
                episode = epNum,
                posterUrl = poster,
                date = epDate
            )
        }.reversed()

        val recommendations = document.select("div.listupd > article.bs").mapNotNull {
            it.toSearchResult()
        }

        return newAnimeLoadResponse(title, url, tvType) {
            engName = title
            posterUrl = poster
            this.year = year
            addEpisodes(DubStatus.Subbed, episodes)
            showStatus = when {
                document.select("div.spe > span:contains(Status)").text().contains("Ongoing", true) -> ShowStatus.Ongoing
                document.select("div.spe > span:contains(Status)").text().contains("Completed", true) -> ShowStatus.Completed
                else -> null
            }
            plot = description
            this.tags = tags
            this.rating = rating
            this.duration = duration
            this.recommendations = recommendations
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // Extract video dari berbagai player
        document.select("div.player-embed iframe, div.player-wrapper iframe").forEach { iframe ->
            val iframeUrl = fixUrlNull(iframe.attr("src")) ?: return@forEach
            
            // Load extractor otomatis
            loadExtractor(iframeUrl, subtitleCallback, callback)
        }

        // Extract dari option player
        document.select("select#server option, div.mirror option").forEach { option ->
            val playerUrl = option.attr("value")
            if (playerUrl.isNotEmpty()) {
                loadExtractor(fixUrl(playerUrl), subtitleCallback, callback)
            }
        }

        // Extract dari download links
        document.select("div.download-eps a, div.soraddl a").apmap { link ->
            val downloadUrl = fixUrlNull(link.attr("href")) ?: return@apmap
            val quality = link.text().substringBefore("p").trim()
            
            if (downloadUrl.contains(".mp4") || downloadUrl.contains(".mkv")) {
                callback.invoke(
                    ExtractorLink(
                        source = name,
                        name = "$name - $quality",
                        url = downloadUrl,
                        referer = mainUrl,
                        quality = getQualityFromName(quality),
                        isM3u8 = false
                    )
                )
            }
        }

        // Extract dari script video player
        val scriptData = document.select("script").firstOrNull {
            it.html().contains("sources") || it.html().contains("file")
        }?.html()

        scriptData?.let { script ->
            // Extract m3u8 or mp4 links
            val videoUrlPattern = """(?:file|url)["']\s*:\s*["']([^"']+)""".toRegex()
            videoUrlPattern.findAll(script).forEach { match ->
                val videoUrl = match.groupValues[1]
                if (videoUrl.isNotEmpty() && (videoUrl.contains(".m3u8") || videoUrl.contains(".mp4"))) {
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = name,
                            url = videoUrl,
                            referer = mainUrl,
                            quality = Qualities.Unknown.value,
                            isM3u8 = videoUrl.contains(".m3u8")
                        )
                    )
                }
            }
        }

        return true
    }

    private fun getQualityFromName(quality: String?): Int {
        return when {
            quality?.contains("360") == true -> Qualities.P360.value
            quality?.contains("480") == true -> Qualities.P480.value
            quality?.contains("720") == true -> Qualities.P720.value
            quality?.contains("1080") == true -> Qualities.P1080.value
            else -> Qualities.Unknown.value
        }
    }
}
