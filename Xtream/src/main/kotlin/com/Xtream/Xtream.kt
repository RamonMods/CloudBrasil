package com.Xtream

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.ErrorLoadingException
import com.lagradost.cloudstream3.HomePageList
import com.lagradost.cloudstream3.HomePageResponse
import com.lagradost.cloudstream3.LoadResponse
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.MainPageRequest
import com.lagradost.cloudstream3.SearchResponse
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.TvType
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.mainPageOf
import com.lagradost.cloudstream3.SeasonData
import com.lagradost.cloudstream3.newEpisode
import com.lagradost.cloudstream3.newHomePageResponse
import com.lagradost.cloudstream3.newLiveSearchResponse
import com.lagradost.cloudstream3.newLiveStreamLoadResponse
import com.lagradost.cloudstream3.newMovieLoadResponse
import com.lagradost.cloudstream3.newMovieSearchResponse
import com.lagradost.cloudstream3.newTvSeriesLoadResponse
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * Provedor genérico para painéis Xtream Codes / Xtream UI.
 *
 * Formatos de config aceitos:
 *   host/get.php?username=U&password=P&type=m3u_plus&output=ts
 *   host/player_api.php?username=U&password=P
 *   user:pass@host:port
 */

internal data class XtreamCategory(
    @JsonProperty("category_id") val id: String? = null,
    @JsonProperty("category_name") val name: String? = null,
)

internal data class XtreamItem(
    @JsonProperty("stream_id") val streamId: String? = null,
    @JsonProperty("series_id") val seriesId: String? = null,
    @JsonProperty("vod_id") val vodId: String? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("stream_icon") val streamIcon: String? = null,
    @JsonProperty("cover") val cover: String? = null,
    @JsonProperty("thumbnail") val thumbnail: String? = null,
    @JsonProperty("category_id") val categoryId: String? = null,
    @JsonProperty("container_extension") val ext: String? = null,
    @JsonProperty("rating") val rating: String? = null,
    @JsonProperty("added") val added: String? = null,
    @JsonProperty("plot") val plot: String? = null,
    @JsonProperty("url") val url: String? = null,
) {
    val itemId: String?
        get() = listOfNotNull(streamId, seriesId, vodId).firstOrNull { it.isNotBlank() }

    val displayName: String
        get() = (name ?: title ?: "").trim()
}

internal data class XtreamSeason(
    @JsonProperty("season_number") val number: Int? = null,
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("cover") val cover: String? = null,
)

internal data class XtreamEpisodeInfo(
    @JsonProperty("plot") val plot: String? = null,
    @JsonProperty("release_date") val releaseDate: String? = null,
    @JsonProperty("duration") val duration: String? = null,
)

internal data class XtreamEpisode(
    @JsonProperty("id") val id: String? = null,
    @JsonProperty("episode_num") val episodeNum: Int? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("container_extension") val ext: String? = null,
    @JsonProperty("info") val info: XtreamEpisodeInfo? = null,
)

internal data class XtreamInfo(
    @JsonProperty("name") val name: String? = null,
    @JsonProperty("title") val title: String? = null,
    @JsonProperty("plot") val plot: String? = null,
    @JsonProperty("genre") val genre: String? = null,
    @JsonProperty("releasedate") val releaseDate: String? = null,
    @JsonProperty("year") val year: String? = null,
    @JsonProperty("cast") val cast: String? = null,
    @JsonProperty("rating") val rating: String? = null,
)

internal data class XtreamSeriesInfo(
    @JsonProperty("seasons") val seasons: List<XtreamSeason>? = null,
    @JsonProperty("episodes") val episodes: Map<String, List<XtreamEpisode>>? = null,
    @JsonProperty("info") val info: XtreamInfo? = null,
)

internal data class XtreamVodInfo(
    @JsonProperty("info") val info: XtreamInfo? = null,
)

internal data class XtreamUserInfo(
    @JsonProperty("username") val username: String? = null,
    @JsonProperty("state") val state: String? = null,
    @JsonProperty("exp_date") val expDate: String? = null,
    @JsonProperty("max_connections") val maxConnections: String? = null,
    @JsonProperty("active_connections") val activeConnections: String? = null,
    @JsonProperty("is_trial") val isTrial: String? = null,
)

internal data class XtreamAuth(
    @JsonProperty("user_info") val userInfo: XtreamUserInfo? = null,
)

private data class XtreamCatalog(
    val live: List<XtreamItem> = emptyList(),
    val movies: List<XtreamItem> = emptyList(),
    val series: List<XtreamItem> = emptyList(),
    val liveCategories: List<XtreamCategory> = emptyList(),
    val movieCategories: List<XtreamCategory> = emptyList(),
    val seriesCategories: List<XtreamCategory> = emptyList(),
) {
    val all: List<XtreamItem>
        get() = live + movies + series
}

class Xtream : MainAPI() {
    override var mainUrl = "http://127.0.0.1:8080"
    override var name = "Xtream"
    override var lang = "pt-br"
    override val hasMainPage = true
    override val hasQuickSearch = true
    override val hasDownloadSupport = false
    override val supportedTypes = setOf(TvType.Live, TvType.Movie, TvType.TvSeries)

    override val mainPage = mainPageOf("" to "Catálogo")

    private var configuredUser: String = ""
    private var configuredPass: String = ""
    private var catalogCache: XtreamCatalog? = null

    // ---------------------------------------------------------------- config

    /** Aceita os três formatos de config descritos no cabeçalho/readme. */
    suspend fun parseConfig(config: String) {
        val raw = config.trim().trim('"').trim()
        if (raw.isBlank()) return

        var user: String? = null
        var pass: String? = null
        var base: String? = null

        // user:pass@host:port
        val compact = Regex("^([^:@/]+):([^@/]+)@([^/]+)/?$").find(raw)
        if (compact != null) {
            user = compact.groupValues[1]
            pass = compact.groupValues[2]
            base = "http://" + compact.groupValues[3]
        } else {
            val withScheme = when {
                raw.startsWith("http://", true) || raw.startsWith("https://", true) -> raw
                else -> "http://$raw"
            }
            try {
                val parsed = java.net.URL(withScheme)
                val params = parsed.query.orEmpty().split('&').mapNotNull { part ->
                    val key = part.substringBefore('=').trim()
                    val value = part.substringAfter('=', "").trim()
                    if (key.isBlank()) null else key.lowercase(Locale.ROOT) to value
                }.toMap()

                user = params["username"]
                pass = params["password"]
                if (user.isNullOrBlank() && parsed.userInfo != null) {
                    val info = parsed.userInfo!!
                    user = info.substringBefore(':')
                    pass = info.substringAfter(':')
                }
                val port = if (parsed.port in 1..65535) ":${parsed.port}" else ""
                base = "${parsed.protocol}://${parsed.host}$port"
            } catch (t: Throwable) {
                return
            }
        }

        if (user.isNullOrBlank() || pass.isNullOrBlank() || base.isNullOrBlank()) return
        configuredUser = user!!
        configuredPass = pass!!
        mainUrl = base!!.trimEnd('/')
        storedCredentials = "$configuredUser:$configuredPass"
        catalogCache = null
    }

    private suspend fun ensureConfig() {
        if (configuredUser.isNotBlank() && configuredPass.isNotBlank()) return

        val current = mainUrl.trim()
        if (current.contains("player_api.php") || current.contains("get.php") || current.contains("@")) {
            parseConfig(current)
            return
        }
        val creds = storedCredentials
        if (!creds.isNullOrBlank()) {
            val separator = creds.indexOf(':')
            if (separator > 0) {
                configuredUser = creds.substring(0, separator)
                configuredPass = creds.substring(separator + 1)
            }
        }
    }

    private suspend fun credentials(): Pair<String, String>? {
        ensureConfig()
        if (configuredUser.isNotBlank() && configuredPass.isNotBlank())
            return configuredUser to configuredPass
        val creds = storedCredentials ?: return null
        val separator = creds.indexOf(':')
        return if (separator > 0) creds.substring(0, separator) to creds.substring(separator + 1) else null
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")

    private suspend fun api(action: String, vararg params: Pair<String, String>): String? {
        val (user, pass) = credentials() ?: return null
        val query = buildString {
            append("username=").append(encode(user))
            append("&password=").append(encode(pass))
            if (action.isNotBlank()) append("&action=").append(encode(action))
            params.forEach { (key, value) ->
                append('&').append(encode(key)).append('=').append(encode(value))
            }
        }
        return try {
            app.get("${mainUrl.trimEnd('/')}/player_api.php?$query").text
        } catch (t: Throwable) {
            null
        }
    }

    private inline suspend fun <reified T> apiJson(action: String, vararg params: Pair<String, String>): T? {
        val body = api(action, *params) ?: return null
        if (body.isBlank() || body.trimStart().startsWith("<")) return null
        return try {
            tryParseJson<T>(body)
        } catch (t: Throwable) {
            null
        }
    }

    private fun streamUrl(item: XtreamItem, type: String): String {
        if (!item.url.isNullOrBlank()) return item.url!!
        val id = item.itemId ?: return ""
        val (user, pass) = configuredUser to configuredPass
        if (user.isBlank() || pass.isBlank()) return ""
        val ext = item.ext?.ifBlank { null } ?: if (type == "live") "ts" else "mp4"
        val base = mainUrl.trimEnd('/')
        return when (type) {
            "live" -> "$base/live/$user/$pass/$id.$ext"
            "movie" -> "$base/movie/$user/$pass/$id.$ext"
            else -> "$base/series/$user/$pass/$id.$ext"
        }
    }

    // ------------------------------------------------------------- catalogo

    private suspend fun loadCatalog(force: Boolean = false): XtreamCatalog {
        catalogCache?.let { if (!force) return it }

        val live = apiJson<List<XtreamItem>>("get_live_streams").orEmpty()
        val movies = apiJson<List<XtreamItem>>("get_vod_streams").orEmpty()
        val series = apiJson<List<XtreamItem>>("get_series").orEmpty()
        val liveCategories = apiJson<List<XtreamCategory>>("get_live_categories").orEmpty()
        val movieCategories = apiJson<List<XtreamCategory>>("get_vod_categories").orEmpty()
        val seriesCategories = apiJson<List<XtreamCategory>>("get_series_categories").orEmpty()

        var catalog = XtreamCatalog(
            live, movies, series, liveCategories, movieCategories, seriesCategories
        )
        if (catalog.all.isEmpty()) {
            parseM3u()?.let { catalog = it }
        }
        catalogCache = catalog
        return catalog
    }

    /** Fallback para provedores sem player_api: le um M3U com #EXTINF. */
    private suspend fun parseM3u(): XtreamCatalog? {
        val (user, pass) = credentials() ?: return null
        val url = "${mainUrl.trimEnd('/')}/get.php?username=${encode(user)}&password=${encode(pass)}&type=m3u_plus&output=ts"
        val text = try {
            app.get(url).text
        } catch (t: Throwable) {
            return null
        }
        if (!text.contains("#EXTINF")) return null

        val live = mutableListOf<XtreamItem>()
        val groups = LinkedHashMap<String, String>()
        var pendingName: String? = null
        var pendingGroup: String? = null
        var pendingLogo: String? = null

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.startsWith("#EXTINF")) {
                pendingName = line.substringAfterLast(',').trim()
                pendingGroup = Regex("group-title=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.get(1)?.trim()
                pendingLogo = Regex("tvg-logo=\"([^\"]*)\"", RegexOption.IGNORE_CASE)
                    .find(line)?.groupValues?.get(1)?.trim()
                continue
            }
            if (!line.startsWith("http") || pendingName == null) continue

            val id = line.substringBefore('?').substringAfterLast('/').substringBefore('.')
            val group = pendingGroup ?: "Canais"
            groups.putIfAbsent(group, group)
            live.add(
                XtreamItem(
                    streamId = id,
                    name = pendingName,
                    streamIcon = pendingLogo?.ifBlank { null },
                    categoryId = group,
                    url = line,
                )
            )
            pendingName = null
            pendingGroup = null
            pendingLogo = null
        }
        if (live.isEmpty()) return null
        return XtreamCatalog(
            live = live,
            liveCategories = groups.values.map { XtreamCategory(id = it, name = it) },
        )
    }

    private fun audioOf(name: String): String? = when {
        Regex("(^|[^a-z])(dublado|dubbed|dub)([^a-z]|$)", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "Dublado"
        Regex("(^|[^a-z])(legendado|subbed|leg|sub)([^a-z]|$)", RegexOption.IGNORE_CASE).containsMatchIn(name) -> "Legendado"
        else -> null
    }

    private fun XtreamItem.toSearch(type: String): SearchResponse? {
        val id = itemId ?: return null
        val title = displayName.ifBlank { return null }
        val url = "xtream://$type/$id"
        val audio = audioOf(title)
        val label = if (audio != null) "$title ($audio)" else title
        val icon = streamIcon ?: cover ?: thumbnail
        return when (type) {
            "live" -> newLiveSearchResponse(label, url, TvType.Live, fix = false) {
                this.posterUrl = icon
            }
            "movie" -> newMovieSearchResponse(label, url, TvType.Movie, fix = false) {
                this.posterUrl = icon
            }
            else -> newMovieSearchResponse(label, url, TvType.TvSeries, fix = false) {
                this.posterUrl = icon
            }
        }
    }

    // ------------------------------------------------------------- account

    private suspend fun getAccountInfo(): HomePageList? {
        val auth = apiJson<XtreamAuth>("") ?: return null
        val info = auth.userInfo ?: return null

        val status = info.state?.takeIf { it.isNotBlank() }?.let {
            if (it.equals("active", true)) "Ativa" else "Inativa ($it)"
        } ?: "Desconhecido"
        val trial = if (info.isTrial == "1") " (teste)" else ""
        val expiry = info.expDate?.let { raw ->
            val seconds = raw.toLongOrNull() ?: return@let null
            if (seconds <= 0L) "sem validade"
            else try {
                SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date(seconds * 1000L))
            } catch (t: Throwable) {
                raw
            }
        } ?: "não informada"
        val active = info.activeConnections ?: "0"
        val max = info.maxConnections ?: "?"
        val title = listOfNotNull(
            "Usuário: ${info.username ?: configuredUser}",
            "Validade: $expiry",
            "Status: $status$trial",
            "Conexões: $active/$max",
        ).joinToString(" • ")

        val item = newMovieSearchResponse(title, "xtream://account", TvType.Live, fix = false)
        return HomePageList("Minha conta", listOf(item))
    }

    // ----------------------------------------------------------- main page

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        ensureConfig()
        val catalog = loadCatalog()
        val rows = mutableListOf<HomePageList>()

        getAccountInfo()?.let { rows.add(it) }

        fun addCategories(title: String, type: String, items: List<XtreamItem>, categories: List<XtreamCategory>) {
            val byId = items.groupBy { it.categoryId ?: "" }
            val allItems = items.mapNotNull { it.toSearch(type) }
            if (allItems.isNotEmpty()) rows.add(HomePageList(title, allItems, isHorizontalImages = type != "live"))

            for (category in categories) {
                val id = category.id ?: continue
                val group = byId[id].orEmpty().mapNotNull { it.toSearch(type) }
                if (group.isNotEmpty()) {
                    rows.add(
                        HomePageList(
                            "$title • ${category.name?.ifBlank { null } ?: id}",
                            group,
                            isHorizontalImages = type != "live",
                        )
                    )
                }
            }
        }

        addCategories("TV ao vivo", "live", catalog.live, catalog.liveCategories)
        addCategories("Filmes", "movie", catalog.movies, catalog.movieCategories)
        addCategories("Séries", "series", catalog.series, catalog.seriesCategories)

        val dubbed = catalog.all.filter { audioOf(it.displayName) == "Dublado" }.mapNotNull { it.toSearch("movie") }
        val subbed = catalog.all.filter { audioOf(it.displayName) == "Legendado" }.mapNotNull { it.toSearch("movie") }
        if (dubbed.isNotEmpty()) rows.add(HomePageList("Dublados", dubbed))
        if (subbed.isNotEmpty()) rows.add(HomePageList("Legendados", subbed))

        if (rows.isEmpty()) {
            throw ErrorLoadingException("Nenhum catálogo encontrado. Configure a URL Xtream (Clonar site).")
        }
        return newHomePageResponse(rows, hasNext = false)
    }

    // -------------------------------------------------------------- search

    override suspend fun search(query: String): List<SearchResponse> {
        val raw = query.trim()
        if (raw.isEmpty()) return emptyList()

        val audioFilter = when {
            raw.startsWith("dublado", true) -> "Dublado"
            raw.startsWith("legendado", true) -> "Legendado"
            else -> null
        }
        val term = if (audioFilter != null) raw.substringAfter(' ').trim() else raw
        val catalog = loadCatalog()

        return catalog.all.filter { item ->
            val name = item.displayName
            name.contains(term, ignoreCase = true) &&
                (audioFilter == null || audioOf(name) == audioFilter)
        }.mapNotNull { item ->
            val type = when (item) {
                in catalog.live -> "live"
                in catalog.movies -> "movie"
                else -> "series"
            }
            item.toSearch(type)
        }.distinctBy { it.url }
    }

    // ---------------------------------------------------------------- load

    private suspend fun findItem(type: String, id: String): XtreamItem? =
        loadCatalog().let { catalog ->
            when (type) {
                "live" -> catalog.live
                "movie" -> catalog.movies
                else -> catalog.series
            }.firstOrNull { it.itemId == id }
        }

    override suspend fun load(url: String): LoadResponse? {
        ensureConfig()
        if (url.startsWith("xtream://")) {
            val type = url.removePrefix("xtream://").substringBefore('/')
            val id = url.substringAfter("xtream://").substringAfter('/').substringBefore('?')

            if (type == "account") {
                val auth = apiJson<XtreamAuth>("")
                val info = auth?.userInfo
                return newMovieLoadResponse(
                    "Minha conta",
                    url,
                    TvType.Live,
                    "",
                ) {
                    plot = listOfNotNull(
                        info?.username?.let { "Usuário: $it" },
                        info?.state?.let { "Status: $it" },
                        info?.expDate?.let { "Validade (unix): $it" },
                        info?.maxConnections?.let { "Conexões: ${info.activeConnections ?: "0"}/$it" },
                    ).joinToString(" • ")
                }
            }

            val item = findItem(type, id)
                ?: throw ErrorLoadingException("Item não encontrado: $id")
            val title = item.displayName.ifBlank { "Sem título" }
            val icon = item.streamIcon ?: item.cover ?: item.thumbnail
            val link = streamUrl(item, type)

            return when (type) {
                "live" -> newLiveStreamLoadResponse(title, url, link) {
                    posterUrl = icon
                    plot = "Canal ao vivo • ${item.categoryId ?: "Xtreme"}"
                }

                "movie" -> {
                    val info = apiJson<XtreamVodInfo>("get_vod_info", "vod_id" to id)?.info
                    newMovieLoadResponse(title, url, TvType.Movie, link) {
                        posterUrl = icon
                        plot = info?.plot ?: item.plot
                        year = info?.year?.toIntOrNull()
                        tags = info?.genre?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }
                    }
                }

                else -> {
                    val detail = apiJson<XtreamSeriesInfo>("get_series_info", "series_id" to id)
                    val episodes = mutableListOf<com.lagradost.cloudstream3.Episode>()
                    val allSeasonNames = detail?.seasons.orEmpty().mapNotNull { season ->
                        season.number?.let { number ->
                            SeasonData(
                                number,
                                season.name?.ifBlank { null } ?: "Temporada $number",
                            )
                        }
                    }

                    // Percorre TODAS as temporadas, não só a primeira.
                    for (seasonEntry in detail?.seasons.orEmpty()) {
                        val seasonNumber = seasonEntry.number ?: continue
                        val seasonKey = seasonEntry.number.toString()
                        val seasonEpisodes = detail?.episodes?.get(seasonKey)
                            ?: detail?.episodes?.get("season_$seasonNumber")
                            ?: detail?.episodes?.entries
                                ?.firstOrNull { it.key.trim().toIntOrNull() == seasonNumber }?.value
                            ?: continue

                        val seasonCover = seasonEntry.cover
                        for (episode in seasonEpisodes) {
                            val episodeId = episode.id ?: continue
                            val episodeNumber = episode.episodeNum
                            val episodeTitle = episode.title?.ifBlank { null }
                            val episodePlot = episode.info?.plot
                            val extension = episode.ext?.ifBlank { null } ?: "mp4"
                            val epLink = "${mainUrl.trimEnd('/')}/series/$configuredUser/$configuredPass/$episodeId.$extension"
                            episodes.add(
                                newEpisode(epLink) {
                                    name = episodeTitle ?: "Episódio ${episodeNumber ?: ""}".trim()
                                    season = seasonNumber
                                    this.episode = episodeNumber
                                    posterUrl = seasonCover ?: icon
                                    description = episodePlot
                                }
                            )
                        }
                    }

                    newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                        this.posterUrl = icon
                        plot = detail?.info?.plot ?: item.plot
                        year = detail?.info?.year?.toIntOrNull()
                        tags = detail?.info?.genre?.split(',')?.map { it.trim() }?.filter { it.isNotBlank() }
                        if (allSeasonNames.isNotEmpty()) this.seasonNames = allSeasonNames
                    }
                }
            }
        }

        // Link direto (por exemplo de uma lista M3U): reproduz como ao vivo.
        val link = url.takeIf { it.startsWith("http") }
            ?: throw ErrorLoadingException("URL inválida")
        return newLiveStreamLoadResponse(link.substringAfterLast('/').substringBefore('.'), url, link)
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
    ): Boolean {
        if (data.isBlank()) return false
        val type = if (data.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
        callback(
            newExtractorLink(name, name, data, type) {
                referer = "${mainUrl.trimEnd('/')}/"
                quality = Qualities.Unknown.value
            }
        )
        return true
    }
}
