# AnimesDigital

Plugin original para CloudBrasil/CloudStream do site **AnimesDigital**
(`https://animesdigital.org`, WordPress, HTTP 200). Código escrito do zero a partir
dos seletores descobertos com `curl` (User-Agent de Chrome, `--noproxy '*'`) — nenhum
código de terceiros foi copiado: o repo de origem (`saimuelbr/saimuelrepo`) não declara
licença.

## URLs testadas (26/11/2026)

| URL | Status | Uso |
|---|---|---|
| `https://animesdigital.org/` | 200, 76.800 B | home: `.itemE` (TOP/Últimos episódios) e `.itemA` (Últimos animes/filmes) |
| `https://animesdigital.org/wp-content/uploads/2025/03/cropped-logo-e1740827992823-192x192.png` | 200, `image/png`, 25.108 B | `iconUrl` real |
| `https://animesdigital.org/pesquisa/naruto/` | 200 → `/pesquisa/naruto/`, 49.604 B | busca: 20 cards `.itemA` |
| `https://animesdigital.org/pesquisa/one%20piece/` | 200, 33.146 B | busca com espaço (7 animes) |
| `https://animesdigital.org/animes-legendados-online001` | 200, 55.095 B | lista com filtros (cards via JS) |
| `https://animesdigital.org/lancamentos01/` | 200, 47.549 B, 16 `.itemE` | aba "Últimos episódios" |
| `https://animesdigital.org/ultimos-titulos-adicionados/` | 200, 55.955 B | aba "Últimos animes" |
| `https://animesdigital.org/anime/a/dragon-ball-super-beerus-dublado` | 200, 36.197 B | ficha: `.animeInfos h1`, `.sinopse`, 3 episódios |
| `https://animesdigital.org/anime/a/one-piece` | 200, 204.387 B | ficha: 51 episódios na página 1, **25 páginas** |
| `https://animesdigital.org/anime/b/003100031001/page/2/` | 200, 204.956 B | página 2 com 51 episódios |
| `https://animesdigital.org/video/a/139852/` | 200, 37.298 B | 2 players (`#player1`, `#player2`) |
| `https://api.anivideo.net/cdn/v1....` (iframe do Player FHD) | 200 com `Sec-Fetch-Dest: iframe` + `Referer` | JWPlayer com `file: "...mp4/index.m3u8"` |
| `https://cdn-s01.mywallpaper-4k-image.net/stream/sv/d/.../index.m3u8` | 200, `application/vnd.apple.mpegurl` | stream final do Player FHD |
| `https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute?rpcids=WcwnYd...` | 200 | RPC do Player 2 → URLs `googlevideo` |

> Sem `Sec-Fetch-Dest: iframe` o CDN responde **403** com
> `x-ad-hls-block-reason: NOT_IFRAME`. O plugin envia os cabeçalhos corretos.

## Seletores

- **Home:** `.itemE` (com `.title .title_anime` + `.title .number`) e `.itemA`
- **Listas paginadas:** `/lancamentos01/page/N/` e
  `/ultimos-titulos-adicionados/page/N/` → `.itemE`
- **Busca:** `GET /pesquisa/<termo>/` → `.itemA`; próxima página em
  `.content-pagination li.next a[href]`
- **Ficha:** `.animeInfos h1`, `.animeInfos .poster img`, `.animeInfos .sinopse`,
  `.animeInfos .info` (Ano), `.animeInfos .genre a`
- **Episódios:** `.itens_ep .item_ep a[href*='/video/a/']` com `.title_anime` e
  `.thumb img`; paginação em `.content-pagination li a[href*='/page/']`
- **Link da ficha a partir do episódio:** `.linksEP .epsL a[href*='/anime/a/']`
- **Players:** `.tab-video iframe[src]`, rótulos em
  `.tabs_videos li[data-tab='#playerN']`
- **Player FHD:** HTML do iframe com `file: "https://.../index.m3u8"`
- **Player 2:** iframe aninhado `blogger.com/video.g?token=...` → RPC `WcwnYd`

## Arquivos criados

- `build.gradle.kts` — padrão AnimeFire (`authors=["RamonMods"]`, `version=1`,
  `tvTypes=["Animes","AnimeMovie"]`, `language="pt-br"`, `iconUrl` do favicon real).
- `src/main/kotlin/com/AnimesDigital/AnimesDigitalPlugin.kt` — `@CloudstreamPlugin`,
  registra o provider.
- `src/main/kotlin/com/AnimesDigital/AnimesDigital.kt` — `MainAPI` com `getMainPage`,
  `search`, `load` e `loadLinks`.
- `README.md` — este arquivo.

## Notas de implementação

- Todos os episódios usam `newEpisode(...)` e o campo `.data` (nunca `.url`).
- `load` percorre **todas as páginas** de episódios da ficha (One Piece: 25 páginas /
  ~1.200 episódios) e preenche `season` e `episode`. A temporada vem do título
  (`2ª Temporada`, `Season 2`, `S2`) com fallback 1; o número vem de
  `Episódio N` (aceita decimais como `1133.5`).
- Episódios são deduplicados por `.data` e ordenados por temporada, número e URL.
- Cards de episódio da home são resolvidos para a ficha do anime via `.linksEP`.
- `loadLinks` resolve o Player FHD para HLS e o Player 2 para os streams do Blogger;
  se nada for extraído, devolve o próprio iframe como fallback.
- Não foi rodado Gradle e não foi feito commit. Nenhum outro módulo foi tocado.
