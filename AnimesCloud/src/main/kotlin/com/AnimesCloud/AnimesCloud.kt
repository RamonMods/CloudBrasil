package com.AnimesCloud

import android.util.Base64
import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.Score
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale

/**
 * AnimesCloud — provider original para o catálogo AnimesDrive (https://animesdrive.cloud).
 *
 * Fluxo descoberto no site (26/11/2026):
 *  - catálogo HTML: /anime, /filme, /episodio (paginação /page/N)
 *  - busca JSON: /wp-json/dooplay/search/?keyword=...&nonce=... (nonce em dtGonza no HTML)
 *  - ficha da série: /anime/<slug> (todas as temporadas em #seasons .se-c)
 *  - ficha do episódio: /episodio/<slug> (post ID em data-post-id / data-postid)
 *  - player: /wp-json/dooplayer/v2/<postId>/movie/<1|2|3>
 *    (o tipo da rota é "movie" no singular, inclusive para episódios)
 */
class AnimesCloud : MainAPI() {
    override var mainUrl = "https://animesdrive.cloud"
    override var name = "AnimesCloud"
    override var lang = "pt-br"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "/anime" to "Animes",
        "/filme" to "Filmes e Anime Movies",
        "/episodio" to "Episódios recentes",
    )

    private val siteHeaders: Map<String, String>
        get() = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/json;q=0.9,*/*;q=0.8",
            "Referer" to "$mainUrl/",
        )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val section = request.data.trim('/')
        val url = "$mainUrl/$section" + if (page > 1) "/page/$page" else ""
        val doc = fetchDocument(url)
            ?: return newHomePageResponse(request.name, emptyList<SearchResponse>(), hasNext = false)

        val items = when (section) {
            "episodio" -> doc.select("article.item.episodes").mapNotNull { it.toEpisodeCard() }
            "filme" -> doc.select("article.item.movies").mapNotNull { it.toCatalogCard(TvType.AnimeMovie) }
            else -> doc.select("article.item.tvshows").mapNotNull { it.toCatalogCard(TvType.Anime) }
        }

        val hasNext = doc.selectFirst("a.arrow_pag") != null ||
            doc.selectFirst("link[rel=next]") != null
        return newHomePageResponse(request.name, items, hasNext)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val term = query.trim()
        if (term.isEmpty()) return emptyList()

        val found = LinkedHashMap<String, SearchResponse>()
        val nonce = readSearchNonce()
        if (nonce != null) {
            val endpoint = "$mainUrl/wp-json/dooplay/search/?keyword=${encode(term)}&nonce=$nonce"
            val raw = fetchText(endpoint)
            if (!raw.isNullOrBlank()) {
                try {
                    val root = JSONObject(raw)
                    if (!root.has("error")) {
                        for (key in root.keys()) {
                            val item = root.optJSONObject(key) ?: continue
                            val title = item.optString("title", "").trim()
                            val link = absolute(item.optString("url", "")) ?: continue
                            if (title.isEmpty()) continue
                            val type = if (link.contains("/filme/")) TvType.AnimeMovie else TvType.Anime
                            val score = item.optJSONObject("extra")?.optString("imdb", "")?.toFloatOrNull()
                            val year = item.optJSONObject("extra")?.optString("date", "")
                                ?.let { Regex("""\b(19|20)\d{2}\b""").find(it)?.value?.toIntOrNull() }
                            val result = newAnimeSearchResponse(title, link, type) {
                                posterUrl = absolute(item.optString("img", ""))
                                this.year = year
                                if (score != null) this.score = Score.from10(score)
                            }
                            found.putIfAbsent(result.url, result)
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        // A busca WordPress tradicional também devolve fichas de série/filme.
        if (found.isEmpty()) {
            val doc = fetchDocument("$mainUrl/?s=${encode(term)}") ?: return emptyList()
            for (card in doc.select("article.item")) {
                val result = card.toCatalogCard(
                    if (card.hasClass("movies")) TvType.AnimeMovie else TvType.Anime,
                ) ?: continue
                found.putIfAbsent(result.url, result)
            }
        }
        return found.values.toList()
    }

    override suspend fun load(url: String): com.lagradost.cloudstream3.LoadResponse? {
        val clean = url.substringBefore("?")
        return when {
            clean.contains("/episodio/") -> loadFromEpisodePage(clean)
            clean.contains("/filme/") -> loadMovie(clean)
            clean.contains("/anime/") -> loadSeries(clean)
            else -> null
        }
    }

    private suspend fun loadFromEpisodePage(url: String): com.lagradost.cloudstream3.LoadResponse? {
        val doc = fetchDocument(url) ?: return null
        val parent = doc.selectFirst("#doo-breadcrumbs a[href*='/anime/'], #doo-breadcrumbs a[href*='/filme/']")
            ?.let { absolute(it.attr("href")) }
        if (parent != null && parent != url) return load(parent)

        // Episódio órfão: vira uma ficha de um único episódio apontando para ele mesmo.
        val title = doc.selectFirst("[data-post-series]")?.attr("data-post-series")?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: name
        return newAnimeLoadResponse(title, url, TvType.Anime) {
            addEpisodes(DubStatus.None, listOf(newEpisode(url) { this.name = title }))
        }
    }

    private suspend fun loadSeries(url: String): com.lagradost.cloudstream3.LoadResponse? {
        val doc = fetchDocument(url) ?: return null
        val title = doc.selectFirst("h1[itemprop=name]")?.text()?.trim()
            ?: doc.selectFirst(".data h1")?.text()?.trim()
            ?: return null

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            applyCommonDetails(this, doc)
            addEpisodes(DubStatus.None, parseSeasonEpisodes(doc))
        }
    }

    private suspend fun loadMovie(url: String): com.lagradost.cloudstream3.LoadResponse? {
        val doc = fetchDocument(url) ?: return null
        val title = doc.selectFirst("h1[itemprop=name]")?.text()?.trim()
            ?: doc.selectFirst(".data h1")?.text()?.trim()
            ?: doc.selectFirst("h1")?.text()?.trim()
            ?: return null

        val episodes = parseSeasonEpisodes(doc).ifEmpty {
            listOf(newEpisode(url) { this.name = "Filme completo" })
        }
        return newAnimeLoadResponse(title, url, TvType.AnimeMovie) {
            applyCommonDetails(this, doc)
            if (plot.isNullOrBlank()) {
                plot = doc.selectFirst("#info .wp-content")?.text()
                    ?.substringAfter("Sinopse:")
                    ?.substringBefore("Ano de Lançamento:")
                    ?.trim()
            }
            addEpisodes(DubStatus.None, episodes)
        }
    }

    private fun applyCommonDetails(target: com.lagradost.cloudstream3.AnimeLoadResponse, doc: Document) {
        target.posterUrl = absolute(
            doc.selectFirst(".sheader .poster img[itemprop=image]")?.attr("src")
                ?: doc.selectFirst(".poster img")?.attr("src"),
        )
        val backdropStyle = doc.selectFirst(".sheader")?.attr("style").orEmpty()
        val backdrop = Regex("""url\(['"]?([^'")]+)['"]?\)""")
            .find(backdropStyle)?.groupValues?.get(1)
        target.backgroundPosterUrl = absolute(backdrop)

        val overview = doc.selectFirst(".animeq-single-overview")?.text()?.trim()
        if (!overview.isNullOrBlank()) target.plot = overview

        val tags = doc.select(".sgeneros a[rel=tag]").mapNotNull { it.text().trim().takeIf { v -> v.isNotEmpty() } }
        if (tags.isNotEmpty()) target.tags = tags.distinct()

        val metaText = doc.select(".animeq-single-meta-pills span").joinToString(" ") { it.text() }
        val yearText = if (metaText.isNotBlank()) metaText else doc.selectFirst("#info")?.text().orEmpty()
        target.year = Regex("""\b(19|20)\d{2}\b""").find(yearText)?.value?.toIntOrNull()

        val scoreText = doc.selectFirst(".animeq-score-box strong")?.text()?.trim()
            ?: doc.selectFirst(".dt_rating_vgs")?.text()?.trim()
        scoreText?.toFloatOrNull()?.let { target.score = Score.from10(it) }
    }

    /**
     * Todas as temporadas estão na mesma ficha (#seasons .se-c). O número da temporada vem
     * de .se-t ("1", "2", "ep" para especiais); episódios especiais como "1108-5" usam
     * apenas a parte inteira no campo [episode] e o valor completo no nome.
     */
    private fun parseSeasonEpisodes(doc: Document): List<Episode> {
        val unique = LinkedHashMap<String, Episode>()
        for (block in doc.select("#seasons .se-c")) {
            val seasonNumber = block.selectFirst(".se-t")?.text()?.trim()?.toIntOrNull() ?: 0
            for (card in block.select(".episode-card")) {
                val episodeUrl = absolute(card.selectFirst("a[href]")?.attr("href")) ?: continue
                val rawNumber = card.attr("data-episode-number").trim()
                val number = Regex("""\d+""").find(rawNumber)?.value?.toIntOrNull() ?: 0
                val label = card.selectFirst(".episode-title")?.text()?.trim()
                    ?: card.attr("data-episode-title").trim().ifBlank { null }
                    ?: "Episódio ${rawNumber.ifBlank { number.toString() }}"
                val thumb = absolute(card.selectFirst("img")?.attr("src"))
                val s = seasonNumber
                val e = number
                unique.putIfAbsent(
                    episodeUrl,
                    newEpisode(episodeUrl) {
                        this.name = label
                        this.season = s
                        this.episode = e
                        this.posterUrl = thumb
                    },
                )
            }
        }
        return unique.values.sortedWith(
            compareBy<Episode>({ it.season ?: 0 }, { it.episode ?: 0 }, { it.name.orEmpty() }),
        )
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (com.lagradost.cloudstream3.SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val doc = fetchDocument(data.substringBefore("?")) ?: return false
        val ids = LinkedHashSet<String>()
        doc.select("[data-post-id]").forEach { ids.add(it.attr("data-post-id").trim()) }
        doc.select("[data-postid]").forEach { ids.add(it.attr("data-postid").trim()) }
        doc.select("[data-post]").forEach { ids.add(it.attr("data-post").trim()) }
        doc.select("meta[id=dooplay-ajax-counter]").forEach { ids.add(it.attr("data-postid").trim()) }

        val labels = arrayOf("SD", "HD", "FHD")
        for (id in ids) {
            if (!id.matches(Regex("""\d+"""))) continue
            var foundForId = false
            for (nume in 1..3) {
                val raw = fetchText("$mainUrl/wp-json/dooplayer/v2/$id/movie/$nume") ?: continue
                val player = try {
                    JSONObject(raw)
                } catch (_: Exception) {
                    continue
                }
                val embed = player.optString("embed_url", "").trim()
                if (embed.isEmpty()) continue
                val type = player.optString("type", "").lowercase(Locale.ROOT)
                val label = labels.getOrElse(nume - 1) { "Player $nume" }

                when (type) {
                    "mp4" -> {
                        val source = URLDecoder.decode(
                            embed.substringAfter("source=", "").substringBefore("&"),
                            "UTF-8",
                        )
                        if (!source.startsWith("http")) continue
                        callback(
                            newExtractorLink(name, "$name - $label", source, ExtractorLinkType.VIDEO) {
                                referer = "$mainUrl/"
                                quality = Qualities.Unknown.value
                            },
                        )
                        foundForId = true
                    }

                    "iframe" -> {
                        if (!embed.contains("blogger.com/video.g")) continue
                        for ((videoUrl, quality) in resolveBlogger(embed)) {
                            callback(
                                newExtractorLink(name, "$name - $label", videoUrl, ExtractorLinkType.VIDEO) {
                                    referer = "https://youtube.googleapis.com/"
                                    this.quality = quality
                                },
                            )
                            foundForId = true
                        }
                    }
                }
            }
            if (foundForId) return true
        }
        return false
    }

    /** Resolve o iframe do Blogger para URLs de vídeo reproduzíveis pelo CloudStream. */
    private suspend fun resolveBlogger(embedUrl: String): List<Pair<String, Int>> {
        val token = Regex("""[?&]token=([^&]+)""").find(embedUrl)?.groupValues?.get(1) ?: return emptyList()
        val page = fetchText("https://www.blogger.com/video.g?token=$token") ?: return emptyList()

        fun field(key: String): String? =
            Regex("""\"$key\":\"([^\"]+)\"""").find(page)?.groupValues?.get(1)

        val sid = field("FdrFJe") ?: return emptyList()
        val build = field("cfb2h") ?: return emptyList()
        val uu = field("UUFaWc") ?: "%.@.null,1000,2]"
        val hs = field("hsFLT") ?: "%.@.null,10,3]"
        val nonce = Regex("""script[^>]*nonce="([^"]+)"""").find(page)?.groupValues?.get(1).orEmpty()
        val reqid = (10000..99999).random()

        val endpoint = "https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute" +
            "?rpcids=WcwnYd&source-path=%2Fvideo.g&f.sid=$sid&bl=$build" +
            "&hl=pt-BR&_reqid=$reqid&rt=c"
        val payload = """[[["WcwnYd","[\"$token\",\"\",0]",null,"generic"]]]"""
        val response = try {
            app.post(
                endpoint,
                headers = mapOf(
                    "User-Agent" to USER_AGENT,
                    "Origin" to "https://www.blogger.com",
                    "Referer" to "https://www.blogger.com/",
                    "Content-Type" to "application/x-www-form-urlencoded;charset=UTF-8",
                    "x-same-domain" to "1",
                ),
                data = mapOf("f.req" to payload),
            ).text
        } catch (_: Exception) {
            return emptyList()
        }

        val decoded = response
            .replace(Regex("""^\)\]\}'\s*\n?"""), "")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
            .replace("\\u003d", "=")
            .replace("\\u0026", "&")
            .replace("\\/", "/")

        val results = LinkedHashMap<String, Int>()
        val now = System.currentTimeMillis()
        val urlRegex = Regex("""https://[A-Za-z0-9.\-]+googlevideo\.com/videoplayback\?[^"\\ ]+""")
        for (match in urlRegex.findAll(decoded)) {
            var videoUrl = match.value.trimEnd('\\', '"', ',', ']')
            val itag = Regex("""[?&]itag=(\d+)""").find(videoUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 18
            val videoId = Regex("""[?&]id=([a-f0-9]+)""").find(videoUrl)?.groupValues?.get(1)
                ?: continue
            val cpn = makeCpn(build, uu, hs, videoId, now, nonce)
            val separator = if (videoUrl.contains("?")) "&" else "?"
            videoUrl += "${separator}cpn=$cpn&c=WEB_EMBEDDED_PLAYER&cver=1.20260224.08.00"
            val quality = when (itag) {
                37 -> 1080
                22 -> 720
                59 -> 480
                else -> 360
            }
            results.putIfAbsent(videoUrl, quality)
        }
        return results.toList()
    }

    private fun makeCpn(
        build: String,
        uu: String,
        hs: String,
        videoId: String,
        timestamp: Long,
        nonce: String,
    ): String {
        val seed = build + uu + hs + videoId + timestamp.toString() + nonce
        val digest = MessageDigest.getInstance("SHA-256").digest(seed.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest, Base64.NO_WRAP)
            .take(16)
            .replace(Regex("""[+/=]"""), "")
    }

    private suspend fun readSearchNonce(): String? {
        val html = fetchText("$mainUrl/anime") ?: return null
        return Regex("""var dtGonza\s*=\s*\{[^}]*?"nonce":"([a-f0-9]+)"""")
            .find(html)?.groupValues?.get(1)
            ?: Regex(""""nonce":"([a-f0-9]+)"""").find(html)?.groupValues?.get(1)
    }

    private suspend fun fetchText(url: String): String? = try {
        app.get(url, headers = siteHeaders).text.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    private suspend fun fetchDocument(url: String): Document? =
        fetchText(url)?.let { Jsoup.parse(it, url) }

    private fun Element.toCatalogCard(type: TvType): SearchResponse? {
        val link = selectFirst("h3 a") ?: selectFirst(".poster a[href]") ?: return null
        val title = link.text().trim().ifBlank { return null }
        val href = absolute(link.attr("href")) ?: return null
        val score = selectFirst(".rating")?.text()?.trim()?.toFloatOrNull()
        return newAnimeSearchResponse(title, href, type) {
            posterUrl = absolute(selectFirst("img")?.attr("src"))
            if (score != null) this.score = Score.from10(score)
        }
    }

    private fun Element.toEpisodeCard(): SearchResponse? {
        val link = selectFirst(".data h3 a") ?: selectFirst(".poster a[href]") ?: return null
        val title = link.text().trim().ifBlank { return null }
        val href = absolute(link.attr("href")) ?: return null
        return newAnimeSearchResponse(title, href, TvType.Anime) {
            posterUrl = absolute(selectFirst("img")?.attr("src"))
        }
    }

    private fun absolute(raw: String?): String? {
        val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val normalized = if (value.startsWith("//")) "https:$value" else value
        return try {
            val resolved = URI(mainUrl).resolve(normalized).toString()
            if (resolved.startsWith("http")) resolved else null
        } catch (_: Exception) {
            if (normalized.startsWith("http")) normalized else null
        }
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
