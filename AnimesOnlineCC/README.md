# AnimesOnlineCC

Plugin original para CloudBrasil/CloudStream do site **Animes Online CC**
(`https://animesonlinecc.to`, WordPress, HTTP 200). Código escrito do zero a partir
dos seletores descobertos com `curl` usando User-Agent de navegador — nenhum
código de terceiros foi copiado.

## URLs testadas (26/11/2026)

| URL | Resultado | Uso |
|---|---|---|
| `https://animesonlinecc.to/` | 200, 39 KB | home: mais vistos, últimos episódios, recentes |
| `https://animesonlinecc.to/?s=naruto` | 200 → `/search/naruto`, 13 KB | busca |
| `https://animesonlinecc.to/anime/` | 200, 25 KB | lista de animes (101 páginas) |
| `https://animesonlinecc.to/anime/page/2/` | 200, 25 KB | paginação da lista |
| `https://animesonlinecc.to/episodio/page/2/` | 200, 25 KB | paginação de episódios |
| `https://animesonlinecc.to/anime/naruto-shippuden/` | 200, 928 KB | ficha: 500 episódios, 1 temporada |
| `https://animesonlinecc.to/anime/boku-no-hero-academia/` | 200, 1,2 MB | ficha: 8 temporadas |
| `https://animesonlinecc.to/episodio/naruto-shippuden-episodio-1/` | 200, 169 KB | player com 2 opções (Dublado, Legendado) |
| `https://animesonlinecc.to/episodio/tsuihou-sareta-tensei-juukishi-wa-game-sashiki-de-musou-suru-episodio-22/` | 200, 22 KB | episódio recente, 1 opção HD |
| `https://animesonlinecc.to/episodio/koori-no-jouheki-2-episodio-9/` | 200, 18 KB | episódio recente, 1 opção HD |
| `https://www.blogger.com/video.g?token=...` | 200, 27 KB | player: HTML embute URLs `googlevideo` |
| URL `googlevideo` extraída (itag 22) | GET com Range → 206 `video/mp4` | stream final confirmado |

> HEAD no `googlevideo` devolve 403; o player do CloudStream usa GET/Range e funciona (206).

## Seletores

- **Home / mais vistos:** `#dt-seasons article.item.se.tvshows`
- **Home / recentes:** `#dt-tvshows article.item.tvshows`
- **Home / últimos episódios:** `#blog article.item.se.episodes`
- **Busca:** `GET /?s=<termo>` (redireciona para `/search/<termo>`) → `article.item.se.tvshows, article.item.tvshows`
- **Lista paginada:** `GET /anime/page/<n>/` → `article.item.tvshows`; próxima página em `.pagination a.arrow_pag`
- **Episódios paginados:** `GET /episodio/page/<n>/` → `article.item.se.episodes`
- **Cards:** `.poster a[href]`, `.poster img[src]`, `.data h3 a`, `.eptitle h3 a`, `.quality`
- **Ficha:** `.sheader .data h1`, `.sheader .poster img`, `.sheader .extra .date`,
  `.resumotemp .wp-content`, `.sgeneros a`, `.dt_rating_vgs`
- **Temporadas:** `#seasons .se-c` → `.se-q .se-t` (número da temporada),
  `.se-q .title` ("Temporada N"), `ul.episodios li`
- **Episódio da ficha:** `.numerando` ("Ep - N"), `.episodiotitle a[href*='/episodio/']`,
  `.imagen img`
- **Player:** `#playex .play-box-iframe iframe[src]`, rótulos em
  `nav.player .idTabs a.options[href='#option-N']` (Dublado / Legendado / HD)
- **Link para a ficha a partir do episódio:** `.areaserie a[href*='/anime/']`
- **Blogger:** iframe `blogger.com/video.g?token=...`; HTML do player traz os URLs
  finais escapados como `&quot;`, `\u003d`, `\u0026`. Depois de decodificar,
  `https://...googlevideo...videoplayback?...&itag=18|22` é o stream direto.

## Arquivos

- `build.gradle.kts` — `internalName` derivado da pasta (`AnimesOnlineCC`), package
  `com.AnimesOnlineCC`, `authors=["RamonMods"]`, `tvTypes=["Animes"]`,
  `language="pt-br"`, `version=1`, `iconUrl` = favicon real do site.
- `src/main/kotlin/com/AnimesOnlineCC/AnimesOnlineCC.kt` — `MainAPI` com
  `getMainPage`, `search`, `load`, `loadLinks`.
- `src/main/kotlin/com/AnimesOnlineCC/AnimesOnlineCCPlugin.kt` — `@CloudstreamPlugin`,
  registra o provider.
- `README.md` — este arquivo.

## Notas de implementação

- Todos os episódios usam `newEpisode(...)` e o campo `.data` (nunca `.url`).
- `season` e `episode` são preenchidos em **todas** as temporadas; quando o site não
  marca temporadas, agrupamos pelo prefixo do slug e numeramos a partir de 1.
- Episódios são deduplicados por `.data` e ordenados por temporada, número e URL.
- O card de episódio da home é resolvido via `.areaserie` para a ficha do anime.
- `accept-language: pt-BR` e User-Agent de Chrome são enviados em todas as requisições.
- Não foi rodado Gradle e não foi feito commit.
