# Fila da noite — 26/11/2026 (final)

## Feitas

- [x] Repo `RamonMods/CloudStream` criado como **projeto independente** (`isFork=false`), sem aviso de fork, com todo o código e documentação em PT-BR.
- [x] Links/servidores do autor original trocados nos dois repos (mantidas só coordenadas de dependência que quebrariam o build).
- [x] Varredura completa (26/11, turno 3): removidos Discord/APK/docs/site do projeto original do README e das issues, repositório padrão do app agora é o CloudBrasil, workflows mortos que baixavam keystore de terceiros apagados, links em comentários de código limpos. Restaram apenas as coordenadas JitPack `com.github.recloudstream:*`, necessárias para compilar.
- [x] CI do app corrigida: **Build APK verde**.
- [x] CI do CloudBrasil corrigida: **build verde, 18 plugins publicados**.
- [x] Xtream/M3U (várias listas, validade, filtro dublado/legendado) — commit `5a6b500`/`367d9bf`.
- [x] Correção de temporadas: AnimeFire, AnimesFHD, AniTube, DattebayoBR, Goyabu, PobreFlix.
- [x] Novos provedores originais: AnimesOnlineCC, DonghuaNoSekai, DoramasOnline, AnimesDigital, AnimesCloud.
- [x] Mapas do ecossistema (app, plugins, libs, novas extensões, sites do saimuelrepo) em `mapa/` no repo do app.
- [x] Skill `loop-noturno` criada em `C:\Users\ramon\.agents\skills\loop-noturno\SKILL.md`.
- [x] Edge fechado; README/mapeado atualizados.

## Precisa do Ramon

- Validar reprodução no aparelho (players Blogger/HLS).
- Autorizar remoção dos módulos com site morto.
- Criar servidor Discord e colar o convite.
- Keystore própria para APK assinado (workflows de release desativados).

## Se amanhã continuar

1. Rodar o app e testar Xtream + novos provedores no celular.
2. Corrigir o que o teste no aparelho revelar.
3. Avaliar MegaFlix/NetCine/TopFilmes (relatório `mapa/05-saimuel-sites.md`) — sites fechados, provável baixo retorno.
