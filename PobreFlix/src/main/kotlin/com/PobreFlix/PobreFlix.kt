package com.PobreFlix

import android.content.Context
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONObject
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Locale

@CloudstreamPlugin
class PobreFlixProvider : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(PobreFlix())
    }
}

/**
 * Provider para o CMS Overflix (YouCine).
 *
 * Seletores confirmados em https://youcine.quest (HTTP 200, 25/11/2026):
 * - Home: section[data-overflix-tabs] .overflix-tab-panel .swiper-slide a.block
 * - Listas: .vbListTableWrap .similarMovies a.block ; paginação .ipsPagination_next a[rel=next]
 * - Busca: /buscar?s=<termo> -> .overflix-search-results a.block
 * - Fichas: /filmes/online/<slug> e /series/online/<slug>
 * - Título: h2.one ; sinopse: .sinopse-text ; metadados: .meta-info / .meta-generos
 * - Episódios: li.vbEpisodeCard[data-overflix-season-card][data-overflix-episode-number]
 *   (todas as temporadas vêm no HTML; as demais estão com hidden)
 * - Player: [data-playbackhandle] + POST /__siteplay/start -> JSON { sources: [ { url } ] }
 */
class PobreFlix : MainAPI() {
    override var mainUrl = "https://youcine.quest"
    override var name = "PobreFlix"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime, TvType.AsianDrama)
    override val usesWebView = true

    companion object {
        private const val SEARCH_PATH = "/buscar"
        private const val EPISODE_CARD = "li.vbEpisodeCard[data-overflix-season-card][data-overflix-episode-number]"
        private const val ITEM_LINK =
            "a.block[href*='/filmes/online/'], a.block[href*='/series/online/'], a.block[href*='/episodios/online/'], a.block[href*='/animes/online/'], a.block[href*='/doramas/online/']"
        private val MAIN_SECTIONS = listOf(
            "" to "Em Alta",
            "/filmes" to "Filmes",
            "/series" to "Séries",
            "/animes" to "Animes",
            "/doramas" to "Doramas"
        )
    }

    override val mainPage = mainPageOf(
        *MAIN_SECTIONS.map { (path, sectionName) ->
            (if (path.isEmpty()) mainUrl else "$mainUrl$path") to sectionName
        }.toTypedArray()
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = try {
            app.get(request.data).document
        } catch (e: Exception) {
            return newHomePageResponse(request.name, emptyList(), hasNext = false)
        }

        val elements = if (request.name == "Em Alta") {
            document.select("section[data-overflix-tabs] .overflix-tab-panel a.block")
        } else {
            document.select(".vbListTableWrap a.block, .overflix-search-results a.block, section[data-overflix-tabs] a.block")
        }

        val items = elements
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }

        val hasNext = if (request.name == "Em Alta") {
            false
        } else {
            document.selectFirst(".ipsPagination_next a[rel=next]") != null || items.size >= 20
        }

        return newHomePageResponse(request.name, items, hasNext = hasNext)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val href = attr("href").takeIf { it.isNotBlank() } ?: return null
        val url = fixUrl(href)
        if (!isContentUrl(url)) return null

        val poster = selectFirst("img")?.let { img ->
            fixImageUrl(img.attr("src").ifBlank { img.attr("data-src") })
        }
        val title = selectFirst("h3")?.text()?.trim()
            ?: attr("title").removePrefix("Assistir ").trim().takeIf { it.isNotBlank() }
            ?: selectFirst("img")?.attr("alt")?.removePrefix("Assistir ")?.trim()
            ?: return null

        val year = selectFirst(".info p, p")?.text()?.trim()?.let { Regex("\\d{4}").find(it)?.value?.toIntOrNull() }

        return when {
            url.contains("/animes/") || url.contains("/anime/") -> newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = poster
                this.year = year
            }
            url.contains("/series/") || url.contains("/doramas/") || url.contains("/dorama/") ->
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                }
            else -> newMovieSearchResponse(title, url, TvType.Movie) {
                this.posterUrl = poster
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        return try {
            val url = "$mainUrl$SEARCH_PATH?s=${URLEncoder.encode(query, "UTF-8")}"
            app.get(url).document.select(".overflix-search-results a.block, .similarMovies a.block")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String, page: Int): SearchResponseList? {
        if (query.length < 2) return null
        return try {
            val encoded = URLEncoder.encode(query, "UTF-8")
            val sep = if (SEARCH_PATH.contains("?")) "&" else "?"
            val suffix = if (page > 1) "${sep}page=$page" else ""
            val url = "$mainUrl$SEARCH_PATH?s=$encoded$suffix"
            val document = app.get(url).document
            val results = document.select(".overflix-search-results a.block, .similarMovies a.block")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
            val hasNext = document.selectFirst(".ipsPagination_next a[rel=next]") != null
            results.toNewSearchResponseList(hasNext)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        return try {
            val document = app.get(url).document

            val title = document.selectFirst("h2.one")?.text()?.trim()
                ?: document.selectFirst("h1.type")?.text()?.trim()
                    ?.removePrefix("Assistir ")?.replace(Regex("\\s+Online$"), "")
                ?: return null

            val poster = fixImageUrl(
                document.selectFirst("aside .vbItemImage img")?.attr("src")
                    ?: document.selectFirst("meta[property='og:image']")?.attr("content")
            )
            val backdrop = poster

            val synopsis = document.selectFirst(".sinopse-text")?.text()?.trim()
                ?: document.selectFirst("meta[name='description']")?.attr("content")?.trim()

            val metaInfo = document.selectFirst(".meta-info")?.text().orEmpty()
            val year = Regex("\\b(19|20)\\d{2}\\b").find(metaInfo)?.value?.toIntOrNull()
            val duration = parseDuration(metaInfo)
            val rating = document.selectFirst(".meta-info .nota")?.text()
                ?.replace(Regex("[^0-9.,]"), "")?.replace(",", ".")?.toFloatOrNull()

            val tags = document.selectFirst(".meta-generos")?.text()
                ?.split("•")?.map { it.trim() }?.filter { it.isNotBlank() }

            val cast = document.select(".fields-right p.extra")
                .firstOrNull { it.text().contains("Elenco", ignoreCase = true) }
                ?.text().orEmpty()
                .substringAfter("•")
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { Actor(name = it) }
                .takeIf { it.isNotEmpty() }

            val trailerId = document.selectFirst("a.trailer[data-trailer-id]")?.attr("data-trailer-id")
            val trailerUrl = trailerId?.takeIf { it.isNotBlank() }
                ?.let { "https://www.youtube.com/watch?v=$it" }

            val recommendations = document.select("#vbRelatedSlider a.block")
                .mapNotNull { it.toSearchResult() }
                .distinctBy { it.url }
                .takeIf { it.isNotEmpty() }

            val isMovie = url.contains("/filmes/online/") || url.contains("/filmes/")
            if (isMovie) {
                return newMovieLoadResponse(title, url, TvType.Movie, url) {
                    this.posterUrl = poster
                    this.backgroundPosterUrl = backdrop
                    this.year = year
                    this.plot = synopsis
                    this.tags = tags
                    this.duration = duration
                    this.recommendations = recommendations
                    if (rating != null) this.score = Score.from10(rating)
                    if (cast != null) addActors(cast)
                    if (trailerUrl != null) addTrailer(trailerUrl)
                }
            }

            val type = when {
                url.contains("/animes/") || url.contains("/anime/") -> TvType.Anime
                url.contains("/doramas/") || url.contains("/dorama/") -> TvType.AsianDrama
                else -> TvType.TvSeries
            }

            val episodes = document.select(EPISODE_CARD).mapNotNull { card -> card.toEpisode() }
                .distinctBy { it.data }
                .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }))

            newTvSeriesLoadResponse(title, url, type, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.year = year
                this.plot = synopsis
                this.tags = tags
                this.recommendations = recommendations
                if (rating != null) this.score = Score.from10(rating)
                if (cast != null) addActors(cast)
                if (trailerUrl != null) addTrailer(trailerUrl)
            }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Monta o episódio sem preencher Episode.url: o endereço da ficha vai em data,
     * que é o valor entregue a loadLinks. Inclui TODAS as temporadas presentes
     * no HTML (inclusive as marcadas como hidden pelo seletor de temporada).
     */
    private fun Element.toEpisode(): Episode? {
        val season = attr("data-overflix-season-card").toIntOrNull() ?: return null
        val number = attr("data-overflix-episode-number").toIntOrNull() ?: return null
        val href = selectFirst("a.vbEpisodeCard__link[href]")?.attr("href")?.takeIf { it.isNotBlank() }
            ?: return null
        val episodeUrl = fixUrl(href)

        val poster = selectFirst(".vbEpisodeCard__thumb img")?.let { img ->
            fixImageUrl(img.attr("src").ifBlank { img.attr("data-src") })
        }
        val name = selectFirst(".vbEpisodeCard__title")?.text()?.trim()
            ?.takeIf { it.isNotBlank() } ?: "Episódio $number"
        val description = selectFirst(".vbEpisodeCard__synopsis")?.text()?.trim()
        val dateText = selectFirst(".vbEpisodeCard__date")?.text().orEmpty()
            .substringAfter("Lançamento:").trim()

        return newEpisode(episodeUrl) {
            this.name = name
            this.season = season
            this.episode = number
            this.posterUrl = poster
            this.description = description
            if (dateText.isNotBlank()) parseDate(dateText)?.let { this.addDate(it) }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        return try {
            val document = app.get(data).document
            var found = false

            // 1) Iframes já presentes na ficha (quando o CMS entrega o player no HTML).
            document.select("iframe[src]").forEach { iframe ->
                val src = fixUrl(iframe.attr("src"))
                if (src.contains("youtube", ignoreCase = true)) return@forEach
                callback(newExtractorLink(name, name, src, ExtractorLinkType.VIDEO) {
                    this.referer = mainUrl
                })
                found = true
            }

            // 2) URLs de player embutidas em scripts / atributos data-*.
            val scriptUrls = document.select("script").map { it.data() }.joinToString("\n")
            Regex("""(?:src|url|href)\s*[:=]\s*["'](https?://[^"']+)["']""")
                .findAll(scriptUrls)
                .map { it.groupValues[1] }
                .filter { it.contains("player", true) || it.contains("embed", true) || it.contains("m3u8", true) || it.contains("vizer", true) }
                .distinct()
                .forEach { raw ->
                    callback(newExtractorLink(name, name, raw, ExtractorLinkType.VIDEO) {
                        this.referer = mainUrl
                    })
                    found = true
                }

            // 3) API oficial do player Overflix: POST /__siteplay/start com o handle da ficha.
            val handle = document.selectFirst("[data-playbackhandle]")?.attr("data-playbackhandle")?.trim()
            if (!handle.isNullOrBlank()) {
                val csrf = document.selectFirst("meta[name='csrf-token']")?.attr("content").orEmpty()
                val headers = mapOf(
                    "Accept" to "application/json",
                    "Content-Type" to "application/json",
                    "X-Requested-With" to "XMLHttpRequest",
                    "X-CSRF-TOKEN" to csrf,
                    "Referer" to data
                )
                val response = app.post(
                    url = "$mainUrl/__siteplay/start",
                    headers = headers,
                    data = mapOf("handle" to handle)
                )
                if (response.isSuccessful) {
                    val sources = JSONObject(response.text).optJSONArray("sources")
                    if (sources != null) {
                        for (i in 0 until sources.length()) {
                            val url = sources.optJSONObject(i)?.optString("url").orEmpty()
                            if (url.isBlank()) continue
                            callback(newExtractorLink(name, "Fonte ${i + 1}", url, ExtractorLinkType.M3U8) {
                                this.referer = mainUrl
                            })
                            found = true
                        }
                    }
                }
            }

            found
        } catch (e: Exception) {
            false
        }
    }

    private fun isContentUrl(url: String): Boolean =
        url.contains("/filmes/online/") || url.contains("/series/online/") ||
            url.contains("/episodios/online/") || url.contains("/animes/online/") ||
            url.contains("/doramas/online/")

    private fun fixUrl(href: String): String = when {
        href.startsWith("http") -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> "$mainUrl$href"
        else -> "$mainUrl/$href"
    }

    private fun fixImageUrl(url: String?): String? {
        if (url.isNullOrBlank() || url.startsWith("data:image")) return null
        return when {
            url.startsWith("//") -> "https:$url"
            url.startsWith("/") -> "$mainUrl$url"
            else -> url
        }
    }

    private fun parseDuration(metaInfo: String): Int? {
        val text = metaInfo.lowercase(Locale.getDefault())
        val hours = Regex("(\\d+)\\s*h").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val minutes = Regex("(\\d+)\\s*(?:min|m)\\b").find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        return if (hours > 0 || minutes > 0) hours * 60 + minutes else null
    }

    private fun parseDate(value: String): String? = try {
        SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).parse(value)?.let {
            SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(it)
        }
    } catch (e: Exception) {
        null
    }
}
