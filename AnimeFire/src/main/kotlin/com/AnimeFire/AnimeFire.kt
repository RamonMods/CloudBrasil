package com.AnimeFire

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Provider do AnimeFire usando a API JSON pública (api.animefire.one).
 *
 * O site virou uma SPA Angular, então o scraping de HTML morreu. Todos os dados
 * abaixo vêm dos endpoints JSON (validados com curl):
 *  - busca:    GET /animes/pesquisar?q=<termo>
 *  - anime:    GET /anime/<animeId>
 *  - episódio: GET /episode/<episodeId>
 *  - home:     GET /home (carrosséis) e GET /animes/lancamentos
 */
class AnimeFire : MainAPI() {
    override var mainUrl = "https://animefire.one"
    override var name = "AnimeFire"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(TvType.Anime, TvType.Movie, TvType.OVA)
    override val usesWebView = false

    companion object {
        // Base da API JSON (o site em si é SPA e não serve mais HTML útil)
        private const val API_URL = "https://api.animefire.one"

        // Cache do /home (resposta grande, ~400 KB) para não baixar uma vez por aba
        private const val HOME_CACHE_MS = 5 * 60 * 1000L
        private val homeMutex = Mutex()
        private var cachedHome: ApiHomeResponse? = null
        private var cachedHomeAt = 0L

        /**
         * Linhas da home. Cada chave é um carrossel do endpoint /home, exceto
         * "lancamentos", que usa /animes/lancamentos.
         *
         * O carrossel "new-episodes" foi omitido de propósito: os itens dele só
         * trazem o id do EPISÓDIO (sem o id do anime), então não dá para montar
         * a URL de load ("/anime/<animeId>/<epId>").
         */
        private val HOME_TABS = listOf(
            "lancamentos" to "Lançamentos",
            "new-animes" to "Novidades",
            "new-animes-dubbed" to "Novos Dublados",
            "most-liked" to "Mais Curtidos",
            "top-airing" to "Melhores em Lançamento",
            "top-10" to "Top 10 da Semana",
            "tag-magic" to "Magia e Feitiçaria",
            "tag-time-manipulation" to "Viagens no Tempo",
            "tag-detective" to "Mistérios para Resolver",
            "tag-post-apocalyptic" to "Pós-Apocalíptico"
        )
    }

    override val mainPage = mainPageOf(
        *HOME_TABS.toTypedArray()
    )

    /** Extrai o último segmento da URL (id do anime ou do episódio). */
    private fun extractId(url: String): String? {
        return url.substringBefore('?')
            .substringBefore('#')
            .trimEnd('/')
            .substringAfterLast('/')
            .takeIf { it.isNotBlank() }
    }

    /** Título preferindo BR, depois JP, depois US. */
    private fun ApiTitles?.bestTitle(): String? {
        return this?.br?.takeIf { it.isNotBlank() }
            ?: this?.jp?.takeIf { it.isNotBlank() }
            ?: this?.us?.takeIf { it.isNotBlank() }
    }

    private fun ApiSearchItem.toSearchResponse(): AnimeSearchResponse? {
        val animeId = id?.takeIf { it.isNotBlank() } ?: return null
        val title = titles.bestTitle() ?: return null
        val audio = audio?.lowercase().orEmpty()

        return newAnimeSearchResponse(title, "$mainUrl/anime/$animeId", TvType.Anime) {
            this.posterUrl = posterSrc
            if (audio.isNotBlank()) {
                addDubStatus(
                    dubExist = audio.contains("dublado"),
                    subExist = audio.contains("legendado")
                )
            }
        }
    }

    /** Busca no /home com cache em memória. */
    private suspend fun getHomeCached(): ApiHomeResponse? {
        val now = System.currentTimeMillis()
        cachedHome?.let { if (now - cachedHomeAt < HOME_CACHE_MS) return it }

        return homeMutex.withLock {
            val nowInner = System.currentTimeMillis()
            cachedHome?.let { if (nowInner - cachedHomeAt < HOME_CACHE_MS) return@withLock it }

            val response = app.get("$API_URL/home", timeout = 40)
                .parsedSafe<ApiHomeResponse>()
            if (response != null) {
                cachedHome = response
                cachedHomeAt = nowInner
            }
            response
        }
    }

    private suspend fun fetchHomeCarousel(key: String): List<SearchResponse> {
        val carousel = getHomeCached()
            ?.data?.carousels
            ?.firstOrNull { it.key == key }
            ?: return emptyList()

        return carousel.items.orEmpty().mapNotNull { it.toSearchResponse() }
    }

    private suspend fun fetchLancamentos(): List<SearchResponse> {
        val response = app.get("$API_URL/animes/lancamentos", timeout = 30)
            .parsedSafe<ApiLancamentosResponse>()

        return response?.data?.animes.orEmpty().mapNotNull { it.toSearchResponse() }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        return try {
            // Carrosséis e lançamentos não são paginados pela API.
            if (page > 0) {
                newHomePageResponse(request.name, emptyList(), false)
            } else {
                val items = when (request.data) {
                    "lancamentos" -> fetchLancamentos()
                    else -> fetchHomeCarousel(request.data)
                }
                newHomePageResponse(
                    request.name,
                    items.distinctBy { it.url },
                    false
                )
            }
        } catch (e: Exception) {
            newHomePageResponse(request.name, emptyList(), false)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.isBlank()) return emptyList()

        return try {
            val response = app.get(
                "$API_URL/animes/pesquisar",
                params = mapOf("q" to query.trim()),
                timeout = 25
            ).parsedSafe<ApiSearchResponse>()

            response?.data.orEmpty()
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val animeId = extractId(url) ?: return newTvSeriesLoadResponse(
            "Erro ao carregar",
            url,
            TvType.Anime,
            emptyList()
        ) {
            this.plot = "Não foi possível identificar o anime na URL."
        }

        return try {
            val data = app.get("$API_URL/anime/$animeId", timeout = 45)
                .parsedSafe<ApiAnimeResponse>()
                ?.data
                ?: throw IllegalStateException("Resposta vazia da API para o anime $animeId")

            val hero = data.hero
            val title = hero?.titles.bestTitle() ?: "Sem Título"

            // A própria API já entrega season/number corretos (a numeração reinicia
            // por temporada). Não normalizamos nada, só deduplicamos e ordenamos.
            val episodes = data.episodes.orEmpty()
                .filter { !it.id.isNullOrBlank() }
                .distinctBy { it.id }
                .sortedWith(compareBy({ it.season ?: 0 }, { it.number ?: 0 }))
                .mapNotNull { episode ->
                    val episodeId = episode.id ?: return@mapNotNull null
                    newEpisode("$mainUrl/anime/$animeId/$episodeId") {
                        this.season = episode.season
                        this.episode = episode.number
                        this.name = episode.title?.takeIf { it.isNotBlank() }
                            ?: "Episódio ${episode.number ?: 0}"
                        this.posterUrl = episode.stillSrc
                    }
                }

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = hero?.posterSrc
                this.backgroundPosterUrl = hero?.backdropSrc
                this.plot = hero?.synopsis
                this.year = hero?.publishedAt?.take(4)?.toIntOrNull()
                this.score = Score.from10(hero?.score)
                this.showStatus = getStatus(hero?.status)
                hero?.genres?.let { this.tags = it }
            }
        } catch (e: Exception) {
            newTvSeriesLoadResponse("Erro ao carregar", url, TvType.Anime, emptyList()) {
                this.plot = "Não foi possível carregar este anime na API."
            }
        }
    }

    /** Maior qualidade disponível. A API manda lista de strings; se vier objeto, usa o melhor valor. */
    private fun bestQuality(raw: Any?): String? {
        return when (raw) {
            is String -> raw.takeIf { it.isNotBlank() }
            is List<*> -> raw.mapNotNull { qualityLabel(it) }.maxByOrNull { qualityHeight(it) }
            is Map<*, *> -> raw.mapNotNull { (key, value) ->
                qualityLabel(value) ?: (key as? String)?.takeIf { it.isNotBlank() }
            }.maxByOrNull { qualityHeight(it) }
            else -> null
        }
    }

    private fun qualityLabel(value: Any?): String? {
        return when (value) {
            is String -> value.takeIf { it.isNotBlank() }
            is Map<*, *> -> (value["label"] ?: value["quality"] ?: value["name"]) as? String
            else -> null
        }
    }

    private fun qualityHeight(label: String): Int {
        return Regex("""(\d{3,4})p""", RegexOption.IGNORE_CASE)
            .find(label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /**
     * Tipo do link. A API serve HLS até em URLs terminadas em .jpg (o
     * content-type validado foi application/vnd.apple.mpegurl), por isso o
     * ".jpg" também é tratado como M3U8.
     */
    private fun linkType(url: String): ExtractorLinkType {
        val path = url.substringBefore('?').substringBefore('#').lowercase()
        return if (path.endsWith(".m3u8") || path.endsWith(".jpg")) {
            ExtractorLinkType.M3U8
        } else {
            ExtractorLinkType.VIDEO
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val episodeId = extractId(data) ?: return false
            val streams = app.get("$API_URL/episode/$episodeId", timeout = 30)
                .parsedSafe<ApiEpisodeResponse>()
                ?.data?.streams
                .orEmpty()

            var found = false
            streams.forEach { stream ->
                val streamUrl = stream.url?.takeIf { it.isNotBlank() } ?: return@forEach
                val quality = bestQuality(stream.qualities)
                val audioLabel = stream.audio
                    ?.takeIf { it.isNotBlank() }
                    ?.lowercase()
                    ?.replaceFirstChar { it.uppercase() }

                val linkName = listOfNotNull("AnimeFire", audioLabel, quality).joinToString(" ")

                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = linkName,
                        url = streamUrl
                    ) {
                        this.type = linkType(streamUrl)
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
}

// ---------------------------------------------------------------------------
// Modelos da API JSON (campos conferidos por curl antes de escrever o código)
// ---------------------------------------------------------------------------

private data class ApiTitles(
    @JsonProperty("BR") val br: String? = null,
    @JsonProperty("JP") val jp: String? = null,
    @JsonProperty("US") val us: String? = null
)

private data class ApiSearchResponse(
    @JsonProperty("data") val data: List<ApiSearchItem>? = null
)

private data class ApiSearchItem(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("titles") val titles: ApiTitles? = null,
    @JsonProperty("audio") val audio: String? = null,
    @JsonProperty("poster_src") val posterSrc: String? = null
)

private data class ApiAnimeResponse(
    @JsonProperty("data") val data: ApiAnimeData? = null
)

private data class ApiAnimeData(
    @JsonProperty("hero") val hero: ApiHero? = null,
    @JsonProperty("seasons") val seasons: List<ApiSeason>? = null,
    @JsonProperty("episodes") val episodes: List<ApiEpisode>? = null
)

private data class ApiHero(
    @JsonProperty("titles") val titles: ApiTitles? = null,
    @JsonProperty("synopsis") val synopsis: String? = null,
    @JsonProperty("poster_src") val posterSrc: String? = null,
    @JsonProperty("backdrop_src") val backdropSrc: String? = null,
    @JsonProperty("genres") val genres: List<String>? = null,
    @JsonProperty("score") val score: Double? = null,
    @JsonProperty("status") val status: String? = null,
    @JsonProperty("published_at") val publishedAt: String? = null,
    @JsonProperty("audio") val audio: String? = null
)

private data class ApiSeason(
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("first_episode_number") val firstEpisodeNumber: Int? = null
)

private data class ApiEpisode(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("still_src") val stillSrc: String? = null,
    @JsonProperty("audio") val audio: String? = null
)

private data class ApiEpisodeResponse(
    @JsonProperty("data") val data: ApiEpisodeData? = null
)

private data class ApiEpisodeData(
    @JsonProperty("season") val season: Int? = null,
    @JsonProperty("number") val number: Int? = null,
    @JsonProperty("streams") val streams: List<ApiStream>? = null
)

private data class ApiStream(
    @JsonProperty("audio") val audio: String? = null,
    @JsonProperty("url") val url: String? = null,
    // Lista de strings na API atual; mantido como Any? para aceitar objeto também.
    @JsonProperty("qualities") val qualities: Any? = null
)

private data class ApiHomeResponse(
    @JsonProperty("data") val data: ApiHomeData? = null
)

private data class ApiHomeData(
    @JsonProperty("carousels") val carousels: List<ApiCarousel>? = null
)

private data class ApiCarousel(
    @JsonProperty("key") val key: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("items") val items: List<ApiSearchItem>? = null
)

private data class ApiLancamentosResponse(
    @JsonProperty("data") val data: ApiLancamentosData? = null
)

private data class ApiLancamentosData(
    @JsonProperty("animes") val animes: List<ApiSearchItem>? = null
)
