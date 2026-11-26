package com.AnimesFHD

import com.lagradost.cloudstream3.DubStatus
import com.lagradost.cloudstream3.Episode
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.LoadResponse.Companion.addMalId
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.ShowStatus
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.addDubStatus
import com.lagradost.cloudstream3.addEpisodes
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.newAnimeLoadResponse
import com.lagradost.cloudstream3.newAnimeSearchResponse
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newSubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone

/**
 * AnimesFHD (https://animesfhd.net) pela API JSON pública do site.
 * Cada temporada e cada áudio (dublado/legendado) é uma ficha no site; ao abrir uma,
 * todas as fichas de versions[] são agregadas e separadas entre Dub/Sub.
 */
class AnimesFHD : MainAPI() {
    override var mainUrl = "https://animesfhd.net"
    override var name = "AnimesFHD"
    override var lang = "pt-br"
    override val hasMainPage = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Anime, TvType.AnimeMovie)

    override val mainPage = mainPageOf(
        "sort=new_episodes&format=series" to "Lançamentos",
        "sort=popular&format=series" to "Populares",
        "sort=popular&cat=dublado&format=series" to "Dublados",
        "sort=popular&cat=filme" to "Filmes",
    )

    private val apiHeaders
        get() = mapOf("Referer" to "$mainUrl/", "Accept" to "application/json")

    private suspend inline fun <reified T> getJson(path: String): T? =
        tryParseJson<T>(app.get("$mainUrl$path", headers = apiHeaders).text)

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val data = getJson<SeriesPage>("/api/series?pagination=1&page=$page&per_page=$PAGE_SIZE&${request.data}")
        val items = data?.series.orEmpty().mapNotNull { it.toSearchResponse() }
        return newHomePageResponse(request.name, items, hasNext = data?.hasMore == true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val q = query.trim()
        if (q.isEmpty()) return emptyList()
        // Sem "sort" a API ordena a busca por relevância, como a pesquisa do site.
        val data = getJson<SeriesPage>("/api/series?pagination=1&page=1&per_page=$SEARCH_SIZE&q=${encode(q)}")
        return data?.series.orEmpty().mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val id = SERIES_ID.find(url)?.groupValues?.get(1) ?: return null
        val first = getJson<SeriesDetail>("/api/series/$id?page=1&per_page=$EPISODES_PAGE_SIZE") ?: return null
        val series = first.series ?: return null
        val isMovie = series.category == "filme" || first.seo?.format == "movie"

        // Cada entrada de versions[] é uma ficha de temporada/áudio; agregamos todas
        // para devolver a série completa, e não só a página aberta.
        val byStatus = collectVersionsEpisodes(id, first)

        val title = first.seo?.heading?.takeIf { it.isNotBlank() } ?: series.title ?: return null
        return newAnimeLoadResponse(title, url, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
            posterUrl = series.coverImage
            backgroundPosterUrl = series.backdropImage
            plot = series.description
            year = series.year
            tags = series.genre?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            showStatus = when (series.status?.lowercase()) {
                "completed", "completo", "finalizado" -> ShowStatus.Completed
                "ongoing", "airing", "em lançamento", "lancando" -> ShowStatus.Ongoing
                else -> null
            }
            addEpisodes(DubStatus.Dubbed, byStatus[DubStatus.Dubbed].orEmpty())
            addEpisodes(DubStatus.Subbed, byStatus[DubStatus.Subbed].orEmpty())
            addMalId(first.mal?.id)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        val id = EPISODE_ID.find(data)?.groupValues?.get(1) ?: return false
        val detail = getJson<EpisodeDetail>("/api/episodes/$id") ?: return false

        val sources = detail.sources.orEmpty()
            .filter { it.nativeVideo == true && it.kind != "embed" && !it.url.isNullOrBlank() }
        if (sources.isEmpty()) return false

        // Legenda só faz sentido nos players legendados.
        val subtitle = detail.episode?.subtitleUrl?.takeIf { it.isNotBlank() }?.let { absolute(it) }
        if (subtitle != null && sources.any { it.audio != "dublado" }) {
            subtitleCallback(newSubtitleFile("Português", subtitle))
        }

        var found = false
        for (source in sources) {
            val url = absolute(source.url ?: continue) ?: continue
            val audio = when (source.audio) {
                "dublado" -> "Dublado"
                "legendado" -> "Legendado"
                else -> null
            }
            val label = listOfNotNull(source.label?.takeIf { it.isNotBlank() } ?: "Player", audio).joinToString(" - ")
            val isHls = source.mediaKind == "s6" || url.contains(".m3u8")
            callback(
                newExtractorLink(name, "$name - $label", url, if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    referer = "$mainUrl/"
                    quality = Qualities.Unknown.value
                },
            )
            found = true
        }
        return found
    }

    /** Lê todas as páginas de episódios; para se a API repetir uma página (proteção contra laço). */
    private suspend fun readAllEpisodes(id: String, first: SeriesDetail): List<EpisodeItem> {
        val seen = LinkedHashMap<Long, EpisodeItem>()
        var page = first
        var pageNumber = 1
        while (true) {
            var added = 0
            for (ep in page.episodes.orEmpty()) {
                val epId = ep.id ?: continue
                if (seen.putIfAbsent(epId, ep) == null) added++
            }
            if (page.hasMore != true || added == 0) break
            pageNumber++
            page = getJson<SeriesDetail>("/api/series/$id?page=$pageNumber&per_page=$EPISODES_PAGE_SIZE") ?: break
        }
        return seen.values.toList()
    }

    /**
     * Agrega todas as versões da API (uma ficha por temporada/áudio). O `episode` ganha
     * um offset acumulado por temporada para a numeração ficar contínua entre elas;
     * duplicatas são descartadas por `data` e a saída sai ordenada por (season, episode).
     */
    private suspend fun collectVersionsEpisodes(
        id: String,
        first: SeriesDetail,
    ): Map<DubStatus, List<Episode>> {
        // Sem versões na resposta, cai para a própria ficha (comportamento antigo).
        val versions = first.versions.orEmpty().filter { it.seriesId != null }.ifEmpty {
            listOf(Version(seriesId = id.toLongOrNull(), season = first.seo?.season, audio = first.seo?.audio))
        }
        val dubbed = LinkedHashMap<String, Episode>()
        val subbed = LinkedHashMap<String, Episode>()
        var offset = 0

        // groupBy deixa as temporadas em ordem e permite calcular o offset antes de numerar.
        for ((season, group) in versions.groupBy { it.season ?: 0 }.toSortedMap()) {
            val fetched = group.mapNotNull { version ->
                val sid = version.seriesId?.toString() ?: return@mapNotNull null
                // A ficha atual já veio no primeiro JSON; as demais vêm da API.
                val detail = if (sid == id) first
                    else getJson<SeriesDetail>("/api/series/$sid?page=1&per_page=$EPISODES_PAGE_SIZE")
                        ?: return@mapNotNull null
                val status = if (version.audio == "dublado") DubStatus.Dubbed else DubStatus.Subbed
                status to toEpisodes(readAllEpisodes(sid, detail), season, offset)
            }
            // Dub e sub da mesma temporada costumam ter a mesma contagem; usa a maior.
            if (fetched.isNotEmpty()) offset += fetched.maxOf { it.second.size }

            for ((status, episodes) in fetched) {
                val target = if (status == DubStatus.Dubbed) dubbed else subbed
                for (episode in episodes) target.putIfAbsent(episode.data, episode)
            }
        }

        fun sorted(values: Collection<Episode>) =
            values.sortedWith(compareBy<Episode>({ it.season ?: 0 }, { it.episode ?: 0 }))

        return mapOf(
            DubStatus.Dubbed to sorted(dubbed.values),
            DubStatus.Subbed to sorted(subbed.values),
        )
    }

    private fun toEpisodes(items: List<EpisodeItem>, season: Int?, offset: Int = 0): List<Episode> {
        val formats = DATE_PATTERNS.map { pattern ->
            SimpleDateFormat(pattern, Locale.ROOT).apply { timeZone = TimeZone.getTimeZone("UTC") }
        }
        return items.sortedBy { it.number ?: 0.0 }.mapNotNull { ep ->
            val epId = ep.id ?: return@mapNotNull null
            val number = ep.number
            val whole = number?.takeIf { it % 1.0 == 0.0 }?.toInt()
            val numberText = when {
                number == null -> null
                whole != null -> whole.toString()
                else -> number.toString()
            }
            newEpisode("$mainUrl/episod/$epId") {
                name = listOfNotNull(
                    numberText?.let { "Episódio $it" },
                    ep.title?.trim()?.takeIf { it.isNotEmpty() && it != "Episódio $numberText" },
                ).joinToString(" - ").ifEmpty { null }
                // Número local continua no nome; o campo episode recebe o offset da temporada.
                episode = whole?.plus(offset)
                this.season = season
                posterUrl = ep.thumbnail
                description = ep.description
                date = parseDate(ep.airedAt, formats) ?: parseDate(ep.createdAt, formats)
            }
        }
    }

    private fun Series.toSearchResponse(): SearchResponse? {
        val seriesId = id ?: return null
        val baseTitle = title?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val isDub = category == "dublado"
        val displayTitle = if (isDub && !baseTitle.contains("dublad", ignoreCase = true)) "$baseTitle (Dublado)" else baseTitle
        val type = if (category == "filme") TvType.AnimeMovie else TvType.Anime
        return newAnimeSearchResponse(displayTitle, "$mainUrl/serie/$seriesId", type) {
            posterUrl = coverImage
            year = this@toSearchResponse.year
            addDubStatus(isDub)
        }
    }

    /** Resolve URLs relativas ("/api/v/...") e protocolo-relativas ("//cdn/...") no domínio do site. */
    private fun absolute(url: String): String? = try {
        val resolved = URI("$mainUrl/").resolve(url.trim()).toString()
        if (resolved.startsWith("https://") || resolved.startsWith("http://")) resolved else null
    } catch (e: Exception) {
        null
    }

    /**
     * Datas da API: "2025-03-01T12:00:00Z", "2025-03-01 12:00:00" ou "2025-03-01".
     * O fuso vira "+0000"/"-0300" para usar o padrão "Z", que existe em todo Android
     * (o padrão "X" só chegou no Android 7 e o minSdk é 21).
     */
    private fun parseDate(value: String?, formats: List<SimpleDateFormat>): Long? {
        val raw = value?.trim()?.takeIf { it.isNotEmpty() }
            ?.replace(Regex("""Z$"""), "+0000")
            ?.replace(Regex("""([+-]\d{2}):(\d{2})$"""), "$1$2")
            ?: return null
        for (format in formats) {
            val parsed = try {
                format.parse(raw)
            } catch (e: Exception) {
                null
            }
            if (parsed != null) return parsed.time
        }
        return null
    }

    private fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val PAGE_SIZE = 50
        private const val SEARCH_SIZE = 40
        private const val EPISODES_PAGE_SIZE = 200
        private val SERIES_ID = Regex("""/serie/(\d+)""")
        private val EPISODE_ID = Regex("""/episod/(\d+)""")
        private val DATE_PATTERNS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSZ",
            "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd",
        )
    }
}
