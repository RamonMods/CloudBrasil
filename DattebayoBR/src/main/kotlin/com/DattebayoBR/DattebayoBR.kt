package com.dattebayo

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.json.JSONArray
import org.json.JSONObject

@CloudstreamPlugin
class DattebayoBRPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(DattebayoBR())
    }
}

class DattebayoBR : MainAPI() {
    override var mainUrl = "https://www.dattebayo-br.com"
    override var name = "Dattebayo BR"
    override val hasMainPage = true
    override var lang = "pt-br"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime, 
        TvType.AnimeMovie,
        TvType.TvSeries
    )
    override val usesWebView = false

    companion object {
        private const val HOME_ITEM = ".ultimosAnimesHomeItem, .ultimosEpisodiosHomeItem"
        private const val HOME_LINK = "a"
        private const val HOME_TITLE = ".ultimosAnimesHomeItemInfosNome, .ultimosEpisodiosHomeItemInfosNome"
        private const val HOME_IMG = ".ultimosAnimesHomeItemImg img, .ultimosEpisodiosHomeItemImg img"
        private const val HOME_EP_NUM = ".ultimosEpisodiosHomeItemInfosNum"
        private const val HOME_EP_TOTAL = ".ultimosAnimesHomeItemQntEps"
        private const val HOME_TIPO = ".ultimosAnimesHomeItemTipo"
        private const val DETAIL_TITLE = "h1"
        private const val DETAIL_POSTER = ".aniInfosSingleCapa img"
        private const val DETAIL_SYNOPSIS = ".aniInfosSingleSinopse p"
        private const val DETAIL_GENRES = ".aniInfosSingleGeneros span"
        private const val DETAIL_STATUS = "#completed"
        private const val DETAIL_EPISODES_INFO = ".aniInfosSingleNumsItem"
        private const val EPISODE_CONTAINER = ".aniContainer"
        private const val EPISODE_ITEM = ".ultimosEpisodiosHomeItem"
        private const val EPISODE_LINK = "a"
        private const val EPISODE_TITLE_ATTR = "title"
        private const val OUTBRAIN_URL = "https://widgets.outbrain.com/outbrain.js"
        private const val ADS_API_URL = "https://ads.animeyabu.net/"
    }

    override val mainPage = mainPageOf(
        "$mainUrl/animes/letra/todos" to "Animes",
        "$mainUrl/anime-dublado" to "Animes Dublados",
        "$mainUrl/tokusatsus" to "Tokusatsus",
        "$mainUrl/doramas" to "Doramas",
        "$mainUrl/donghua" to "Donghuas",
    )

    private fun isDub(title: String, url: String? = null): Boolean {
        return title.contains("Dublado", ignoreCase = true) || 
               url?.contains("dublado", ignoreCase = true) == true ||
               url?.contains("anime-dublado", ignoreCase = true) == true
    }

    private fun cleanTitle(title: String): String {
        return title.replace("(?i)\\s*ep\\s*\\d+".toRegex(), "")
            .replace("(?i)\\s*-\\s*final".toRegex(), "")
            .replace("- Dublado", "", ignoreCase = true)
            .replace("\\s+".toRegex(), " ")
            .trim()
    }

    private fun extractEpisodeNumber(title: String): Int? {
        val fromEpisodio = "ep(?:is[oó]dio)?\\s*(\\d+)".toRegex(RegexOption.IGNORE_CASE)
            .find(title)?.groupValues?.get(1)?.toIntOrNull()
        if (fromEpisodio != null) return fromEpisodio

        val fromDash = "-\\s*(\\d+)".toRegex().find(title)?.groupValues?.get(1)?.toIntOrNull()
        if (fromDash != null) return fromDash

        val numberAtEnd = "\\b(\\d+)\\s*$".toRegex().find(title)?.groupValues?.get(1)?.toIntOrNull()
        return numberAtEnd
    }

    private fun extractTotalEpisodes(text: String): Pair<Int?, Int?> {
        val regex = "(\\d+)/(\\d+)".toRegex()
        return regex.find(text)?.let {
            val current = it.groupValues[1].toIntOrNull()
            val total = it.groupValues[2].toIntOrNull()
            current to total
        } ?: (null to null)
    }

    // Extrai o numero da temporada a partir do titulo da ficha.
    // Ex.: "Season 2", "Season 3 Part 2", "Temporada 2", "2nd Season".
    private fun extrairTemporada(titulo: String): Int {
        return try {
            val padroes = listOf(
                "(?i)temporada\\s*(\\d+)".toRegex(),
                "(?i)(\\d+)\\s*(?:ª|º|°)?\\s*temporada".toRegex(),
                "(?i)season\\s*(\\d+)".toRegex(),
                "(?i)(\\d+)(?:st|nd|rd|th)\\s+season".toRegex()
            )
            for (padrao in padroes) {
                padrao.find(titulo)?.groupValues?.get(1)?.toIntOrNull()?.let { return it }
            }
            if (titulo.contains("final season", ignoreCase = true)) 4 else 1
        } catch (e: Exception) {
            1
        }
    }

    // Extrai o numero da parte ("Season 3 Part 2" => 2). Ausente = parte 1.
    private fun extrairParte(titulo: String): Int {
        return try {
            "(?i)\\bpart(?:e)?\\s*(\\d+)".toRegex()
                .find(titulo)?.groupValues?.get(1)?.toIntOrNull() ?: 1
        } catch (e: Exception) {
            1
        }
    }

    // Le o total de episodios ("Episodios N/M") da ficha da temporada anterior,
    // usada para deslocar a numeracao das partes (ex.: "Season 3 Part 2").
    private suspend fun totalEpisodiosTemporadaAnterior(document: Document): Int? {
        return try {
            val anteriorUrl = document.selectFirst("a.tempAnterior")?.attr("href")
                ?.takeIf { it.isNotBlank() }
                ?.let { fixUrl(it) }
                ?: return null

            val anteriorDoc = app.get(anteriorUrl, referer = mainUrl).document
            var total: Int? = null
            anteriorDoc.select(DETAIL_EPISODES_INFO).forEach { element ->
                val text = element.text()
                if (text.contains("Episódios", ignoreCase = true)) {
                    val (_, t) = extractTotalEpisodes(text)
                    if (t != null) total = t
                }
            }
            total
        } catch (e: Exception) {
            null
        }
    }

    private fun findAllVideoUrls(document: Document): List<Pair<String, String>> {
        val videoUrls = mutableListOf<Pair<String, String>>()
        
        document.select("script").forEach { script ->
            val content = script.data()
            val regex = "var vid = '(https?://[a-zA-Z0-9]+\\.r2\\.cloudflarestorage\\.com/[^']+\\.mp4)'".toRegex()
            val matches = regex.findAll(content)
            
            matches.forEach { match ->
                val url = match.groupValues[1]
                val quality = when {
                    script.parent()?.id() == "jwContainer_2" -> "FULLHD"
                    script.parent()?.id() == "jwContainer_1" -> "HD"
                    script.parent()?.id() == "jwContainer_0" -> "SD"
                    url.contains("/fful/") -> "FULLHD"
                    url.contains("/f222/") -> "HD"
                    url.contains("/fiphoneb/") -> "SD"
                    url.contains("/fiphonec/") -> "SD"
                    url.contains("/f333/") -> "HD"
                    else -> "Unknown"
                }
                videoUrls.add(Pair(url, quality))
            }
        }
        
        val html = document.html()
        val cloudflareRegex = "https?://[a-zA-Z0-9]+\\.r2\\.cloudflarestorage\\.com/[a-zA-Z0-9]+/[0-9]+\\.mp4".toRegex()
        cloudflareRegex.findAll(html).forEach { match ->
            val url = match.value
            if (!videoUrls.any { it.first == url }) {
                val quality = when {
                    url.contains("/fful/") -> "FULLHD"
                    url.contains("/f222/") -> "HD"
                    url.contains("/fiphoneb/") -> "SD"
                    url.contains("/fiphonec/") -> "SD"
                    url.contains("/f333/") -> "HD"
                    else -> "Unknown"
                }
                videoUrls.add(Pair(url, quality))
            }
        }
        
        return videoUrls.distinctBy { it.first }
    }

    private fun Element.toSearchResponse(): AnimeSearchResponse? {
        val link = selectFirst(HOME_LINK) ?: return null
        val href = fixUrl(link.attr("href"))
        val title = selectFirst(HOME_TITLE)?.text()?.trim() ?: return null
        val poster = selectFirst(HOME_IMG)?.attr("src")?.let { fixUrl(it) }
        val episodeNum = selectFirst(HOME_EP_NUM)
        val tipo = selectFirst(HOME_TIPO)?.text()?.trim()
        val isDub = isDub(title, href)
        val isDorama = href.contains("/doramas/")
        val urlWithPoster = if (poster != null) "$href|poster=$poster" else href

        return if (episodeNum != null) {
            val epNumber = extractEpisodeNumber(title) ?: 1
            val animeName = cleanTitle(title)
            
            newAnimeSearchResponse(animeName, urlWithPoster, if (isDorama) TvType.TvSeries else TvType.Anime) {
                this.posterUrl = poster
                if (isDub) {
                    addDubStatus(DubStatus.Dubbed, epNumber)
                } else {
                    addDubStatus(DubStatus.Subbed, epNumber)
                }
            }
        } else {
            newAnimeSearchResponse(cleanTitle(title), urlWithPoster, if (isDorama) TvType.TvSeries else TvType.Anime) {
                this.posterUrl = poster
                if (isDub) {
                    addDubStatus(DubStatus.Dubbed, null)
                } else {
                    addDubStatus(DubStatus.Subbed, null)
                }
            }
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (request.name == "Últimos Episódios") {
            val document = app.get("$mainUrl/", referer = mainUrl).document
            val episodeElements = document.select(".epiContainer .ultimosEpisodiosHomeItem")
            val items = episodeElements
                .mapNotNull { it.toSearchResponse() }
                .distinctBy { it.url }
            
            return newHomePageResponse(
                list = HomePageList(request.name, items, isHorizontalImages = true),
                hasNext = false
            )
        }
        
        val maxPage = when {
            request.data.contains("/animes/letra/todos") -> 218
            request.data.contains("/anime-dublado") -> 51
            request.data.contains("/tokusatsus") -> 12
            request.data.contains("/doramas") -> 36
            request.data.contains("/donghua") -> 10
            else -> 50
        }
        
        val randomPage = (1..maxPage).random()
        val baseUrl = request.data.removeSuffix("/")
        val url = if (baseUrl.contains("/letra/todos")) {
            baseUrl.replace("/letra/todos", "") + "/page/$randomPage/letra/todos"
        } else {
            "$baseUrl/page/$randomPage"
        }
        
        val document = try {
            app.get(url, referer = mainUrl).document
        } catch (e: Exception) {
            val fallbackUrl = if (baseUrl.contains("/letra/todos")) {
                baseUrl.replace("/letra/todos", "") + "/page/1/letra/todos"
            } else {
                "$baseUrl/page/1"
            }
            app.get(fallbackUrl, referer = mainUrl).document
        }
        
        val items = document.select(HOME_ITEM)
            .mapNotNull { it.toSearchResponse() }
            .distinctBy { it.url }
        
        return newHomePageResponse(request.name, items, true)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        if (query.length < 2) return emptyList()
        
        val formattedQuery = query.replace(" ", "+")
        val searchUrl = "$mainUrl/busca?busca=$formattedQuery"
        
        val document = try {
            app.get(searchUrl).document
        } catch (e: Exception) {
            return emptyList()
        }

        val searchResults = mutableListOf<SearchResponse>()
        
        // 1. Extrai os resultados da Primeira Página
        searchResults.addAll(document.select(HOME_ITEM).mapNotNull { it.toSearchResponse() })
        
        // 2. Descobre a última página analisando as URLs (à prova de mudanças de layout)
        val lastPage = document.select("a[href]")
            .mapNotNull { link ->
                "(?:/page/|[?&]page=)(\\d+)".toRegex()
                    .find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            }
            .maxOrNull() ?: 1

        // 3. Se houver mais de 1 página, busca o resto de forma paralela (amap)
        if (lastPage > 1) {
            // CORREÇÃO AQUI: URL formatada exatamente como no HTML do site (&page=X)
            val pageUrls = (2..lastPage).map { "$mainUrl/busca?busca=$formattedQuery&page=$it" }
            
            val additionalResults = pageUrls.amap { pageUrl ->
                try {
                    val pageDoc = app.get(pageUrl).document
                    pageDoc.select(HOME_ITEM).mapNotNull { it.toSearchResponse() }
                } catch (e: Exception) {
                    emptyList<SearchResponse>() // Ignora erros individuais de página
                }
            }.flatten() // Achata todas as listas em uma só
            
            searchResults.addAll(additionalResults)
        }
        
        // 4. Retorna removendo possíveis itens duplicados
        return searchResults.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val (actualUrl, thumbPoster) = url.split("|poster=").let { 
            it[0] to it.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { fixUrl(it) }
        }

        val document = app.get(actualUrl).document
        val title = document.selectFirst(DETAIL_TITLE)?.text()?.trim() ?: "Sem título"

        // Temporada/parte ficam no h1 ("Season 3 Part 2"); o <title> e o fallback
        val tituloTemporada = document.selectFirst(DETAIL_TITLE)?.text()?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: document.title().trim()
        val seasonNumber = extrairTemporada(tituloTemporada)
        val parteNumber = extrairParte(tituloTemporada)

        // Partes ("Season 3 Part 2") colidem com a temporada base: desloca os
        // episodios pelo total da ficha irma; sem ela, usa 50 por parte
        val offsetEpisodios = if (parteNumber > 1) {
            totalEpisodiosTemporadaAnterior(document) ?: ((parteNumber - 1) * 50)
        } else {
            0
        }
        val isDub = isDub(title, actualUrl)
        val isDorama = actualUrl.contains("/doramas/")
        val poster = thumbPoster ?: document.selectFirst(DETAIL_POSTER)?.attr("src")?.let { fixUrl(it) }
        val synopsis = document.selectFirst(DETAIL_SYNOPSIS)?.text()?.trim() ?: "Sinopse não disponível"
        val genres = document.select(DETAIL_GENRES).map { it.text() }.filter { it.isNotBlank() }
        
        var year: Int? = null
        var totalEpisodes: Int? = null
        var tvType = if (isDorama) TvType.TvSeries else TvType.Anime

        document.select(DETAIL_EPISODES_INFO).forEach { element ->
            val text = element.text()
            when {
                text.contains("Ano") -> year = text.substringAfter("Ano").trim().toIntOrNull()
                text.contains("Episódios") -> {
                    val (current, total) = extractTotalEpisodes(text)
                    totalEpisodes = total
                }
                !isDorama && text.contains("Tipo") && text.contains("Filme", ignoreCase = true) -> {
                    tvType = TvType.AnimeMovie
                }
            }
        }

        val showStatus = if (document.selectFirst(DETAIL_STATUS)?.text()?.contains("Completo") == true) {
            ShowStatus.Completed
        } else {
            ShowStatus.Ongoing
        }

        // --- INÍCIO DA CORREÇÃO DE PAGINAÇÃO ---
        val episodes = mutableListOf<Episode>()

        // Função isolada para ler os episódios de qualquer Document (página 1, 2, 3...)
        fun parseEpisodesFromDoc(doc: Document): List<Episode> {
            val pageEps = mutableListOf<Episode>()
            doc.select(EPISODE_CONTAINER).select(EPISODE_ITEM).forEach { element ->
                val link = element.selectFirst(EPISODE_LINK) ?: return@forEach
                val episodeUrl = fixUrl(link.attr("href"))
                val episodeTitle = link.attr(EPISODE_TITLE_ATTR).takeIf { it.isNotBlank() } 
                    ?: element.selectFirst(HOME_TITLE)?.text()?.trim() ?: return@forEach
                
                // Extrai o numero real do episodio do titulo e aplica o deslocamento da parte
                val episodeNumber = extractEpisodeNumber(episodeTitle)?.plus(offsetEpisodios)
                val episodePoster = element.selectFirst("img")?.attr("src")?.let { fixUrl(it) }

                pageEps.add(
                    newEpisode(episodeUrl) {
                        name = if (tvType == TvType.AnimeMovie) "Filme" 
                               else if (episodeNumber != null) "Episódio $episodeNumber" 
                               else episodeTitle
                        episode = episodeNumber
                        season = seasonNumber
                        posterUrl = episodePoster ?: poster
                    }
                )
            }
            return pageEps
        }

        // 1. Extrai os episódios da Primeira Página
        episodes.addAll(parseEpisodesFromDoc(document))

        // 2. Descobre a última página analisando as URLs (à prova de mudanças de layout)
        val lastPage = document.select("a[href]")
            .mapNotNull { link ->
                "(?:/page/|[?&]page=)(\\d+)".toRegex()
                    .find(link.attr("href"))?.groupValues?.get(1)?.toIntOrNull()
            }
            .maxOrNull() ?: 1

        // 3. Se houver mais de 1 página, busca o resto de forma paralela (amap)
        if (lastPage > 1) {
            val pageUrls = (2..lastPage).map { "${actualUrl.removeSuffix("/")}/page/$it" }
            
            val additionalEpisodes = pageUrls.amap { pageUrl ->
                try {
                    val pageDoc = app.get(pageUrl).document
                    parseEpisodesFromDoc(pageDoc)
                } catch (e: Exception) {
                    emptyList<Episode>() // Se uma página falhar, não quebra tudo
                }
            }.flatten() // Achata as listas de cada página em uma lista única
            
            episodes.addAll(additionalEpisodes)
        }

        // 4. Deduplica por URL (a paginacao concatena sem dedupe) e ordena por temporada/episodio
        val uniqueEpisodes = episodes.distinctBy { it.url }
        val sortedEpisodes = uniqueEpisodes.sortedWith(
            compareBy({ it.season ?: 1 }, { it.episode ?: 0 })
        )

        return newAnimeLoadResponse(cleanTitle(title), actualUrl, tvType) {
            this.posterUrl = poster
            this.year = year
            this.plot = synopsis
            this.tags = genres
            this.showStatus = showStatus
            if (isDub) {
                addEpisodes(DubStatus.Dubbed, sortedEpisodes)
            } else {
                addEpisodes(DubStatus.Subbed, sortedEpisodes)
            }
        }
    }

    private fun extractTokenFromJson(jsonString: String): String? {
        return try {
            val jsonArray = JSONArray(jsonString)
            val jsonObject = jsonArray.getJSONObject(0)
            jsonObject.optString("publicidade")
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val episodePageUrl = data.split("|poster=")[0]
        
        val document = try {
            app.get(episodePageUrl, referer = mainUrl).document
        } catch (e: Exception) {
            return false
        }
        
        val baseUrls = findAllVideoUrls(document)
        
        if (baseUrls.isEmpty()) {
            return false
        }
        
        val priority = mapOf("FULLHD" to 4, "HD" to 3, "SD" to 2, "Unknown" to 1)
        val sortedUrls = baseUrls.sortedByDescending { priority[it.second] ?: 0 }
        
        var linksFound = false
        
        sortedUrls.forEach { (baseUrl, quality) ->
            try {
                val outbrainJs = app.get(
                    OUTBRAIN_URL,
                    headers = mapOf(
                        "Referer" to mainUrl,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                ).text
                
                val firstResponse = app.post(
                    url = ADS_API_URL,
                    data = mapOf(
                        "category" to "client",
                        "type" to "premium",
                        "ad" to outbrainJs
                    ),
                    headers = mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded",
                        "Referer" to mainUrl,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                ).text
                
                val token = extractTokenFromJson(firstResponse)
                
                if (token.isNullOrBlank()) {
                    return@forEach
                }
                
                val secondUrl = "$ADS_API_URL?token=$token&url=$baseUrl"
                
                val secondResponse = app.get(
                    secondUrl,
                    headers = mapOf(
                        "Referer" to mainUrl,
                        "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                ).text
                
                val authParams = extractTokenFromJson(secondResponse)
                
                if (authParams.isNullOrBlank()) {
                    return@forEach
                }
                
                val finalUrl = baseUrl + authParams
                
                val qualityValue = when (quality) {
                    "FULLHD" -> 1080
                    "HD" -> 720
                    "SD" -> 480
                    else -> 720
                }
                
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "Cloudflare $quality",
                        url = finalUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = qualityValue
                        referer = mainUrl
                        headers = mapOf(
                            "Referer" to mainUrl,
                            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                        )
                    }
                )
                
                linksFound = true
                
            } catch (e: Exception) {
                // Apenas ignora e continua para o próximo
            }
        }
        
        return linksFound
    }
}
