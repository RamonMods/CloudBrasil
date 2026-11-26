package com.AnimesDigital

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

/**
 * Provider original para o WordPress https://animesdigital.org (HTTP 200 em 26/11/2026).
 *
 * Seletores confirmados com curl + User-Agent de navegador:
 *  - Home:      .itemE (lancamentos/episodios) e .itemA (animes)
 *  - Listas:    /lancamentos01/page/N/ e /ultimos-titulos-adicionados/page/N/ -> .itemE
 *  - Busca:     /pesquisa/<termo>/ -> .itemA, proxima pagina em .content-pagination li.next a
 *  - Ficha:     /anime/a/<slug> -> .animeInfos h1, .poster img, .sinopse, .genre a,
 *               .itens_ep .item_ep a[href*='/video/a/'] e .content-pagination
 *  - Episodio:  /video/a/<id>/ -> .tab-video iframe[src] (Player FHD / Player 2)
 *
 * Nenhum codigo de terceiros foi copiado: toda a logica foi escrita do zero a partir
 * desses seletores e das respostas HTTP reais do site.
 */
class AnimesDigital : MainAPI() {
    override var mainUrl = MAIN_URL
    override var name = "AnimesDigital"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)
    override val usesWebView = false

    companion object {
        private const val MAIN_URL = "https://animesdigital.org"

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

        private val headers = mapOf(
            "User-Agent" to UA,
            "Accept-Language" to "pt-BR,pt;q=0.9,en;q=0.8"
        )

        // O player exige Sec-Fetch-Dest: iframe e Referer do site (CF NOT_IFRAME = 403).
        private val iframeHeaders = headers + mapOf(
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to "$MAIN_URL/",
            "Sec-Fetch-Dest" to "iframe",
            "Sec-Fetch-Mode" to "navigate",
            "Sec-Fetch-Site" to "same-site"
        )

        private val numberRegex = Regex("""\d+""")
        private val episodeRegex = Regex("epis[oó]dio\\s*(\\d+(?:[.,]\\d+)?)", RegexOption.IGNORE_CASE)
        private val seasonRegex = Regex("(\\d+)\\s*[ªaºo]?\\s*(temporada|season)", RegexOption.IGNORE_CASE)
        private val pageRegex = Regex("/page/(\\d+)/?")
        private val fileRegex = Regex("file\\s*:\\s*\"([^\"]+)\"")
        private val nestedIframeRegex = Regex("iframe[^>]*src=\"([^\"]+)\"")
        private val tokenRegex = Regex("video\\.g\\?token=([^&\"'\\s]+)")
        private val sidRegex = Regex("\"FdrFJe\":\"([^\"]+)\"")
        private val blRegex = Regex("\"cfb2h\":\"([^\"]+)\"")
        private val googleVideoRegex = Regex("https?://[^\\s\"'\\\\,]+googlevideo[^\\s\"'\\\\,]+")
        private val itagRegex = Regex("[?&]itag=(\\d+)")

        private const val MAX_EPISODE_PAGES = 60
        private const val MAX_SEARCH_PAGES = 5

        private val qualities = mapOf(
            18 to 360, 22 to 720, 37 to 1080, 59 to 480,
            133 to 240, 134 to 360, 135 to 480, 136 to 720,
            137 to 1080, 160 to 144, 242 to 240, 243 to 360,
            244 to 480, 247 to 720, 248 to 1080
        )

        private fun absoluteUrl(href: String): String = when {
            href.startsWith("http", ignoreCase = true) -> href
            href.startsWith("//") -> "https:$href"
            href.startsWith("/") -> "$MAIN_URL$href"
            else -> "$MAIN_URL/$href"
        }

        private fun firstNumber(text: String?): Int? =
            text?.let { numberRegex.find(it)?.value?.toIntOrNull() }

        private fun tidyTitle(raw: String): String = raw
            .replace(Regex("^Assistir\\s+", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+Online em HD\\s*$", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\s+Todos [Oo]s Epis[oó]dios.*$"), "")
            .trim()
            .ifEmpty { "Anime" }

        /** 2ª Temporada / Temporada 2 / Season 2 / S2 -> 2; sem marcador -> 1. */
        private fun detectSeason(text: String): Int {
            val explicit = seasonRegex.find(text)?.groupValues?.get(1)?.toIntOrNull()
            if (explicit != null && explicit in 1..100) return explicit
            val short = Regex("(?:^|\\s)[Ss](\\d{1,2})(?:\\s|$)").find(text)
                ?.groupValues?.get(1)?.toIntOrNull()
            return if (short != null && short in 1..100) short else 1
        }

        private fun episodeNumber(text: String?): Int? {
            if (text.isNullOrBlank()) return null
            val value = episodeRegex.find(text)?.groupValues?.get(1)
                ?.replace(',', '.')?.toFloatOrNull() ?: return null
            return value.toInt()
        }

        private fun linkType(url: String): ExtractorLinkType {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return if (path.endsWith(".m3u8") || path.endsWith(".jpg")) {
                ExtractorLinkType.M3U8
            } else {
                ExtractorLinkType.VIDEO
            }
        }

        private fun Element.cardImage(): String? =
            selectFirst(".thumb img[src], img[src]")?.attr("src")
                ?.takeIf { it.isNotBlank() }?.let(::absoluteUrl)

        private fun Element.asSearchResponse(): SearchResponse? {
            val link = selectFirst("a[href*='/anime/a/'], a[href*='/anime/b/'], a[href*='/video/a/']")
                ?: return null
            val href = link.attr("href").takeIf { it.isNotBlank() } ?: return null
            val url = absoluteUrl(href)
            val name = selectFirst(".title .title_anime")?.text()?.trim()
                ?: tidyTitle(link.attr("title"))
            val number = selectFirst(".title .number")?.text()?.trim()
            val title = if (!number.isNullOrBlank() && !name.contains(number)) "$name $number" else name

            return newAnimeSearchResponse(title, url, TvType.Anime) {
                this.posterUrl = cardImage()
            }
        }
    }

    override val mainPage = mainPageOf(
        "" to "Em destaque",
        "lancamentos01" to "Últimos episódios",
        "ultimos-titulos-adicionados" to "Últimos animes"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val isHome = request.data.isBlank()
        val url = if (isHome || page <= 0) {
            if (isHome) mainUrl else "$mainUrl/${request.data}/"
        } else {
            "$mainUrl/${request.data}/page/${page + 1}/"
        }
        val selector = if (isHome) ".itemE, .itemA" else ".itemE"

        val items = try {
            app.get(url, headers = headers, timeout = 30).document
                .select(selector)
                .mapNotNull { it.asSearchResponse() }
                .distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }

        return newHomePageResponse(request.name, items, !isHome && items.isNotEmpty())
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim().replace(Regex("""\s+"""), "%20")
        if (term.isBlank()) return emptyList()

        val results = mutableListOf<SearchResponse>()
        var nextUrl: String? = "$mainUrl/pesquisa/$term/"
        var pages = 0

        while (!nextUrl.isNullOrBlank() && pages < MAX_SEARCH_PAGES) {
            val doc = try {
                app.get(nextUrl, headers = headers, timeout = 30).document
            } catch (e: Exception) {
                break
            }
            doc.select(".itemA").mapNotNull { it.asSearchResponse() }.forEach { results.add(it) }
            nextUrl = doc.selectFirst(".content-pagination li.next a[href]")
                ?.attr("href")?.takeIf { it.isNotBlank() && it != "#" }
                ?.let(::absoluteUrl)
            pages++
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url, headers = headers, timeout = 40).document

        // Cards de episodio da home/listas apontam para /video/a/<id>(): resolvimos a ficha.
        if (url.contains("/video/a/")) {
            val animeUrl = doc.selectFirst(".linksEP .epsL a[href*='/anime/a/']")
                ?.attr("href")?.takeIf { it.isNotBlank() }?.let(::absoluteUrl)
            if (!animeUrl.isNullOrBlank() && animeUrl != url) return load(animeUrl)

            val title = doc.selectFirst("#anime_title")?.text()?.trim()?.let(::tidyTitle)
                ?: "Episódio"
            val number = episodeNumber(title)
            val episodes = listOf(newEpisode(url) {
                this.name = title
                this.season = detectSeason(title)
                this.episode = number
                this.posterUrl = doc.selectFirst("#thumbHis")?.attr("src")?.let(::absoluteUrl)
            })
            return newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.plot = doc.selectFirst("meta[itemprop=description]")?.attr("content")
            }
        }

        val title = doc.selectFirst(".animeInfos h1")?.text()?.trim()?.let(::tidyTitle)
            ?: doc.selectFirst("h1")?.text()?.trim()?.let(::tidyTitle)
            ?: "Anime"
        val poster = doc.selectFirst(".animeInfos .poster img[src]")?.attr("src")
            ?.takeIf { it.isNotBlank() }?.let(::absoluteUrl)
        val plot = doc.selectFirst(".animeInfos .sinopse")?.text()?.trim()
        val tags = doc.select(".animeInfos .genre a").map { it.text().trim() }.filter { it.isNotBlank() }
        val year = doc.select(".animeInfos .info")
            .firstOrNull { it.text().contains("Ano", ignoreCase = true) }
            ?.let { firstNumber(it.text()) }
            ?.takeIf { it in 1900..2100 }
        val type = if (Regex("(filme|movie|o filme)", RegexOption.IGNORE_CASE).containsMatchIn(title)) {
            TvType.AnimeMovie
        } else {
            TvType.Anime
        }

        val episodes = fetchAllEpisodes(url, doc)
        return newTvSeriesLoadResponse(title, url, type, episodes) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = tags
        }
    }

    /** Le TODAS as paginas de episodios da ficha (ex.: One Piece tem 25 paginas). */
    private suspend fun fetchAllEpisodes(firstUrl: String, firstDoc: Document): List<Episode> {
        val links = firstDoc.select(".content-pagination li a[href*='/page/']")
            .mapNotNull { it.attr("href").takeIf { h -> h.isNotBlank() && h != "#" }?.let(::absoluteUrl) }
        val base = links.firstOrNull()?.substringBefore("/page/") ?: firstUrl
        val last = links.mapNotNull { pageRegex.find(it)?.groupValues?.get(1)?.toIntOrNull() }
            .maxOrNull() ?: 1
        val pageUrls = mutableListOf(firstUrl)
        for (p in 2..minOf(last, MAX_EPISODE_PAGES)) {
            val candidate = "$base/page/$p/"
            if (candidate !in pageUrls) pageUrls.add(candidate)
        }

        val documents = coroutineScope {
            pageUrls.mapIndexed { index, pageUrl ->
                async(Dispatchers.IO) {
                    if (index == 0) {
                        firstDoc
                    } else {
                        try {
                            app.get(pageUrl, headers = headers, timeout = 40).document
                        } catch (e: Exception) {
                            null
                        }
                    }
                }
            }.awaitAll()
        }

        val fallbackSeason = detectSeason(
            firstDoc.selectFirst(".animeInfos h1")?.text().orEmpty()
        )

        return documents.filterNotNull()
            .flatMap { doc ->
                doc.select(".itens_ep .item_ep a[href*='/video/a/']").mapNotNull { a ->
                    val href = a.attr("href").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    val data = absoluteUrl(href)
                    val alt = a.selectFirst("img")?.attr("alt").orEmpty()
                    val name = a.selectFirst(".title_anime")?.text()?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: alt.takeIf { it.isNotBlank() }?.let(::tidyTitle)
                        ?: "Episódio"
                    val number = episodeNumber(name) ?: episodeNumber(alt)

                    newEpisode(data) {
                        this.name = name
                        this.season = detectSeason(name).takeIf { it != 1 } ?: fallbackSeason
                        this.episode = number
                        this.posterUrl = a.selectFirst("img")?.attr("src")
                            ?.takeIf { it.isNotBlank() }?.let(::absoluteUrl)
                    }
                }
            }
            .distinctBy { it.data }
            .sortedWith(compareBy<Episode>({ it.season ?: 0 }, { it.episode ?: 0 }, { it.data }))
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.isBlank()) return false
        return try {
            val doc = app.get(data, headers = headers, timeout = 30).document
            val players = doc.select(".tab-video iframe[src]")
            if (players.isEmpty()) return false

            var found = false
            players.forEachIndexed { index, iframe ->
                val src = absoluteUrl(iframe.attr("src"))
                val playerId = iframe.parent()?.id().orEmpty()
                val label = doc.selectFirst(".tabs_videos li[data-tab='#$playerId']")
                    ?.text()?.trim()?.takeIf { it.isNotBlank() }
                    ?: "Player ${index + 1}"
                if (resolvePlayer(src, label, callback, 0)) found = true
            }
            found
        } catch (e: Exception) {
            false
        }
    }

    /** O Player FHD entrega um JWPlayer com file: "...m3u8"; o Player 2 embute um Blogger. */
    private suspend fun resolvePlayer(
        iframeUrl: String,
        label: String,
        callback: (ExtractorLink) -> Unit,
        depth: Int
    ): Boolean {
        if (depth > 2) return false
        return try {
            val body = app.get(iframeUrl, headers = iframeHeaders, timeout = 30).text
            var emitted = false

            fileRegex.findAll(body).forEach { match ->
                val stream = match.groupValues[1]
                    .replace("\\/", "/")
                    .replace("\\u0026", "&")
                    .replace("\\u003d", "=")
                if (!stream.startsWith("http", ignoreCase = true)) return@forEach
                callback(
                    newExtractorLink(name, "$name $label", stream, linkType(stream)) {
                        this.referer = "$mainUrl/"
                        this.headers = headers
                    }
                )
                emitted = true
            }
            if (emitted) return true

            val nested = nestedIframeRegex.find(body)?.groupValues?.get(1)
                ?.replace("&amp;", "&")
            if (!nested.isNullOrBlank()) {
                val nestedUrl = absoluteUrl(nested)
                if (nestedUrl.contains("blogger.com/video.g", ignoreCase = true)) {
                    return resolveBlogger(nestedUrl, label, callback)
                }
                if (nestedUrl != iframeUrl) return resolvePlayer(nestedUrl, label, callback, depth + 1)
            }

            // Ultimo recurso: devolve o proprio iframe para o player do app.
            callback(
                newExtractorLink(name, "$name $label", iframeUrl, ExtractorLinkType.VIDEO) {
                    this.referer = "$mainUrl/"
                    this.headers = iframeHeaders
                }
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    /** Resolve o token do Blogger via RPC WcwnYd e devolve os URLs googlevideo. */
    private suspend fun resolveBlogger(
        url: String,
        label: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val token = tokenRegex.find(url)?.groupValues?.get(1) ?: return false
            val page = app.get(url, headers = headers, timeout = 25).text
            val sid = sidRegex.find(page)?.groupValues?.get(1) ?: return false
            val bl = blRegex.find(page)?.groupValues?.get(1) ?: return false

            val fReq = """[[["WcwnYd","[\"$token\",\"\",0]",null,"generic"]]]"""
            val endpoint = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute" +
                "?rpcids=WcwnYd&source-path=%2Fvideo.g&f.sid=$sid&bl=$bl" +
                "&hl=pt-BR&_reqid=${(10000..99999).random()}&rt=c"
            val postHeaders = mapOf(
                "User-Agent" to UA,
                "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                "Origin" to "https://www.blogger.com",
                "Referer" to "https://www.blogger.com/",
                "x-same-domain" to "1"
            )

            var raw = app.post(endpoint, headers = postHeaders, data = mapOf("f.req" to fReq), timeout = 25).text
            if (!raw.contains("googlevideo", ignoreCase = true)) {
                val encoded = "%5B%5B%5B%22WcwnYd%22%2C%22%5B%5C%22$token%5C%22%2C%5C%22%5C%22%2C0%5D%22" +
                    "%2Cnull%2C%22generic%22%5D%5D%5D"
                raw = app.post(endpoint, headers = postHeaders, data = mapOf("f.req" to encoded), timeout = 25).text
            }

            val clean = raw
                .replace("\\u003d", "=")
                .replace("\\u0026", "&")
                .replace("\\/", "/")
                .replace("\\\"", "\"")
                .replace("\\=", "=")
                .replace("\\&", "&")

            val seen = HashSet<String>()
            var received = false
            googleVideoRegex.findAll(clean).forEach { match ->
                val stream = match.value.trimEnd(']', '}', ',', '"', '\\')
                if (!stream.startsWith("http") || !seen.add(stream)) return@forEach
                val quality = itagRegex.find(stream)?.groupValues?.get(1)?.toIntOrNull()
                    ?.let { qualities[it] } ?: 360
                callback(
                    newExtractorLink(name, "$name $label ${quality}p", stream, ExtractorLinkType.VIDEO) {
                        this.quality = quality
                        this.referer = "https://www.blogger.com/"
                        this.headers = mapOf(
                            "User-Agent" to UA,
                            "Referer" to "https://www.blogger.com/",
                            "Accept" to "*/*"
                        )
                    }
                )
                received = true
            }
            received
        } catch (e: Exception) {
            false
        }
    }
}
