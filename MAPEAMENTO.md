# Mapa do repositório CloudBrasil

Levantamento feito em 26/11/2026. Status dos sites medido com requisição real (UA de navegador).

## Como instalar

- Shortcode no CloudStream: `CloudBrasil`
- URL do repositório: `https://raw.githubusercontent.com/RamonMods/CloudBrasil/main/builds/repo.json`

## Extensões

| Módulo | Base (mainUrl) | Tipos | v | Site | Estado do código | Pendência |
|---|---|---|---|---|---|---|
| **AnimeFire** | `https://animefire.one` | Animes | 2 | 200 | Scraping HTML morto (site virou SPA Angular). `season = 1` fixo | Reescrever para a API `api.animefire.one` |
| **AnimesFHD** | `https://animesfhd.net` | Anime, AnimeMovie | 1 | 200 | API JSON própria funcionando | Agregar `versions[]` (todas as temporadas) |
| **AniTube** | `https://www.anitube.zip` | Animes | 2 | 200 | Domínio corrigido; `season` ainda nulo | Ler temporada do título (`h1`) |
| **CineAgora** | `https://cineagora.net` | Movies, Series, Animes | 1 | **morto** | API de origem (`watch.brstream.cc`) viva e já entrega temporadas | Trocar fonte ou remover |
| **DattebayoBR** | `https://www.dattebayo-br.com` | Anime, AsianDrama | 1 | 200 | Funciona; `season` nulo, S3 Parte 2 colide | Temporada pelo `h1` + deslocar episódios da parte |
| **Doramogo** | `https://www.doramogo.net` | AsianDrama | 2 | 200 | Temporadas corretas (`data-season`/bloco por temporada) | Só dedupe defensivo |
| **EmbedTV** | — | Live | 3 | **morto** | Só canais ao vivo, host fora do ar | Remover |
| **Goyabu** | `https://goyabu.io` | Animes | 2 | 403 (Cloudflare) | Temporada fixa em 1; exige WebView | `season` pelo título + `usesWebView = true` |
| **MendigoFlix** | `https://mendigoflix.lol` | Movies, Series, Animes | 3 | 200 (parked) | Domínio estacionado, sem catálogo | Substituir/remover |
| **PobreFlix** | `https://lospobreflix.site` | Movies, Series, Animes, AsianDrama | 3 | **morto** | CMS migrou para Overflix (`youcine.quest`) | Reescrever parser |
| **ReiDosEmbeds** | `https://reidosembeds.lat` | Live | 1 | **timeout** | Live-only, API mudou de host | Revisar base/player |
| **StreamFlix** | `https://streamflix.live` | Movies, Series | 1 | **morto** | Código de temporada correto (Xtream) | Remover ou apontar para lista viva |
| **Xtream (M3U)** | configurável | Live, Movies, Series | — | — | **não criado ainda** | Módulo novo: listas M3U/Xtream, várias listas, validade, filtro dublado/legendado |

## Diagnóstico das temporadas

O CloudStream agrupa episódios por `Episode.season` — não existe `addSeason`. O bug de "só aparece a temporada 1" vem de:

1. `season` nunca preenchido (fica `null`) → AniTube, DattebayoBR.
2. `season = 1` fixo no código → AnimeFire, Goyabu.
3. Só a temporada da URL é carregada → AnimesFHD (as outras estão em `versions[]`).
4. Parser lendo formato antigo do site → PobreFlix.

## Pendências por prioridade

1. **Módulo Xtream/M3U** (pedido principal): colar URL da lista, várias listas via "Clonar site", validade/conexões da conta, filtro dublado/legendado/ambos.
2. **AnimeFire**: reescrever `load`/`search`/`loadLinks` para a API JSON.
3. **AnimesFHD**: carregar todas as temporadas de `versions[]`.
4. **PobreFlix**: reescrever para o CMS Overflix.
5. **AniTube / DattebayoBR / Goyabu**: temporada por regex no título.
6. **Limpeza**: remover EmbedTV, StreamFlix, MendigoFlix e CineAgora (sites mortos) ou trocar a fonte.

## Estrutura do repositório

- `build.gradle.kts` — configuração raiz; plugin Gradle do CloudStream fixado em commit válido do JitPack (`81b1d424d236`).
- `settings.gradle.kts` — inclui automaticamente toda pasta com `build.gradle.kts`.
- `.github/workflows/build.yml` — compila e publica `builds/*.cs3`, `plugins.json` e `repo.json`.
- `builds/` — artefatos gerados pelo CI (não editar à mão).

## Licença

GPL-3.0 (ver `LICENSE`).
