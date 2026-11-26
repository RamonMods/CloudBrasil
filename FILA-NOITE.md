# Fila da noite — 26/11/2026

Protocolo: skill `loop-noturno`. Sempre manter pelo menos um subagente/comando em background;
ao acordar, processar resultado, commitar, atualizar esta fila e disparar o proximo.

## Em andamento

- [ ] #1 Mapear nucleo do app CloudStream (recloudstream/cloudstream, gradle, .github, archive, dokka, site, sync) — subagente `ses_dea587c3bffezvmKvJHuHORWy4` -> `cloudstream-map/01-app.md`
- [ ] #2 Mapear ecossistema de plugins (extensions, cs-repos, TestPlugins, maven, csdocs, anime-db) — subagente `ses_dea581928ffek6kqlZikH1R5HH` -> `cloudstream-map/02-plugins.md`
- [ ] #3 Mapear libs/forks (NewPipeExtractor, UniFile, NiceHttp, libass, ffmpeg, torrent, etc.) — subagente `ses_dea5801daffeb6GtfTqnPMwMOv` -> `cloudstream-map/03-libs.md`
- [ ] #4 Pesquisar extensoes PT-BR e sites vivos ainda nao cobertos — subagente `ses_dea57d825ffezVNsbWJ3NuYe6F` -> `cloudstream-map/04-extensoes-novas.md`

## Proximas

- [ ] #5 Criar repo `RamonMods/CloudStream` (novo repo pedido pelo Ramon) com README, mapa do ecossistema, arquitetura e script de clone unico; apontar o CloudBrasil para ele
- [ ] #6 Juntar 01..04 no repo novo + atualizar `MAPEAMENTO.md` do CloudBrasil
- [ ] #7 Confirmar CI verde depois do fix `7073caf` (`.data` em Episode)
- [ ] #8 AnimesFHD: agregar `versions[]` (todas as temporadas/audios)
- [ ] #9 PobreFlix: reescrever para CMS Overflix (`youcine.quest`)
- [ ] #10 Modulo Xtream/M3U (listas multiplas, validade, filtro dublado/legendado)
- [ ] #11 Avaliar `C:\Users\ramon\Downloads\saimuelrepo` e portar provedores vivos
- [ ] #12 Implementar as melhores ideias de `04-extensoes-novas.md`
- [ ] #13 Procurar funcoes que o app CloudStream ainda nao tem e que o CloudBrasil pode expor

## Precisa do Ramon

- Remover de vez EmbedTV, StreamFlix, MendigoFlix e CineAgora (sites mortos) — exclusao nao foi autorizada explicitamente.
- Servidor/link do Discord.

## Feitas

- [x] README PT-BR, badges, subtitulo sem bandeira duplicada — `3d7bac9`
- [x] AnimeFire reescrito para API JSON — `ee62591`
- [x] Temporadas AniTube/DattebayoBR/Goyabu — `e1abe93`
- [x] Fix de compilacao `.data` (Episode) — `7073caf`
