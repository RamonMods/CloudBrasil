# Mapa do repositório CloudBrasil

Atualizado em 26/11/2026. **CI verde** — 18 plugins publicados em `builds/`.

## Instalação

- Shortcode no CloudStream: `CloudBrasil`
- URL: `https://raw.githubusercontent.com/RamonMods/CloudBrasil/main/builds/repo.json`
- Repo do app/ecossistema (PT-BR): https://github.com/RamonMods/CloudStream

## Módulos publicados

| Módulo | Base | Tipos | Site | Temporadas |
|---|---|---|---|---|
| AnimeFire | `animefire.one` | Animes | 200 | ✅ via API JSON |
| AnimesCloud | `animesdrive.cloud` | Animes, AnimeMovie | 200 | ✅ 23 temporadas (One Piece) |
| AnimesDigital | `animesdigital.org` | Animes, AnimeMovie | 200 | ✅ todas as páginas |
| AnimesFHD | `animesfhd.net` | Anime, AnimeMovie | 200 | ✅ `versions[]` |
| AnimesOnlineCC | `animesonlinecc.to` | Animes | 200 | ✅ 8 temporadas (BNHA) |
| AniTube | `anitube.zip` | Animes | 200 | ✅ regex no título |
| CineAgora | `cineagora.net` | Movies, Series, Animes | morto | fonte antiga |
| DattebayoBR | `dattebayo-br.com` | Anime, AsianDrama | 200 | ✅ offset de "Part 2" |
| DonghuaNoSekai | `donghuanosekai.com` | Animes, AsianDrama | 200 | ✅ labels `S2/S3` |
| DoramasOnline | `doramasonline.net` | AsianDrama | 200 | ✅ (o `.co` está estacionado) |
| Doramogo | `doramogo.net` | AsianDrama | 200 | ✅ bloco por temporada |
| EmbedTV | — | Live | morto | — |
| Goyabu | `goyabu.io` | Animes | 403 (WebView) | ✅ regex no título |
| MendigoFlix | `mendigoflix.lol` | Movies, Series, Animes | parked | — |
| PobreFlix | `youcine.quest` | Movies, Series, Animes, AsianDrama | 200 | ✅ Overflix |
| ReiDosEmbeds | `reidosembeds.lat` | Live | timeout | — |
| StreamFlix | `streamflix.live` | Movies, Series | morto | — |
| **Xtream** | configurável | Live, Movies, Series | — | ✅ `get_series_info` + M3U |

## Xtream / listas M3U (pedido principal)

- Aceita `get.php?username=...&password=...`, `player_api.php?...` e `user:pass@host:port`.
- Várias listas: use **Clonar site** no app e troque `mainUrl` por instância.
- Conta: `getMainPage` mostra status, validade (`exp_date`) e conexões (`user_info`).
- Filtro: categorias "Dublados", "Legendados" e "Todos" por heurística no nome.
- Fallback: parser `#EXTINF` de M3U quando `player_api.php` não responde.

## Correções de temporadas

O CloudStream agrupa por `Episode.season` (não existe `addSeason`). Corrigimos:

1. `season` nulo → preenchido por regex/API.
2. `season = 1` fixo → removido.
3. Uma temporada só → agregamos `versions[]`, `#seasons .se-c`, paginação e offsets.
4. `Episode.url` não existe (é `.data`) e o construtor `Episode()` é proibido — tudo usa `newEpisode`.

## Pendências

- Validar reprodução no aparelho (principalmente players Blogger do AnimesOnlineCC/AnimesDigital).
- Remover módulos com site morto (EmbedTV, StreamFlix, MendigoFlix, CineAgora) — depende de autorização.
- Criar servidor Discord e publicar o convite no README.

## Estrutura

- Cada módulo é uma pasta com `build.gradle.kts` + `src/main/kotlin/com/<Nome>/`.
- CI: `.github/workflows/build.yml` → `builds/*.cs3`, `plugins.json`, `repo.json`.
- `mapa/` no repo `RamonMods/CloudStream` tem o levantamento completo do ecossistema.

## Licença

GPL-3.0. Créditos das extensões de terceiros foram mantidos; código novo é autoral.
