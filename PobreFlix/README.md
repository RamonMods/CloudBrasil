# PobreFlix — CMS Overflix / YouCine

`mainUrl = https://youcine.quest`

## Status da investigação (25/11/2026)

| Alvo | HTTP | Resultado |
|---|---|---|
| `https://youcine.quest/` | 200 | HTML completo, seções reais |
| `/filmes`, `/series`, `/animes`, `/doramas` | 200 | Listagens paginadas |
| `/buscar?s=breaking+bad` | 200 | Resultados de busca |
| `/filmes/online/vingadores-doutor-destino-dublado-38495` | 200 | Ficha de filme |
| `/series/online/hudson-rex-dublado-6785` | 200 | Ficha de série, episódios de todas as temporadas |
| `/episodios/online/hudson-rex-1x1-dublado-1034656` | 200 | Ficha de episódio com handle do player |
| `/__siteplay/start` (POST) | 419 | `CSRF token mismatch` (meta `csrf-token` vem vazio para visitantes anônimos via curl) |

## Seletores confirmados

- Home: `section[data-overflix-tabs] .overflix-tab-panel .swiper-slide a.block`
- Listagem: `.vbListTableWrap .similarMovies a.block` + `.ipsPagination_next a[rel=next]`; páginas em `/page/<n>`
- Busca: `.overflix-search-results a.block`
- Ficha: `h2.one` (título), `.sinopse-text`, `.meta-info`, `.meta-generos`, `.fields-right p.extra` (elenco), `a.trailer[data-trailer-id]`
- Poster: `aside .vbItemImage img[src]` ou `meta[property='og:image']`
- Recomendações: `#vbRelatedSlider a.block`
- Episódios: `li.vbEpisodeCard[data-overflix-season-card][data-overflix-episode-number]`
  - `a.vbEpisodeCard__link[href]`, `.vbEpisodeCard__title`, `.vbEpisodeCard__synopsis`, `.vbEpisodeCard__thumb img`, `.vbEpisodeCard__date`
  - As temporadas além da primeira vêm no HTML com atributo `hidden`; o provider ignora o `hidden` e pega todas.
- Player: `[data-playbackhandle]` → `POST /__siteplay/start` → JSON `{ "sources": [{ "url": "..." }] }`, com headers `X-CSRF-TOKEN`, `X-Requested-With`, `Accept: application/json`.

## Episódios

`Episode.url` **não é usado**. O endereço da ficha do episódio fica em `Episode.data`, que é o valor passado a `loadLinks`. Os episódios são deduplicados por `data` e ordenados por `(season, episode)`.

## Fallback de player

Como o token CSRF do CMS vem vazio para requisições anônimas (a API responde 419), `loadLinks` tenta, em ordem:

1. `iframe[src]` presente no HTML da ficha;
2. URLs de player/embed/m3u8 em `<script>` e atributos `data-*`;
3. `POST /__siteplay/start` com o `data-playbackhandle` e o token lido de `meta[name='csrf-token']` (quando disponível, ex.: sessão logada no app).

A API oficial volta a funcionar assim que o CMS passar a emitir o token para o cliente; nenhuma outra mudança é necessária no provider.
