# AnimesCloud

Módulo CloudStream original (código escrito do zero) para o catálogo
[AnimesDrive](https://animesdrive.cloud). Cobre animes e filmes/anime movies em PT-BR.

## Endpoints testados (26/11/2026)

Todas as requisições foram feitas com `curl` usando User-Agent de navegador Chrome.

| Uso | URL | Resultado |
|---|---|---|
| API do player | `https://animesdrive.cloud/wp-json/dooplayer/v2/` | `200`, namespace `dooplayer/v2` |
| Catálogo de animes | `https://animesdrive.cloud/anime` | `200`, 1.297 animes, paginação `/anime/page/2` |
| Catálogo de filmes | `https://animesdrive.cloud/filme` | `200`, cards `article.item.movies` |
| Episódios recentes | `https://animesdrive.cloud/episodio` | `200`, cards `article.item.episodes` |
| Ficha de série | `https://animesdrive.cloud/anime/one-piece` | `200`, 23 blocos `.se-c` (temporadas) e ~1.193 episódios |
| Ficha de episódio | `https://animesdrive.cloud/episodio/one-piece-episodio-1174` | `200`, post ID `81415` |
| Ficha de filme | `https://animesdrive.cloud/filme/jujutsu-kaisen-0-o-filme` | `200`, post ID `60479` |
| Busca JSON | `https://animesdrive.cloud/wp-json/dooplay/search/?keyword=one%20piece&nonce=2cf7e710c5` | `200`, JSON por ID com `title`, `url`, `img`, `extra.date`, `extra.imdb` |
| Busca sem nonce | `https://animesdrive.cloud/wp-json/dooplay/search?s=one%20piece` | `{"error":"no_verify_nonce"}` — nonce vem de `dtGonza` no HTML |
| Busca HTML (fallback) | `https://animesdrive.cloud/?s=jujutsu` | `200`, fichas em `article.item` |
| Player SD (episódio) | `https://animesdrive.cloud/wp-json/dooplayer/v2/81415/movie/1` | `type=mp4`, `source=animes.click/.../1174.mp4` |
| Player HD (episódio) | `https://animesdrive.cloud/wp-json/dooplayer/v2/81415/movie/2` | `type=iframe`, token `blogger.com/video.g` |
| Player FHD (episódio) | `https://animesdrive.cloud/wp-json/dooplayer/v2/81415/movie/3` | `type=mp4`, `ccdn.xyz/stream.php?...` |
| Player SD (filme) | `https://animesdrive.cloud/wp-json/dooplayer/v2/60479/movie/1` | `type=mp4`, `aniplay.online/.../01.mp4` |
| Blogger batch | `https://www.blogger.com/_/BloggerVideoPlayerUi/data/batchexecute?rpcids=WcwnYd&...` | `200`, devolve `googlevideo.com/videoplayback?...` |
| Favicon (ícone) | `https://animesdrive.cloud/wp-content/uploads/2026/08/cropped-ico-1-192x192.png` | `200 image/png` |

### Pegadinha da API do player

O caminho é `/dooplayer/v2/{id}/{type}/{nume}`, mas o segmento `{type}` é **`movie` no
singular mesmo para episódios**. Com `episodes`, `episode` ou `tvshows` a resposta é
`{"embed_url":null,"type":false}`. Os números válidos são `1`, `2` e `3` (SD/HD/FHD);
`0` e `4+` também retornam vazio.

Quando `type=mp4`, o `embed_url` é um wrapper
`jwplayer?source=<url codificada>&id=...`; o provider decodifica apenas o parâmetro
`source` e entrega o MP4 direto. Quando `type=iframe` e o host é `blogger.com/video.g`,
o provider resolve o token via `batchexecute` do Blogger e anexa o `cpn` necessário
para reproduzir. Iframes de hosts legados (strp2p/animeshd) são ignorados — são apenas
players, não catalogo.

## Como funciona

- **getMainPage**: três seções (`/anime`, `/filme`, `/episodio`) com paginação `/page/N`.
- **search**: usa a API `dooplay/search` com o nonce lido de `dtGonza` no HTML; se falhar,
  cai para a busca HTML `/?s=`.
- **load**: abre `/anime/<slug>` e percorre **todas** as temporadas em `#seasons .se-c`.
  Episódios são criados com `newEpisode(url)` (campo `.data`), com `season` e `episode`
  preenchidos, deduplicados por URL e ordenados por temporada/episódio. Episódios
  especiais (`1022.5`, `1108-5`) mantêm o número completo no nome.
- **loadLinks**: lê o post ID da página (`data-post-id`, `data-postid`, `data-post`),
  consulta as três fontes do `dooplayer/v2` e devolve links reproduzíveis.

## Arquivos

- `build.gradle.kts` — namespace `com.AnimesCloud`, `authors=["RamonMods"]`,
  `tvTypes=["Animes","AnimeMovie"]`, `language="pt-br"`, `version=1`, favicon real.
- `src/main/kotlin/com/AnimesCloud/AnimesCloudPlugin.kt` — `@CloudstreamPlugin`.
- `src/main/kotlin/com/AnimesCloud/AnimesCloud.kt` — `MainAPI`.
- `README.md` — este arquivo.

Gradle não foi executado e nenhum commit foi feito (conforme solicitado).
