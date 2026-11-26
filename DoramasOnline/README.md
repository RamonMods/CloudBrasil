# DoramasOnline (CloudBrasil)

Módulo **novo e original** para o CloudBrasil. Pacote `com.DoramasOnline`,
`tvTypes = ["AsianDrama"]`, `language = "pt-br"`, `version = 1`,
`authors = ["RamonMods"]`.

## Situação dos domínios (importante)

| Domínio | Estado | Evidência |
|---|---|---|
| `https://doramasonline.co` | **estacionado** (parking) | `HTTP/1.1 200`, `Server: openresty`, `Permissions-Policy: ch-ua=(self "https://*.parklogic.com")`. Corpo é um challenge JS (`Router de parklogic`) que termina em `headlinelogic.com/?d=doramasonline.co...`, um portal de notícias sem nenhum catálogo de doramas. Todas as rotas testadas (`/`, `/series/`, `/serie/...`, `/?s=...`, `/wp-json/...`, `/robots.txt`) devolvem a mesma página de ~5,6 KB. O Wayback só guarda o antigo site WordPress/ToroFilm; `doramasonline.co/serie/...` nem está arquivado. |
| `https://doramasonline.net` | **ativo**, mesmo acervo e mesma marca | `GET /` → 200, 205.679 bytes, `<html lang="pt-BR">`, `wp-theme-temanormal`. `GET /?s=amor` → 200, 167.299 bytes com 50 resultados. |

Por isso o `mainUrl` do provider aponta para `.net`, que é onde o catálogo
continua publicado. O flag `usesWebView = true` foi mantido por causa do
challenge JavaScript presente no domínio `.co` informado.

## URLs testadas com curl (UA Chrome + redirecionamentos)

```
GET https://doramasonline.co/                                     200  5.600 B  (challenge ParkLogic)
GET https://doramasonline.co/series/                              200  5.619 B  (challenge)
GET https://doramasonline.co/serie/assistir-o-anti-heroi-online/  200  5.683 B  (challenge)
GET https://doramasonline.co/?s=anti+heroi                        200  5.613 B  (challenge)
GET https://doramasonline.co/wp-json/wp/v2/posts?search=amor      200  5.659 B  (challenge)
GET https://doramasonline.co/robots.txt                           200     26 B  ("User-agent: * / Disallow: /")

GET https://doramasonline.net/                                    200 205.679 B  (home real)
GET https://doramasonline.net/?s=amor                             200 167.299 B  (busca)
GET https://doramasonline.net/search/amor/                        200 167.324 B  (busca canonical)
GET https://doramasonline.net/br/series/                          200 206.245 B  (41 páginas)
GET https://doramasonline.net/br/series/crash/                    200            (2 temporadas, 16 episódios)
GET https://doramasonline.net/br/series/good-partner/             200            (2 temporadas, 18 episódios)
GET https://doramasonline.net/br/episodio/nine-to-six-nove-as-seis-temporada-1-episodio-1/  200 195.432 B
GET https://doramasonline.net/cdn9/odacdn/v2/?id=...01-dub/cdn_stream.m3u8...  200  4.896 B (player JWPlayer)
GET https://doramasonline.net/wp-content/uploads/2022/04/pngegg.png 200 image/png (favicon real do ícone)
```

## Seletores confirmados

**Home** (`GET /`)
- `#dt-tvshows article.item` — carrossel de doramas (`article.item.tvshows`)
- `#dt-movies article.item` — carrossel de filmes
- `article.item.episodes` / `.items.full article.item` — episódios recentes
- Cada card: `a[href*=/br/series/]`, `img[src]`, `h3`, `span` (ano)

**Busca** (`GET /?s=<termo>`)
- `.result-item article`
- `.details .title a` (título/URL), `.thumbnail img` (poster), `.meta .year`

**Lista paginada** (`GET /br/series/page/<n>/`)
- `article.item.tvshows`
- `.pagination span` → texto `Page 1 of 41`

**Ficha** (`GET /br/series/<slug>/`)
- `.sheader h1` (título), `.sheader .poster img`, `.sheader .extra .date` (ano)
- `#info .wp-content p` (sinopse), `.sgeneros a` (gêneros), `.dt_rating_vgs` (nota)
- `#seasons .se-c` — **um bloco por temporada**
  - `.se-t` e `.title` → número da temporada
  - `ul.episodios li` → `.episodiotitle a[href]` (URL do episódio),
    `.numerando` (`"2 - 1"` = temporada - episódio), `.imagen img` (still),
    `.date` (ano)

**Episódio** (`GET /br/episodio/<slug>-temporada-<t>-episodio-<e>/`)
- `div[id^=source-player-] iframe[src]` / `.pframe iframe[src]`
- Dois players: `01-dub` (DUBLADO) e `01-leg` (LEGENDADO)
- O HTML do iframe interno contém:
  `sources: [{"file": "https://.../cdn_stream.m3u8?token=...&expires=...", "type":"hls", "label":"1080p"}]`

## Comportamento do provider

- `getMainPage`: 4 abas (destaques, episódios recentes, filmes, todos os doramas),
  com paginação real na aba "Todos os doramas" e cache de 2 min da home.
- `search`: `?s=<termo>`, dedupe por URL.
- `load`: percorre **todas** as temporadas (`#seasons .se-c`), extrai season e
  episode de cada `li`, deduplica por URL e ordena por `(season, episode)`.
  Usa `newEpisode(url)` — portanto o endpoint do episódio é o campo `.data`.
  Filmes viram `newMovieLoadResponse` com um único episódio.
- `loadLinks`: resolve o HLS com token dentro do player interno e devolve cada
  faixa como `ExtractorLink` M3U8 (DUBLADO / LEGENDADO), com `referer` do site.

## Arquivos

```
DoramasOnline/
├── build.gradle.kts
├── README.md
└── src/main/kotlin/com/DoramasOnline/
    ├── DoramasOnlinePlugin.kt   (@CloudstreamPlugin)
    └── DoramasOnline.kt         (MainAPI: getMainPage / search / load / loadLinks)
```

O `settings.gradle.kts` da raiz já inclui automaticamente qualquer pasta com
`build.gradle.kts`; nenhum outro módulo foi alterado.
