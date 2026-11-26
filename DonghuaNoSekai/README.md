# DonghuaNoSekai (Cloudstream)

Plugin original em Kotlin para `https://donghuanosekai.com` (WordPress, pt-BR).
Lista donghuas, busca títulos, monta a lista de episódios com temporada/episódio
preenchidos e resolve os players até a URL direta do vídeo.

## Endpoints testados (curl com UA de navegador)

Todas as chamadas usam
`User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36`.

| Uso | Endpoint | Observação |
| --- | --- | --- |
| Home paginada (HTML) | `GET /page/2` | 200, cards `div.itemS` |
| Lista API do tema | `POST /wp-admin/admin-ajax.php` | `action=getListFilter`, requer `X-Requested-With: XMLHttpRequest` e `token` |
| Busca REST | `GET /wp-json/site/search/?keyword=swallowed&nonce=<nonce>` | nonce vem de `var pAPI = {...}` na home |
| Busca WordPress | `GET /?s=swallowed` | retorna cards `div.itemE.capa` |
| Posts REST | `GET /wp-json/wp/v2/posts?per_page=2&page=1` | episódios recentes, `content` vazio |
| Post por slug | `GET /wp-json/wp/v2/posts?slug=swallowed-star-episodio-250&_fields=id` | usado para reconstruir players |
| Série | `GET /swallowed-star` | lista completa de episódios |
| Episódio | `GET /swallowed-star-episodio-250` | botões `div.slideItem[data-video-url]` |
| Player | `GET /player?file=NjI4Mzc=&type=0` | `file` = base64 do ID do post |
| Player alternativo | `GET /player?file=NjY4OTc%3D&type=1` | vira `player-new` com `url: <m3u8>` no script (proxy `cdn.donghuanosekai.com`) |
| Player alternativo | `GET /player?file=NjI4NTU=&type=2` | vira iframe do Blogger (não resolvido) |

Exemplo do corpo POST da listagem (URL-encoded):

```
action=getListFilter
token=<data-secury da página /donghuas>
pagina=1
search=0            # ou o termo buscado
limit=30
type=lista
filters={"filter_data":"filter_letter=0&filter_status=all&filter_animation=all&filter_audio=all&filter_order=name","filter_genre_add":"","filter_genre_del":""}
```

Resposta: `{"page":1,"total_page":12,"total_results":30,"results":["<div class=\"itemE capa\">...", ...]}`.
O `filter_order` aceito é `name` (padrão) ou `new` (lançamentos) — ambos retornaram 200 com 30 itens.

## Seletores

- Card de série (API e busca WP): `div.itemE.capa`
  - link: `a[href]`; título: `h3 span.sxcd`; capa: `img[src]`
- Card da home: `div.itemS`
  - link: `a[href]`; título: `h2`; capa: `img[src]`
- Lista de episódios: `div.episode_list div.item a[href]`
  - rótulo: `span.episode` (ex.: `Episódio 250`, `Episódio 28 - (12 S2)`)
- Página do episódio: `div.slideItem[data-video-url]`, `meta[itemprop=thumbnailUrl]`
- Volta da página de episódio para a série: `span.articleCat a[href]`
- Página `/player`: `iframe[src]`, com `?v=` (nativov2) ou `?url=` (player-new legado)
- `player-new` atual: `DPlayer({ video: { url: "...m3u8" } })` no `<script>` (sem iframe)

## Temporadas e episódios

- `Episódio 28 - (12 S2)` → temporada 2, episódio 12.
- `Episódio 20 (04 S3)` → temporada 3, episódio 4.
- Sem marcação `(N Sx)`: usa a temporada única do título (`4ª Temporada` → 4).
  Títulos com várias temporadas (`1ª e 2ª Temporada`) caem na temporada 1.
- Sem temporada alguma: `season = 1` e episódios sequenciais pelo número do rótulo
  (base indexada quando o rótulo não tem número).
- Todos os episódios recebem `season` e `episode`; a lista é deduplicada por
  `it.data` e ordenada por temporada/episódio.

## Players

`loadLinks` lê os botões da página do episódio e, para cada um, abre `/player`:
a mídia direta (`.../index.m3u8` ou `.mp4`) vem do `iframe[src]` (`?v=` do
nativov2 ou `?url=` do player-new legado) ou, no player-new atual, do
`url:` do DPlayer no `<script>`. Cada URL é devolvida como `ExtractorLink`
com a qualidade inferida do nome do arquivo (`1080p`, etc.), com dedupe por
URL. Quando os botões não existem, o ID do post é buscado na REST e os players
`type=0`/`type=1` são reconstruídos. Players de terceiros (Blogger,
Dailymotion, YouTube) são ignorados.

## Limitações

- O `player?type=2` (Blogger) termina em `blogger.com/video.g?token=...`; não é
  convertido em stream direto e é ignorado.
- A REST do WordPress só expõe posts (episódios) — séries não aparecem em
  `/wp/v2/types`; por isso listagem e episódios vêm do HTML/API do tema.
- `token` (`data-secury`) e `nonce` da busca são dinâmicos; são lidos uma vez e
  cacheados em memória, com valores de fallback.
- `/page/N` muito alto pode devolver resultados repetidos/randomizados: a home
  é usada apenas como fallback e os cards são deduplicados por URL.
- Site protegido por Cloudflare em algumas rotas; requisições usam UA e headers
  de navegador e timeout generoso.
- Sem login: títulos "ACESSO ANTECIPADO" podem não ter episódios publicados.

## Arquivos

- `build.gradle.kts` — `internalName = DonghuaNoSekai`, `version = 1`,
  `namespace com.DonghuaNoSekai`, `authors = RamonMods`, `language = pt-br`,
  `tvTypes = Animes + AsianDrama`, ícone do favicon real.
- `src/main/kotlin/com/DonghuaNoSekai/DonghuaNoSekai.kt` — MainAPI.
- `src/main/kotlin/com/DonghuaNoSekai/DonghuaNoSekaiProvider.kt` — `@CloudstreamPlugin`.
- `README.md` — este arquivo.
