package com.DoramasOnline

import com.lagradost.cloudstream3.Score

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Provider original para o Doramas Online.
 *
 * O domínio originally informado (doramasonline.co) está estacionado num
 * serviço de parking (ParkLogic) e devolve apenas um challenge JavaScript
 * que redireciona para um portal de notícias. O catálogo real continua
 * publicado em doramasonline.net, com a mesma marca e o mesmo acervo.
 *
 * Estrutura confirmada por curl (WordPress / tema DooPlay):
 *  - home:      /
 *  - busca:     /?s=<termo>  (canonical /search/<termo>/)
 *  - lista:     /br/series/ e /br/series/page/<n>/
 *  - ficha:     /br/series/<slug>/
 *  - episódio:  /br/episodio/<slug>-temporada-<t>-episodio-<e>/
 *  - player:    iframe em div[id^=source-player-], resolvido para o HLS
 *               direto no HTML do player interno /cdn9/odacdn/v2/
 */
class DoramasOnline : MainAPI() {
    override var mainUrl = "https://doramasonline.net"
    override var name = "DoramasOnline"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.AsianDrama)
    override val usesWebView = true

    // "data" das abas; o prefixo evita colisão com slugs do site.
    override val mainPage = mainPageOf(
        "ds-series" to "Doramas em destaque",
        "ds-episodes" to "Episódios recentes",
        "ds-movies" to "Filmes",
        "ds-all" to "Todos os doramas"
    )

    companion object {
        private const val HOME_CACHE_MS = 120_000L
        private const val SERIES_PATH = "/br/series/"
        private const val EPISODE_MARKER = "/br/episodio/"
        private const val MOVIE_MARKER = "/br/filmes/"
    }

    private var cachedHomeHtml: String? = null
    private var cachedHomeAt = 0L

    private suspend fun homeDocument(): Document {
        val cached = cachedHomeHtml
        if (cached != null && System.currentTimeMillis() - cachedHomeAt < HOME_CACHE_MS) {
            return Jsoup.parse(cached, mainUrl)
        }
        val html = app.get(mainUrl, timeout = 35).text
        cachedHomeHtml = html
        cachedHomeAt = System.currentTimeMillis()
        return Jsoup.parse(html, mainUrl)
    }

    /** Normaliza href relativo, protocolo-relativo e entidades HTML. */
    private fun absolutize(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val value = raw.trim().replace("&amp;", "&")
        return when {
            value.startsWith("http://", true) || value.startsWith("https://", true) -> value
            value.startsWith("//") -> "https:$value"
            value.startsWith("/") -> "$mainUrl$value"
            else -> "$mainUrl/$value"
        }
    }

    private fun Element.imageUrl(): String? {
        val img = selectFirst("img") ?: return null
        val src = img.attr("abs:src")
            .ifBlank { img.attr("src") }
            .ifBlank { img.attr("data-src") }
        return absolutize(src)
    }

    /** Card da home (#dt-tvshows, #dt-movies) e item de busca (.result-item). */
    private fun Element.toSearchResponse(): SearchResponse? {
        val link = selectFirst("a[href*=\"/br/series/\"], a[href*=\"/br/filmes/\"], a[href*=\"/serie/\"]")
            ?: return null
        val url = absolutize(link.attr("href")) ?: return null

        val heading = selectFirst("h3, h2, .title, .entry-title")?.text()?.trim()
        val alt = selectFirst("img")?.attr("alt")?.trim()
        val title = heading?.takeIf { it.isNotBlank() } ?: alt
        if (title.isNullOrBlank()) return null

        return newAnimeSearchResponse(title, url, TvType.AsianDrama) {
            this.posterUrl = imageUrl()
            this.year = selectFirst(".date, .year, span")?.text()?.trim()?.toIntOrNull()
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            if (request.data == "ds-all") {
                val url = if (page <= 0) {
                    "$mainUrl$SERIES_PATH"
                } else {
                    "$mainUrl${SERIES_PATH}page/${page + 1}/"
                }
                val document = app.get(url, timeout = 35).document
                val items = document
                    .select("article.item.tvshows, .result-item article, article.item")
                    .mapNotNull { it.toSearchResponse() }
                    .distinctBy { it.url }

                val hasNext = document.selectFirst(".pagination span")?.text()?.trim()
                    ?.split(" of ")
                    ?.takeIf { it.size == 2 }
                    ?.let { parts ->
                        val current = parts[0].trim().toIntOrNull()
                        val total = parts[1].trim().toIntOrNull()
                        current != null && total != null && current < total
                    } ?: false

                newHomePageResponse(request.name, items, hasNext)
            } else {
                if (page > 0) {
                    newHomePageResponse(request.name, emptyList(), false)
                } else {
                    val document = homeDocument()
                    val selector = when (request.data) {
                        "ds-series" -> "#dt-tvshows article.item"
                        "ds-movies" -> "#dt-movies article.item"
                        "ds-episodes" -> "article.item.episodes, .items.full article.item"
                        else -> "article.item"
                    }
                    val items = document.select(selector)
                        .mapNotNull { it.toSearchResponse() }
                        .distinctBy { it.url }
                    newHomePageResponse(request.name, items, false)
                }
            }
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isBlank()) return emptyList()

        return try {
            app.get(mainUrl, params = mapOf("s" to term), timeout = 35).document
                .select(".result-item article, article.item.tvshows, article.item.movies")
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val target = url.trim()
        val isMovie = target.contains(MOVIE_MARKER) || target.contains("/filme/")

        return try {
            val document = app.get(target, referer = "$mainUrl/", timeout = 40).document

            val title = document.selectFirst(".sheader h1, h1")?.text()?.trim()
                ?.substringBefore(" - Episódio")
                ?.substringBefore(" – Doramas")
                ?.takeIf { it.isNotBlank() }
                ?: "Sem título"

            val poster = document.selectFirst(".sheader .poster img")?.let {
                absolutize(it.attr("abs:src").ifBlank { it.attr("src") })
            } ?: document.selectFirst("meta[property=og:image]")?.attr("content")

            val plot = document.selectFirst("#info .wp-content p")?.text()?.trim()
                ?: document.selectFirst(".synopsis p")?.text()?.trim()

            val year = document.selectFirst(".sheader .extra .date")?.text()?.trim()?.toIntOrNull()
                ?: document.selectFirst("meta[itemprop=dateCreated]")?.attr("content")?.toIntOrNull()

            val tags = document.select(".sgeneros a")
                .map { it.text().trim() }
                .filter { it.isNotBlank() }
                .distinct()

            val score = document.selectFirst(".dt_rating_vgs")?.text()?.trim()?.toDoubleOrNull()
                ?: document.selectFirst("meta[itemprop=ratingValue]")?.attr("content")?.toDoubleOrNull()

            if (isMovie) {
                newMovieLoadResponse(title, target, TvType.AsianDrama, target) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                    this.score = score?.let { Score.from10(it) }
                }
            } else {
                val collected = mutableListOf<Triple<Int, Int, Episode>>()
                val seen = HashSet<String>()

                // Todas as temporadas: cada bloco .se-c tem a sua própria lista.
                document.select("#seasons .se-c").forEachIndexed { index, seasonBlock ->
                    val seasonNo = seasonBlock.selectFirst(".se-t")?.text()?.trim()?.toIntOrNull()
                        ?: Regex("""(\d+)""")
                            .find(seasonBlock.selectFirst(".title")?.text().orEmpty())
                            ?.groupValues?.get(1)?.toIntOrNull()
                        ?: (index + 1)

                    seasonBlock.select("ul.episodios li").forEach { item ->
                        val anchor = item.selectFirst(".episodiotitle a") ?: return@forEach
                        val episodeUrl = absolutize(anchor.attr("href")) ?: return@forEach
                        if (!seen.add(episodeUrl)) return@forEach

                        val numerando = item.selectFirst(".numerando")?.text()?.trim().orEmpty()
                        val episodeNo = numerando.substringAfterLast("-").trim().toIntOrNull()
                            ?: Regex("""-episodio-(\d+)""")
                                .find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull()
                            ?: (collected.size + 1)

                        val built = newEpisode(episodeUrl) {
                            this.name = anchor.text().trim().ifEmpty { "Episódio $episodeNo" }
                            this.season = seasonNo
                            this.episode = episodeNo
                            this.posterUrl = item.selectFirst(".imagen img")?.let {
                                absolutize(it.attr("abs:src").ifBlank { it.attr("src") })
                            }
                        }
                        collected.add(Triple(seasonNo, episodeNo, built))
                    }
                }

                // Fallback para páginas sem o bloco padrão de temporadas.
                if (collected.isEmpty()) {
                    document.select("a[href*=\"$EPISODE_MARKER\"]").forEach { anchor ->
                        val episodeUrl = absolutize(anchor.attr("href")) ?: return@forEach
                        if (!seen.add(episodeUrl)) return@forEach
                        val seasonNo = Regex("""-temporada-(\d+)""")
                            .find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        val episodeNo = Regex("""-episodio-(\d+)""")
                            .find(episodeUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                        val built = newEpisode(episodeUrl) {
                            this.name = anchor.text().trim().ifEmpty { "Episódio $episodeNo" }
                            this.season = seasonNo
                            this.episode = episodeNo
                        }
                        collected.add(Triple(seasonNo, episodeNo, built))
                    }
                }

                val episodes = collected
                    .sortedWith(compareBy({ it.first }, { it.second }))
                    .map { it.third }

                newTvSeriesLoadResponse(title, target, TvType.AsianDrama, episodes) {
                    this.posterUrl = poster
                    this.plot = plot
                    this.year = year
                    this.tags = tags
                    this.score = score?.let { Score.from10(it) }
                }
            }
        } catch (e: Exception) {
            newTvSeriesLoadResponse("Erro ao carregar", target, TvType.AsianDrama, emptyList()) {
                this.plot = "Não foi possível carregar esta página do Doramas Online."
            }
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
            val document = app.get(data, referer = "$mainUrl/", timeout = 40).document
            var found = 0

            document.select("div[id^=\"source-player-\"] iframe[src], .pframe iframe[src]")
                .forEach { frame ->
                    val raw = frame.attr("src").ifBlank { frame.attr("data-src") }
                    val embedUrl = absolutize(raw) ?: return@forEach
                    if (embedUrl.contains("youtube", true) || embedUrl.contains("trailer", true)) {
                        return@forEach
                    }

                    val label = when {
                        embedUrl.contains("01-dub", true) -> "$name DUBLADO"
                        embedUrl.contains("dub", true) -> "$name DUBLADO"
                        embedUrl.contains("01-leg", true) -> "$name LEGENDADO"
                        embedUrl.contains("leg", true) -> "$name LEGENDADO"
                        else -> name
                    }

                    // O iframe interno gera o HLS com token; buscamos essa URL fresca.
                    val resolved = runCatching {
                        val playerHtml = app.get(embedUrl, referer = data, timeout = 35).text
                        val file = Regex("""["']file["']\s*:\s*["']([^"']+)["']""")
                            .find(playerHtml)?.groupValues?.get(1)
                        file?.takeIf { it.contains(".m3u8") || it.contains(".mp4") }
                    }.getOrNull()

                    val streamUrl = resolved ?: return@forEach
                    callback(
                        newExtractorLink(name, label, streamUrl, ExtractorLinkType.M3U8) {
                            this.referer = "$mainUrl/"
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    found++
                }

            found > 0
        } catch (e: Exception) {
            false
        }
    }
}
