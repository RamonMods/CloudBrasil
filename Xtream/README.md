# Xtream

Provedor CloudStream 4 para painéis **Xtream Codes / Xtream UI** ( IPTV ). Lê canais ao vivo, filmes e séries pela `player_api.php`, com fallback para listas M3U (`#EXTINF`).

## Como usar

1. Instale o plugin `Xtream` no CloudStream (pre-release).
2. Vá em **Configurações → Provedores/Extensões → Xtream → Clonar site** (ou editar URL do site).
3. Cole a URL de configuração do seu fornecedor no campo de URL e salve.

### Formatos aceitos no campo de URL

```
host/get.php?username=USUARIO&password=SENHA&type=m3u_plus&output=ts
host/player_api.php?username=USUARIO&password=SENHA
usuario:senha@host:porta
```

Também funciona com esquema explícito (`http://` ou `https://`). O plugin extrai automaticamente o host/porta, o usuário e a senha; não é preciso separar nada.

O painel precisa responder em `http(s)://host:porta/player_api.php`. Se a API não estiver disponível, o plugin tenta `get.php?...&type=m3u_plus&output=ts` e faz o parser das linhas `#EXTINF`.

## Várias listas

Use **Clonar site** uma vez por lista. Cada clone é uma entrada independente do provedor, com o seu próprio usuário/senha/painel:

- clones diferentes = credenciais diferentes (ou o mesmo painel com filtros diferentes);
- rename cada clone (ex.: `Xtream Casa`, `Xtream Madrugada`) para identificar na busca;
- cada clone mantém sua própria validade e limite de conexões, exibidos em **Minha conta**.

## Minha conta e validade

A homepage traz a linha **Minha conta**, lida de `user_info` no retorno de `player_api.php`:

- usuário;
- validade (`exp_date`, convertida para `dd/MM/aaaa`);
- status (`active` → Ativa);
- conexões ativas / máximo permitido;
- indicação de conta trial.

Abra o item **Minha conta** para ver os detalhes completos. Se a conta expirar ou passar do limite de conexões, o status aparece como Inativa.

## Filtro dublado / legendado / ambos

O filtro detecta as palavras nos nomes dos canais, filmes e séries (`dublado`, `dub`, `legendado`, `leg`, `sub`):

- **Dublado** — na busca, digite `dublado` (opcionalmente seguido do termo, ex.: `dublado matrix`);
- **Legendado** — digite `legendado` (ex.: `legendado matrix`);
- **Ambos** — digite só o termo (ex.: `matrix`), sem prefixo de áudio.

A homepage também cria as linhas **Dublados** e **Legendados** com os títulos detectados, além das seções por categoria (`get_live_categories`, `get_vod_categories`, `get_series_categories`).

## Séries

Ao abrir uma série, o plugin consulta `get_series_info` e monta episódios de **todas as temporadas**, com número de temporada/episódio preenchidos, sinopse e capa quando disponíveis.

## Notas

- Idioma: `pt-br` (busca rápida habilitada).
- Downloads desabilitados: os links são streams diretos do painel.
- Não é necessário OAuth, WebView ou chave de API externa.
