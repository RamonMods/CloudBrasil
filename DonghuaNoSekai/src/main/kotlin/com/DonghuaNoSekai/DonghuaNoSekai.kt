package com.DonghuaNoSekai

import android.util.Base64
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jsoup.Jsoup
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Provider original para o Donghua No Sekai (WordPress, pt-BR).
 *
 * Endpoints validados com curl (UA de navegador):
 *  - GET  /wp-json/wp/v2/posts?per_page=20&page=N   -> episódios recentes (REST)
 *  - GET  /wp-json/site/search/?keyword=X&nonce=Y   -> busca de títulos (REST, nonce da home)
 *  - POST /wp-admin/admin-ajax.php (getListFilter)  -> listagem paginada/busca (API do tema)
 *  - GET  /player?file=<base64 do post ID>&type=N   -> página que resolve o stream
 *
 * HTML é usado apenas onde a API não expõe os dados (lista de episódios da
 * série, botões de player e iframe final).
 */
class DonghuaNoSekai : MainAPI() {
    override var mainUrl = "https://donghuanosekai.com"
    override var name = "DonghuaNoSekai"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.AsianDrama)
    override val usesWebView = false

    override val mainPage = mainPageOf(
        "name" to "Todos os Donghuas",
        "new" to "Lançamentos"
    )

    companion object {
        const val INTERNAL_NAME = "DonghuaNoSekai"

        private const val UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

        private val browserHeaders = mapOf(
            "User-Agent" to UA,
            "Accept-Language" to "pt-BR,pt;q=0.9",
            "Referer" to "https://donghuanosekai.com/"
        )

        private val ajaxHeaders = browserHeaders + mapOf(
            "X-Requested-With" to "XMLHttpRequest",
            "Origin" to "https://donghuanosekai.com"
        )

        // Token encontrado em data-secury da página /donghuas. É renovado a cada sessão.
        private const val FALLBACK_LIST_TOKEN = "d01c3e1cc8"

        private val listTokenMutex = Mutex()
        private val nonceMutex = Mutex()
        private var cachedListToken: String? = null
        private var cachedNonce: String? = null

        private val seasonMarkerRegex = Regex("\\((\\d+)\\s*[Ss](\\d+)\\)")
        private val ordinalRegex = Regex("(\\d+)\\s*[ªº]")
        private val writtenSeasonRegex = Regex("[Tt]emporada\\s*(\\d+)")
        private val episodeNumberRegex = Regex("(?:[Ee]pis[óo]dio|Ep\\.?)\\s*(\\d+)")
        private val qualityRegex = Regex("(\\d{3,4})p")

        private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

        private fun decodeParam(value: String): String {
            return if (value.contains('%')) URLDecoder.decode(value, "UTF-8") else value
        }

        private fun cleanText(raw: String?): String {
            return raw?.takeIf { it.isNotBlank() }?.let { Jsoup.parse(it).text().trim() }.orEmpty()
        }

        /** URL absoluta, mesmo quando o HTML vem com href relativo ou protocol-relative. */
        private fun absolutize(href: String?, base: String = "https://donghuanosekai.com"): String? {
            val value = href?.trim().orEmpty()
            if (value.isBlank()) return null
            return when {
                value.startsWith("http", ignoreCase = true) -> value
                value.startsWith("//") -> "https:$value"
                value.startsWith("/") -> "https://donghuanosekai.com$value"
                else -> null
            }
        }

        private fun slugOf(url: String): String {
            return url.substringBefore('?').substringBefore('#').trimEnd('/').substringAfterLast('/')
        }

        /** "Episódio 28 - (12 S2)" ->season 2 / episódio 12; sem marcação usa o número do rótulo. */
        private fun parseEpisode(label: String, fallbackSeason: Int, fallbackEpisode: Int): Pair<Int, Int> {
            val marker = seasonMarkerRegex.find(label)
            if (marker != null) {
                val ep = marker.groupValues[1].toIntOrNull()
                val season = marker.groupValues[2].toIntOrNull()
                if (ep != null && season != null && season > 0) return season to ep
            }

            val number = episodeNumberRegex.find(label)?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("(\\d+)").find(label)?.groupValues?.get(1)?.toIntOrNull()
                ?: fallbackEpisode

            val season = fallbackSeason.takeIf { it > 0 } ?: 1
            return season to number
        }

        /**
         * Séries com uma única temporada no título ("4ª Temporada") ancoram os
         * episódios sem marcação. Títulos com várias temporadas (1ª e 2ª) ficam
         * na temporada 1 e confiam nas marcações "(N Sx)" de cada episódio.
         */
        private fun seasonFromTitle(title: String): Int {
            val clean = cleanText(title)
            if (!clean.contains("temporada", ignoreCase = true)) return 1

            val values = (
                ordinalRegex.findAll(clean).mapNotNull { it.groupValues[1].toIntOrNull() } +
                    writtenSeasonRegex.findAll(clean).mapNotNull { it.groupValues[1].toIntOrNull() }
                ).toSet()
            return if (values.size == 1) values.first() else 1
        }

        private fun qualityOf(url: String): Int {
            return qualityRegex.find(url.substringBefore('?'))?.groupValues?.get(1)?.toIntOrNull() ?: 0
        }

        private fun mediaType(url: String): ExtractorLinkType {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return if (path.endsWith(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        }
    }

    // ----------------------------------------------------------------------
    // Tokens/API do tema
    // ----------------------------------------------------------------------

    private suspend fun listToken(): String {
        cachedListToken?.let { return it }
        return listTokenMutex.withLock {
            cachedListToken ?: run {
                val fetched = try {
                    val html = app.get("$mainUrl/donghuas", headers = browserHeaders, timeout = 30).text
                    Regex("data-secury=\"([^\"]+)\"").find(html)?.groupValues?.get(1)
                } catch (e: Exception) {
                    null
                }
                val token = fetched?.takeIf { it.isNotBlank() } ?: FALLBACK_LIST_TOKEN
                cachedListToken = token
                token
            }
        }
    }

    private suspend fun searchNonce(): String? {
        cachedNonce?.let { return it }
        return nonceMutex.withLock {
            cachedNonce ?: run {
                val fetched = try {
                    val html = app.get(mainUrl, headers = browserHeaders, timeout = 30).text
                    Regex("var pAPI\\s*=\\s*\\{[^}]*\"nonce\"\\s*:\\s*\"([^\"]+)\"")
                        .find(html)?.groupValues?.get(1)
                } catch (e: Exception) {
                    null
                }
                fetched?.let { cachedNonce = it }
                fetched
            }
        }
    }

    private fun listBody(token: String, page: Int, order: String, search: String): Map<String, String> {
        val filters = "{\"filter_data\":" +
            "\"filter_letter=0&filter_status=all&filter_animation=all&filter_audio=all&filter_order=$order\"," +
            "\"filter_genre_add\":\"\",\"filter_genre_del\":\"\"}"

        return mapOf(
            "action" to "getListFilter",
            "token" to token,
            "pagina" to page.toString(),
            "search" to search,
            "limit" to "30",
            "type" to "lista",
            "filters" to filters
        )
    }

    private suspend fun fetchList(page: Int, order: String, search: String = "0"): ListAjaxResponse? {
        val token = listToken()
        return try {
            app.post(
                "$mainUrl/wp-admin/admin-ajax.php",
                headers = ajaxHeaders,
                data = listBody(token, page, order, search),
                timeout = 35
            ).parsedSafe<ListAjaxResponse>()
        } catch (e: Exception) {
            null
        }
    }

    // ----------------------------------------------------------------------
    // Conversão de cards
    // ----------------------------------------------------------------------

    private fun parseCard(fragment: String): SearchResponse? {
        val doc = Jsoup.parse(fragment, mainUrl)
        val card = doc.selectFirst("div.itemE.capa, div.itemS") ?: doc
        val anchor = card.selectFirst("a[href]") ?: return null

        val url = absolutize(anchor.attr("abs:href").ifBlank { anchor.attr("href") }) ?: return null
        val rawTitle = card.selectFirst("h3 span.sxcd, h2, h1")?.text()
            ?: anchor.attr("title")
                .removePrefix("Assistir ")
                .removeSuffix(" Online em HD")
        val title = cleanText(rawTitle).ifBlank { return null }
        val poster = card.selectFirst("img[src]")?.let {
            absolutize(it.attr("abs:src").ifBlank { it.attr("src") })
        }

        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
        }
    }

    private fun apiSearchItemToResponse(item: ApiSiteSearchItem): SearchResponse? {
        val title = cleanText(item.title).ifBlank { return null }
        val url = absolutize(item.url) ?: return null
        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = absolutize(item.img)
        }
    }

    // ----------------------------------------------------------------------
    // MainAPI
    // ----------------------------------------------------------------------

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page < 1) return newHomePageResponse(request.name, emptyList(), false)

        val api = fetchList(page, request.data)
        val fromApi = api?.results.orEmpty().mapNotNull { parseCard(it) }
        if (fromApi.isNotEmpty()) {
            val hasNext = api?.totalPage?.let { page < it } ?: (fromApi.size >= 20)
            return newHomePageResponse(
                request.name,
                fromApi.distinctBy { it.url }.sortedBy { it.name.lowercase() },
                hasNext
            )
        }

        // Fallback HTML: a home é paginada em /page/N com cards div.itemS.
        val items = try {
            val doc = app.get("$mainUrl/page/$page", headers = browserHeaders, timeout = 35).document
            doc.select("div.itemS").mapNotNull { parseCard(it.outerHtml()) }
        } catch (e: Exception) {
            emptyList()
        }

        return newHomePageResponse(
            request.name,
            items.distinctBy { it.url }.sortedBy { it.name.lowercase() },
            items.size >= 20
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isBlank()) return emptyList()

        // 1) REST de busca do próprio site (precisa do nonce da home).
        val nonce = searchNonce()
        if (!nonce.isNullOrBlank()) {
            val results = try {
                app.get(
                    "$mainUrl/wp-json/site/search/",
                    params = mapOf("keyword" to term, "nonce" to nonce),
                    headers = browserHeaders,
                    timeout = 30
                ).parsedSafe<Map<String, ApiSiteSearchItem>>()
            } catch (e: Exception) {
                null
            }
            if (!results.isNullOrEmpty()) {
                return results.values.mapNotNull { apiSearchItemToResponse(it) }
                    .distinctBy { it.url }
                    .sortedBy { it.name.lowercase() }
            }
        }

        // 2) API AJAX do tema com filtro de busca.
        val ajax = fetchList(1, "name", term)
        val fromAjax = ajax?.results.orEmpty().mapNotNull { parseCard(it) }
        if (fromAjax.isNotEmpty()) {
            return fromAjax.distinctBy { it.url }.sortedBy { it.name.lowercase() }
        }

        // 3) Fallback HTML da busca nativa do WordPress.
        val fromHtml = try {
            val doc = app.get(
                "$mainUrl/?s=${encode(term)}",
                headers = browserHeaders,
                timeout = 35
            ).document
            doc.select("div.itemE.capa, div.itemS").mapNotNull { parseCard(it.outerHtml()) }
        } catch (e: Exception) {
            emptyList()
        }
        return fromHtml.distinctBy { it.url }.sortedBy { it.name.lowercase() }
    }

    override suspend fun load(url: String): LoadResponse {
        val normalized = absolutize(url) ?: url

        return try {
            val firstHtml = app.get(normalized, headers = browserHeaders, timeout = 40).text
            var doc = Jsoup.parse(firstHtml, normalized)
            var seriesUrl = normalized

            // Se veio um episódio direto, sobe para a página da série pelo breadcrumb.
            val isEpisodePage = doc.selectFirst("div#player") != null ||
                slugOf(normalized).contains("episodio")
            if (isEpisodePage) {
                val parent = doc.selectFirst("span.articleCat a[href]")?.let {
                    absolutize(it.attr("abs:href").ifBlank { it.attr("href") })
                }
                if (!parent.isNullOrBlank() && parent != normalized) {
                    seriesUrl = parent
                    doc = Jsoup.parse(
                        app.get(parent, headers = browserHeaders, timeout = 40).text,
                        parent
                    )
                }
            }

            val title = cleanText(doc.selectFirst("h1")?.text())
                .ifBlank {
                    cleanText(
                        doc.selectFirst("meta[property=og:title]")?.attr("content")
                            ?.substringBefore(" | ")
                    )
                }
                .ifBlank { "Donghua" }

            val poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
                ?.let { absolutize(it) }
                ?: doc.selectFirst("div.thumb img[src]")?.let {
                    absolutize(it.attr("abs:src").ifBlank { it.attr("src") })
                }
            val plot = cleanText(doc.selectFirst("div.articleContent div.context")?.text())

            val fallbackSeason = seasonFromTitle(title)
            var episodes = doc.select("div.episode_list div.item a[href]")
                .mapIndexedNotNull { index, anchor ->
                    val episodeUrl = absolutize(anchor.attr("abs:href").ifBlank { anchor.attr("href") })
                        ?: return@mapIndexedNotNull null
                    val label = cleanText(anchor.selectFirst("span.episode")?.text())
                    val (season, number) = parseEpisode(label, fallbackSeason, index + 1)
                    newEpisode(episodeUrl) {
                        this.name = label.ifBlank { "Episódio $number" }
                        this.season = season
                        this.episode = number
                    }
                }

            // Fallback REST: a API de posts cobre episódios publicados recentemente.
            if (episodes.isEmpty() && title != "Donghua") {
                episodes = fetchEpisodesFromRest(title, fallbackSeason)
            }

            val sortedEpisodes = episodes
                .distinctBy { it.data }
                .sortedWith(compareBy<Episode> { it.season ?: 1 }.thenBy { it.episode ?: 0 })

            newTvSeriesLoadResponse(title, seriesUrl, TvType.Anime, sortedEpisodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        } catch (e: Exception) {
            newTvSeriesLoadResponse("Erro ao carregar", normalized, TvType.Anime, emptyList()) {
                this.plot = "Não foi possível carregar os episódios deste título."
            }
        }
    }

    private suspend fun fetchEpisodesFromRest(title: String, fallbackSeason: Int): List<Episode> {
        val cleanTitle = cleanText(title)
        val result = mutableListOf<Episode>()

        for (page in 1..3) {
            val posts = try {
                app.get(
                    "$mainUrl/wp-json/wp/v2/posts",
                    params = mapOf(
                        "search" to cleanTitle,
                        "per_page" to "100",
                        "page" to page.toString(),
                        "_fields" to "id,link,title"
                    ),
                    headers = browserHeaders,
                    timeout = 35
                ).parsedSafe<List<ApiPost>>().orEmpty()
            } catch (e: Exception) {
                break
            }
            if (posts.isEmpty()) break

            posts.forEachIndexed { index, post ->
                val name = cleanText(post.title?.rendered)
                if (name.isBlank() || !name.contains(cleanTitle, ignoreCase = true)) return@forEachIndexed
                val link = absolutize(post.link) ?: return@forEachIndexed
                val (season, number) = parseEpisode(name, fallbackSeason, index + 1)
                result.add(
                    newEpisode(link) {
                        this.name = name
                        this.season = season
                        this.episode = number
                    }
                )
            }
        }
        return result
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodeUrl = absolutize(data) ?: return false

        return try {
            val doc = Jsoup.parse(
                app.get(episodeUrl, headers = browserHeaders, timeout = 40).text,
                episodeUrl
            )

            val players = doc.select("div.slideItem[data-video-url]").mapNotNull { element ->
                val playerUrl = absolutize(element.attr("data-video-url")) ?: return@mapNotNull null
                val playerName = cleanText(element.text()).ifBlank { "Player" }
                playerName to playerUrl
            }

            val finalPlayers = players.ifEmpty { playersFromRest(episodeUrl) }
            var found = false
            val seen = mutableSetOf<String>()

            finalPlayers.forEach { (playerName, playerUrl) ->
                val media = resolveMedia(playerUrl) ?: return@forEach
                if (!seen.add(media)) return@forEach
                val quality = qualityOf(media)
                val qualityLabel = if (quality > 0) "${quality}p" else ""
                val label = listOf(playerName, qualityLabel).filter { it.isNotBlank() }.joinToString(" ")

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = label,
                        url = media
                    ) {
                        this.type = mediaType(media)
                        this.quality = quality
                        this.referer = "$mainUrl/"
                    }
                )
                found = true
            }
            found
        } catch (e: Exception) {
            false
        }
    }

    /** Os botões Some dos episódios antigos; reconstrói os players a partir do post ID. */
    private suspend fun playersFromRest(episodeUrl: String): List<Pair<String, String>> {
        val slug = slugOf(episodeUrl)
        if (slug.isBlank()) return emptyList()

        val posts = try {
            app.get(
                "$mainUrl/wp-json/wp/v2/posts",
                params = mapOf("slug" to slug, "_fields" to "id"),
                headers = browserHeaders,
                timeout = 30
            ).parsedSafe<List<ApiPost>>().orEmpty()
        } catch (e: Exception) {
            return emptyList()
        }

        val id = posts.firstOrNull()?.id ?: return emptyList()
        val file = Base64.encodeToString(id.toString().toByteArray(), Base64.NO_WRAP)
        return listOf(
            "Player 1" to "$mainUrl/player?file=$file&type=0",
            "Player 2" to "$mainUrl/player?file=$file&type=1"
        )
    }

    /** Só aceita streams diretos; ignora players de terceiros (Blogger, Dailymotion, YouTube). */
    private fun isDirectMedia(url: String): Boolean {
        if (url.contains("dailymotion", ignoreCase = true) ||
            url.contains("blogger", ignoreCase = true) ||
            url.contains("youtube", ignoreCase = true)
        ) return false
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return path.endsWith(".m3u8") || path.endsWith(".mp4")
    }

    /**
     * A página /player encapsula o stream de três formas já vistas no site:
     *  - iframe para player-nativov2.php?v=<m3u8 codificado>
     *  - iframe antigo para player-new/?url=<m3u8 codificado>
     *  - player-new atual: DPlayer inicializa com url: "<m3u8>" (sem iframe)
     * Em seguida ainda há o iframe final com a mídia direta.
     */
    private suspend fun resolveMedia(playerUrl: String): String? {
        val html = app.get(
            playerUrl,
            headers = browserHeaders + mapOf("Referer" to "$mainUrl/"),
            timeout = 35
        ).text
        val doc = Jsoup.parse(html, playerUrl)

        // 1) iframe da página /player
        val iframeSrc = doc.selectFirst("iframe[src]")?.attr("abs:src")?.ifBlank { null }
        val fromIframe = when {
            iframeSrc == null -> null
            iframeSrc.contains("player-nativov2.php", ignoreCase = true) ->
                Regex("[?&]v=([^&]+)").find(iframeSrc)?.groupValues?.get(1)
            iframeSrc.contains("player-new", ignoreCase = true) ->
                Regex("[?&]url=([^&]+)").find(iframeSrc)?.groupValues?.get(1)
            else -> iframeSrc
        }?.let { decodeParam(it) }?.let { absolutize(it) }?.takeIf { isDirectMedia(it) }

        if (fromIframe != null) return fromIframe

        // 2) player-new sem iframe: a URL está no script do DPlayer
        val urlRegex = Regex(
            """["']url["']\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""",
            RegexOption.IGNORE_CASE
        )
        val fileRegex = Regex(
            """["']file["']\s*:\s*["']([^"']+\.(?:m3u8|mp4)[^"']*)["']""",
            RegexOption.IGNORE_CASE
        )
        val anyMediaRegex = Regex(
            """https?://[^"'\s<>]+\.(?:m3u8|mp4)[^"'\s<>]*""",
            RegexOption.IGNORE_CASE
        )

        return listOf(urlRegex, fileRegex, anyMediaRegex).firstNotNullOfOrNull { regex ->
            regex.find(html)?.groupValues?.get(1)
                ?.let { decodeParam(it) }
                ?.let { absolutize(it) }
                ?.takeIf { isDirectMedia(it) }
        }
    }
}

// ---------------------------------------------------------------------------
// Modelos (campos conferidos nas respostas reais)
// ---------------------------------------------------------------------------

private data class ListAjaxResponse(
    @JsonProperty("results") val results: List<String>? = null,
    @JsonProperty("total_page") val totalPage: Int? = null,
    @JsonProperty("total_results") val totalResults: Int? = null
)

private data class ApiSiteSearchItem(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("url") val url: String? = null,
    @JsonProperty("img") val img: String? = null,
    @JsonProperty("status") val status: String? = null
)

private data class ApiPost(
    @JsonProperty("id") val id: Int? = null,
    @JsonProperty("link") val link: String? = null,
    @JsonProperty("title") val title: ApiRenderedTitle? = null
)

private data class ApiRenderedTitle(
    @JsonProperty("rendered") val rendered: String? = null
)
