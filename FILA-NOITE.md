# Fila da noite — 26/11/2026

Protocolo: skill `loop-noturno`. Sempre manter pelo menos um subagente/comando em background;
ao acordar, processar resultado, commitar, atualizar esta fila e disparar o proximo.

## Em andamento

- [ ] #8 AnimesFHD: agregar `versions[]` (todas as temporadas/audios) — subagente `ses_dea4d5781ffeL0LYReMiXqi2Vw`
- [ ] #9 PobreFlix: reescrever para CMS Overflix (`youcine.quest`) — subagente `ses_dea4d3a09ffeAzXhk6EggvMBch`
- [ ] #10 Modulo Xtream/M3U (listas multiplas, validade, filtro dublado/legendado) — subagente `ses_dea4d93d9ffeYAgeDEcn2E8Y6b`
- [ ] #14 Mapear libs/forks da org recloudstream — subagente `ses_dea4d1fd0ffeOPRsLd3J7W9laY` -> `cloudstream-map/03-libs.md`
- [ ] #15 Pesquisar novas extensoes PT-BR e sites vivos — subagente `ses_dea4d0190ffepMRhWlOkk6sF4w` -> `cloudstream-map/04-extensoes-novas.md`

## Proximas

- [ ] #16 Juntar `cloudstream-map/*.md` no repo `RamonMods/CloudStream` quando os mapas chegarem
- [ ] #17 CI: conferir resultado do build apos Xtream/AnimesFHD/PobreFlix e corrigir erros de compilacao
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
- [x] Fix de compilacao `.data` (Episode) — `7073caf` (CI verde)
- [x] FILA-NOITE.md criada — `cf95b70`
- [x] Skill `loop-noturno` criada em `C:\Users\ramon\.agents\skills\loop-noturno\SKILL.md`
- [x] Edge fechado (28 processos)
- [x] **Repo `RamonMods/CloudStream` criado** (fork do app) e com todos os arquivos: README PT-BR, ECOSSISTEMA.md (25 repos), ARQUITETURA.md, REPOSITORIOS.md, scripts/clonar-ecossistema.ps1 — commits `5bc4758`, `3041890`, `f2a1887`, `39d9289`
- [x] CloudBrasil aponta para o repo do app — `5926835`
