package com.AnimesOnlineCC

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Provider original para https://animesonlinecc.to (WordPress).
 *
 * Seletores validados com curl + User-Agent de navegador em 2026-11-26:
 *  - Home / mais vistos: #dt-seasons article.item.se.tvshows
 *  - Home / recentes:    #dt-tvshows article.item.tvshows
 *  - Home / episodios:   #blog article.item.se.episodes
 *  - Busca:              GET /?s=termo (redireciona para /search/termo)
 *                        article.item.se.tvshows
 *  - Lista:              GET /anime/page/N/  -> article.item.tvshows
 *  - Ficha:              GET /anime/<slug>/  -> .sheader, #seasons .se-c,
 *                        ul.episodios li, .se-q .se-t, .numerando
 *  - Player:             GET /episodio/<slug>/ -> #playex .play-box-iframe iframe,
 *                        nav.player .idTabs a.options, .areaserie a[href*='/anime/']
 */
class AnimesOnlineCC : MainAPI() {
    override var mainUrl = "https://animesonlinecc.to"
    override var name = "Animes Online CC"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime)

    companion object {
        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val SITE_HEADERS = mapOf(
            "User-Agent" to UA,
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
        )

        private val NUMBER = Regex("""\d+""")
        private val SLUG_EPISODE = Regex("""episodio-(\d+)""")
        private val BLOGGER_TOKEN = Regex("""token=([^&"'#\s]+)""")
        // Vídeos do Blogger ficam embutidos no HTML do player, não precisam de RPC.
        private val GOOGLEVIDEO_URL = Regex("""https://[^\s"'\\]+googlevideo[^\s"'\\]+""")
        private val ITAG = Regex("""[?&]itag=(\d+)""")

        private val QUALITY_BY_ITAG = mapOf(
            13 to 144, 160 to 144, 133 to 240, 242 to 240,
            18 to 360, 134 to 360, 243 to 360, 59 to 480,
            135 to 480, 244 to 480, 22 to 720, 136 to 720,
            247 to 720, 37 to 1080, 137 to 1080, 248 to 1080
        )

        private fun firstNumber(text: String?): Int? =
            text?.let { NUMBER.find(it)?.value?.toIntOrNull() }

        /** Remove o sufixo de SEO que o site coloca nos títulos. */
        private fun cleanTitle(raw: String?): String {
            val text = raw?.replace(Regex("""\s+"""), " ").trim().orEmpty()
            val cleaned = text
                .replace(Regex("""\s*Todos os Epis[oó]dios Online""", RegexOption.IGNORE_CASE), "")
                .replace(Regex("""\s*Online$""", RegexOption.IGNORE_CASE), "")
                .trim()
            return cleaned.ifEmpty { "Sem título" }
        }
    }

    private fun absolute(href: String): String = when {
        href.startsWith("http", true) -> href
        href.startsWith("//") -> "https:$href"
        href.startsWith("/") -> "$mainUrl$href"
        else -> "$mainUrl/$href"
    }

    private fun Element.imageUrl(): String? {
        val img = selectFirst(".poster img, .image img, .imagen img, img") ?: return null
        val src = img.attr("src").ifBlank { img.attr("data-src") }
        return src.takeIf { it.isNotBlank() }?.let(::absolute)
    }

    private fun Element.animeLink(): String? =
        selectFirst("a[href*='/anime/'], a[href*='/episodio/']")
            ?.attr("href")
            ?.takeIf { it.isNotBlank() }
            ?.let(::absolute)

    /** Card de anime ou de episódio -> SearchResponse. */
    private fun Element.toSearchResponse(): SearchResponse? {
        val url = animeLink() ?: return null
        val title = cleanTitle(
            selectFirst(".data h3 a, .eptitle h3 a, h3 a, h1")?.text()
                ?: selectFirst("a[href*='/anime/'], a[href*='/episodio/']")?.text()
                ?: selectFirst("img[alt]")?.attr("alt")
        )

        val year = firstNumber(selectFirst(".date, .wdate")?.text())
            ?.takeIf { it in 1900..2100 }

        val badge = selectFirst(".quality")?.text().orEmpty()
        val isDub = badge.contains("dublado", true) || title.contains("dublado", true)
        val isSub = badge.contains("legendado", true) || title.contains("legendado", true)

        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = imageUrl()
            if (year != null) this.year = year
            if (isDub || isSub) addDubStatus(dubExist = isDub, subExist = isSub)
        }
    }

    override val mainPage = mainPageOf(
        "#dt-tvshows article.item.tvshows" to "Animes Recentes",
        "#dt-seasons article.item.se.tvshows" to "Mais Vistos",
        "anime" to "Lista de Animes",
        "episodios" to "Últimos Episódios"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val p = (page + 1).coerceAtLeast(1)
        val (url, selector, hasNext) = when (request.data) {
            "anime" -> Triple("$mainUrl/anime/page/$p/", "article.item.tvshows", true)
            "episodios" -> Triple("$mainUrl/episodio/page/$p/", "article.item.se.episodes", true)
            else -> Triple(mainUrl, request.data, false)
        }

        val items = try {
            app.get(url, headers = SITE_HEADERS, timeout = 30).document
                .select(selector)
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }

        return newHomePageResponse(request.name, items, hasNext && items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()
        return try {
            val term = query.trim().replace(Regex("""\s+"""), "+")
            app.get("$mainUrl/?s=$term", headers = SITE_HEADERS, timeout = 30).document
                .select("article.item.se.tvshows, article.item.tvshows")
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        // Cards de episódios da home abrem a ficha do anime pelo link "Todos os episódios".
        if (url.contains("/episodio/")) {
            val animeUrl = try {
                app.get(url, headers = SITE_HEADERS, timeout = 30).document
                    .selectFirst(".areaserie a[href*='/anime/']")
                    ?.attr("href")
                    ?.let(::absolute)
            } catch (_: Exception) {
                null
            }
            if (animeUrl != null) return load(animeUrl)
        }

        val doc = app.get(url, headers = SITE_HEADERS, timeout = 60).document

        val title = cleanTitle(
            doc.selectFirst(".sheader .data h1")?.text() ?: doc.selectFirst("h1")?.text()
        )
        val poster = doc.selectFirst(".sheader .poster img")
            ?.attr("src")?.takeIf { it.isNotBlank() }?.let(::absolute)
        val plot = doc.selectFirst(".resumotemp .wp-content")?.text()?.trim()
        val year = firstNumber(doc.selectFirst(".sheader .extra .date")?.text())
            ?.takeIf { it in 1900..2100 }
        val tags = doc.select(".sgeneros a").mapNotNull {
            it.text().trim().takeIf { t -> t.isNotBlank() }
        }
        val score = doc.selectFirst(".dt_rating_vgs")?.text()
            ?.let { Regex("""\d+(?:[.,]\d+)?""").find(it)?.value }
            ?.replace(',', '.')
            ?.toDoubleOrNull()

        return newTvSeriesLoadResponse(title, url, TvType.Anime, extractEpisodes(doc)) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
            if (score != null) this.score = Score.from10(score.toFloat())
        }
    }

    /**
     * Cada bloco .se-c é uma temporada; .se-q .se-t traz o número. Alguns animes
     * não tem marcação de temporada: agrupamos pelo prefixo do slug e numeramos.
     */
    private fun extractEpisodes(doc: Document): List<Episode> {
        val found = mutableListOf<Episode>()

        val seasonBlocks = doc.select("#seasons .se-c")
        if (seasonBlocks.isNotEmpty()) {
            seasonBlocks.forEachIndexed { index, block ->
                val season = firstNumber(block.selectFirst(".se-q .se-t")?.text())
                    ?: firstNumber(block.selectFirst(".se-q .title")?.text())
                    ?: (index + 1)

                block.select("ul.episodios li").forEach { li ->
                    val href = li.selectFirst(".episodiotitle a[href*='/episodio/'], a[href*='/episodio/']")
                        ?.attr("href")?.takeIf { it.isNotBlank() } ?: return@forEach

                    val number = firstNumber(li.selectFirst(".numerando")?.text())
                        ?: firstNumber(li.selectFirst(".episodiotitle a")?.text())
                        ?: SLUG_EPISODE.find(href)?.groupValues?.get(1)?.toIntOrNull()

                    found.add(newEpisode(absolute(href)) {
                        this.season = season
                        this.episode = number
                        this.name = cleanTitle(li.selectFirst(".episodiotitle a")?.text())
                            .let { if (it == "Sem título") "Episódio ${number ?: 0}" else it }
                        this.posterUrl = li.imageUrl()
                    })
                }
            }
        } else {
            val groups = LinkedHashMap<String, Int>()
            doc.select("ul.episodios li, article.item.se.episodes").forEach { card ->
                val href = card.selectFirst("a[href*='/episodio/']")?.attr("href")
                    ?.takeIf { it.isNotBlank() } ?: return@forEach

                val slug = href.substringAfterLast('/').substringBefore('?')
                val arc = Regex("""(.+)-episodio-\d+""").find(slug)?.groupValues?.get(1)
                    ?: slug.substringBeforeLast('-')
                val season = groups.getOrPut(arc) { groups.size + 1 }
                val number = firstNumber(card.selectFirst(".numerando")?.text())
                    ?: SLUG_EPISODE.find(slug)?.groupValues?.get(1)?.toIntOrNull()

                found.add(newEpisode(absolute(href)) {
                    this.season = season
                    this.episode = number
                    this.name = cleanTitle(card.selectFirst(".episodiotitle a, h3 a")?.text())
                        .let { if (it == "Sem título") "Episódio ${number ?: 0}" else it }
                    this.posterUrl = card.imageUrl()
                })
            }
        }

        return found
            .distinctBy { it.data }
            .sortedWith(compareBy({ it.season ?: 0 }, { it.episode ?: 0 }, { it.data }))
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false

        return try {
            val doc = app.get(data, headers = SITE_HEADERS, timeout = 40).document
            val iframes = doc.select("#playex .play-box-iframe iframe[src]")
            if (iframes.isEmpty()) return false

            var delivered = false
            iframes.forEachIndexed { index, iframe ->
                val src = absolute(iframe.attr("src"))
                val label = doc.selectFirst("nav.player a.options[href='#option-${index + 1}']")
                    ?.text()?.trim()?.takeIf { it.isNotBlank() }
                    ?: "Servidor ${index + 1}"

                if (src.contains("blogger.com/video.g", true)) {
                    if (resolveBlogger(src, label, callback)) delivered = true
                } else {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name $label",
                            url = src,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.headers = SITE_HEADERS
                        }
                    )
                    delivered = true
                }
            }
            delivered
        } catch (_: Exception) {
            false
        }
    }

    /**
     * O player do Blogger embute os URLs googlevideo no próprio HTML, escapados
     * como &quot; / \u003d / \u0026. Basta decodificar e ler os itags.
     */
    private suspend fun resolveBlogger(
        iframeUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val token = BLOGGER_TOKEN.find(iframeUrl)?.groupValues?.get(1) ?: return false

        return try {
            val html = app.get(
                "https://www.blogger.com/video.g?token=$token",
                headers = SITE_HEADERS + ("Referer" to "$mainUrl/"),
                timeout = 30
            ).text

            val decoded = html
                .replace("&quot;", "\"")
                .replace("\\u003d", "=")
                .replace("\\u0026", "&")
                .replace("\\u003f", "?")
                .replace("\\/", "/")
                .replace("\\\"", "\"")

            val seen = HashSet<String>()
            var found = false

            GOOGLEVIDEO_URL.findAll(decoded).forEach { match ->
                val streamUrl = match.value.trimEnd('\\', ']', '}', ',')
                if (!streamUrl.startsWith("http") || !seen.add(streamUrl)) return@forEach

                val itag = ITAG.find(streamUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 18
                val quality = QUALITY_BY_ITAG[itag] ?: 360

                callback(
                    newExtractorLink(
                        source = name,
                        name = "$name $label ${quality}p",
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                        this.referer = "https://www.blogger.com/"
                        this.headers = mapOf(
                            "User-Agent" to UA,
                            "Referer" to "https://www.blogger.com/",
                            "Accept" to "*/*"
                        )
                    }
                )
                found = true
            }

            found
        } catch (_: Exception) {
            false
        }
    }
}
