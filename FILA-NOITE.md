# Fila da noite — 26/11/2026 (turno 2)

Protocolo: skill `loop-noturno`. Sempre manter pelo menos um subagente/comando em background;
ao acordar, processar resultado, commitar, atualizar esta fila e disparar o proximo.

## Em andamento

- [ ] #17 CI CloudBrasil — aguardando resultado do run `45744119124` (fix `internalName` do Xtream). Se falhar, ler `--log-failed` e corrigir.
- [ ] #18 CI app CloudStream — apos `832a488` (removido setup-android quebrado e locale workflow desativado), conferir se o build do APK passa.
- [ ] #12 Novos provedores (agentes escrevendo codigo original, sem copiar repos de terceiros):
  - `ses_dea463fc5ffebxMX42jOezvS0o` → `AnimesOnlineCC`
  - `ses_dea461d64ffePwSCXipEcRxIxf` → `DonghuaNoSekai`
  - `ses_dea45fe01ffe7Jmot61vhvsWxb` → `DoramasOnline`

## Proximas

- [ ] #19 Commitar e subir os 3 novos provedores; checar CI e corrigir erros de compilacao.
- [ ] #20 Atualizar `MAPEAMENTO.md` com os novos modulos e com a saida de `mapa/04-extensoes-novas.md`.
- [ ] #11 Avaliar `C:\Users\ramon\Downloads\saimuelrepo` e portar provedores vivos (respeitando licenca).
- [ ] #13 Procurar funcoes que o app CloudStream ainda nao tem e que o CloudBrasil pode expor.
- [ ] #21 Revisar Xtream: rodar validacao de compile via CI e testar parser M3U com lista publica (sem credenciais do Ramon).

## Precisa do Ramon

- Remover de vez EmbedTV, StreamFlix, MendigoFlix e CineAgora (sites mortos) — exclusao nao foi autorizada explicitamente.
- Servidor/link do Discord.
- Keystore propria para assinar os APKs (workflows de release foram desativados porque dependiam dos secrets do projeto original).

## Feitas

- [x] README PT-BR, badges, subtitulo sem bandeira duplicada — `3d7bac9`
- [x] AnimeFire reescrito para API JSON — `ee62591`
- [x] Temporadas AniTube/DattebayoBR/Goyabu — `e1abe93`
- [x] Fix de compilacao `.data` (Episode) — `7073caf` (CI verde)
- [x] FILA-NOITE.md e skill `loop-noturno` — `cf95b70`
- [x] Edge fechado (28 processos)
- [x] **Repo `RamonMods/CloudStream` independente** (nao e fork; `isFork=false`), HTML e codigo com links do Ramon — `c036537`, `3bbf4f2`, `832a488`
- [x] README + ECOSSISTEMA + ARQUITETURA + REPOSITORIOS + script de clone + 4 mapas em `mapa/` — `5bc4758`, `aba9b2d`
- [x] Traducao de todos os READMEs do app para PT-BR — `3041890`, `f2a1887`, `39d9289`
- [x] CloudBrasil aponta para o repo do app — `5926835`
- [x] Xtream (4 arquivos), AnimesFHD `versions[]`, PobreFlix Overflix — `2756305`
- [x] Fix build Xtream (`internalName` removido) — `f532433`
- [x] Workflow build-apk no repo do app — `4896e52`
