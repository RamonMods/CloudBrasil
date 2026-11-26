# Mapa do repositório CloudBrasil

Levantamento atualizado em 26/11/2026 (segundo turno). Status dos sites medido com requisição real (UA de navegador).

## Como instalar

- Shortcode no CloudStream: `CloudBrasil`
- URL do repositório: `https://raw.githubusercontent.com/RamonMods/CloudBrasil/main/builds/repo.json`
- Repo do app e do ecossistema (PT-BR): `https://github.com/RamonMods/CloudStream`

## Extensões

| Módulo | Base (mainUrl) | Tipos | v | Site | Estado do código | Pendência |
|---|---|---|---|---|---|---|
| **AnimeFire** | `https://animefire.one` | Animes | 2 | 200 | ✅ Reescrito para a API JSON `api.animefire.one`, com `season`/`episode` de todas as temporadas | Validar no app |
| **AnimesFHD** | `https://animesfhd.net` | Anime, AnimeMovie | 1 | 200 | ✅ Agrega `versions[]` (todas as temporadas/áudios, com offset) | Validar no app |
| **AniTube** | `https://www.anitube.zip` | Animes | 2 | 200 | ✅ Domínio corrigido, `season` pelo `h1`, dedupe | Validar no app |
| **CineAgora** | `https://cineagora.net` | Movies, Series, Animes | 1 | **morto** | API de origem (`watch.brstream.cc`) viva | Trocar fonte ou remover* |
| **DattebayoBR** | `https://www.dattebayo-br.com` | Anime, AsianDrama | 1 | 200 | ✅ Temporada pelo `h1`, offset de "Part 2", dedupe | Validar no app |
| **Doramogo** | `https://www.doramogo.net` | AsianDrama | 2 | 200 | ✅ Temporadas corretas | Só dedupe defensivo |
| **EmbedTV** | — | Live | 3 | **morto** | Host fora do ar | Remover* |
| **Goyabu** | `https://goyabu.io` | Animes | 2 | 403 (Cloudflare) | ✅ `season` pelo título + `usesWebView = true` | Validar no app |
| **MendigoFlix** | `https://mendigoflix.lol` | Movies, Series, Animes | 3 | 200 (parked) | Domínio estacionado | Substituir/remover* |
| **PobreFlix** | `https://youcine.quest` | Movies, Series, Animes, AsianDrama | 3 | 200 | ✅ Reescrito para Overflix/YouCine, episódios de todas as temporadas | Validar no app |
| **ReiDosEmbeds** | `https://reidosembeds.lat` | Live | 1 | **timeout** | Live-only, API mudou de host | Revisar base/player |
| **StreamFlix** | `https://streamflix.live` | Movies, Series | 1 | **morto** | Código de temporada correto (Xtream) | Remover* |
| **Xtream (M3U)** | configurável | Live, Movies, Series | 1 | — | ✅ Módulo criado: `player_api.php` + fallback M3U, várias listas via "Clonar site", validade/conexões, filtro dublado/legendado | Testar com lista real |
| **AnimesOnlineCC** | `https://animesonlinecc.to` | Animes | — | 200 | Em implementação | Novo |
| **DonghuaNoSekai** | `https://donghuanosekai.com` | Animes, AsianDrama | — | 200 (REST) | Em implementação | Novo |
| **DoramasOnline** | `https://doramasonline.co` | AsianDrama | — | 200 | Em implementação | Novo |

\* Remoção depende de autorização explícita do dono do repositório.

## Diagnóstico das temporadas (corrigido)

O CloudStream agrupa episódios por `Episode.season` — não existe `addSeason`. As causas do bug "só aparece a temporada 1" foram:

1. `season` nunca preenchido → **AniTube, DattebayoBR** (corrigido via regex no título).
2. `season = 1` fixo → **AnimeFire, Goyabu** (corrigido: API/título).
3. Só a temporada da URL era carregada → **AnimesFHD** (corrigido: `versions[]`).
4. Parser do formato antigo → **PobreFlix** (corrigido: Overflix).

Outro ponto que já corrigimos: `Episode` não tem `.url` (o campo é `.data`), e não se deve usar o construtor `Episode()` direto — use `newEpisode`.

## Pendências por prioridade

1. Validar no app as correções de temporadas e o módulo Xtream (CI/build).
2. Finalizar os 3 novos provedores e subir (AnimesOnlineCC, DonghuaNoSekai, DoramasOnline).
3. Avaliar `saimuelbr/saimuelrepo` e `franciscoalro/CloudstreamRepo` — fontes de plugins BR, mas **sem licença declarada**: não copiar código, apenas reimplementar do zero.
4. Limpeza dos módulos com site morto (depende de autorização).
5. Servidor Discord (link em breve).

## Estrutura do repositório

- `build.gradle.kts` — configuração raiz; plugin Gradle do CloudStream fixado em commit válido do JitPack (`81b1d424d236`).
- `settings.gradle.kts` — inclui automaticamente toda pasta com `build.gradle.kts`.
- `.github/workflows/build.yml` — compila e publica `builds/*.cs3`, `plugins.json` e `repo.json`.
- `builds/` — artefatos gerados pelo CI (não editar à mão).
- `FILA-NOITE.md` — fila de trabalho do modo `loop-noturno`.
- Repo do app/ecossistema: `https://github.com/RamonMods/CloudStream` (mapa, arquitetura, repositórios e documentação em PT-BR).

## Licença

GPL-3.0 (ver `LICENSE`).
