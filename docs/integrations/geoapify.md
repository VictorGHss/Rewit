# Integração com Geoapify — Descoberta Externa de Lugares (plano gratuito)

## 1. Visão Geral

Alternativa sem cobrança ao Google Places para a descoberta externa de locais. Usa o mesmo contrato da aplicação (`PlaceDiscoveryPort` → `PlaceCandidate`) e os mesmos endpoints da API Rewit; muda apenas o adapter de infraestrutura:

```
Cliente (App/Web)
  → API REST Rewit (/api/v1/places/discovery/...)
  → PlaceDiscoveryService → PlaceDiscoveryPort
  → GeoapifyPlacesAdapter (Infrastructure)
  → Geoapify (dados do OpenStreetMap)
  → PlaceCandidate (transitório)
```

O catálogo do Rewit (PostgreSQL + PostGIS) continua sendo a fonte de verdade. Nenhum resultado externo é gravado automaticamente: o local só entra no catálogo quando um usuário o adota por `POST /api/v1/places/adopt`, com `externalReference = {provider: "GEOAPIFY", externalId: <place_id>}` e a mesma deduplicação por `(provider, externalId)` usada para o Google.

## 2. Seleção do provedor

| Propriedade | Variável | Padrão |
| :--- | :--- | :--- |
| `rewit.place-discovery.provider` | `PLACE_DISCOVERY_PROVIDER` | `GOOGLE` (perfil padrão e `local`); `GEOAPIFY` no perfil `external-test` |
| `rewit.geoapify.api-key` | `GEOAPIFY_API_KEY` | vazio |
| `rewit.geoapify.base-url` | `GEOAPIFY_BASE_URL` | `https://api.geoapify.com` |
| `rewit.geoapify.connect-timeout-ms` / `read-timeout-ms` | `GEOAPIFY_CONNECT_TIMEOUT_MS` / `GEOAPIFY_READ_TIMEOUT_MS` | 3000 / 5000 |

* Exatamente um adapter é registrado; não há fallback entre provedores. Detalhes (`GET /api/v1/places/discovery/{provider}/{externalId}`) de um provedor que não é o ativo respondem 400 `UNSUPPORTED_PROVIDER`.
* Valor desconhecido em `PLACE_DISCOVERY_PROVIDER` impede a inicialização (erro de binding da propriedade).
* Sem chave, a aplicação sobe normalmente e a descoberta responde 503 `EXTERNAL_SERVICE_NOT_CONFIGURED`; o restante da API não é afetado.

## 3. Credencial

* Criar a chave em <https://myprojects.geoapify.com/> (cadastro gratuito, sem cartão): criar um projeto e copiar a API key.
* A chave fica **somente no backend**. O app e o painel nunca a recebem: falam apenas com a API Rewit.
* O Geoapify exige a chave na query string (`apiKey=`). Por isso o adapter usa um `RestClient` sem observação (a URL completa não vai para spans de tracing) e os logs registram só o tipo do erro e o status HTTP, nunca a URL nem a mensagem da exceção.
* No ambiente `external-test`, acrescentar apenas a linha `GEOAPIFY_API_KEY=<chave>` ao arquivo de ambiente externo, sem regenerá-lo, e reiniciar a API (`scripts/start-external-test.ps1`).

## 4. Chamadas usadas

1. **Busca:** `GET /v1/geocode/search?text=&type=amenity&format=json&lang=&limit=&apiKey=` com `bias=proximity:lon,lat` e `filter=circle:lon,lat,raio` quando há coordenadas (raio limitado a 50 km). Só resultados `result_type=amenity` com nome viram candidatos: ruas, bairros e endereços geocodificados são descartados.
2. **Detalhes:** `GET /v2/place-details?id=&features=details&lang=&apiKey=`.

Campos mapeados: `place_id`, `name`, `formatted`, `lat`/`lon`, `category`/`categories`, `city`, `state_code` (ou `state`) e `country_code`. Nota, horário de funcionamento e avaliações **não** são lidos nem inventados.

## 5. Erros

| Situação | Resposta da API Rewit |
| :--- | :--- |
| Chave ausente | 503 `EXTERNAL_SERVICE_NOT_CONFIGURED` (sem chamada externa) |
| Timeout | 504 `EXTERNAL_SERVICE_TIMEOUT` |
| Falha de conexão | 502 `EXTERNAL_SERVICE_UNAVAILABLE` |
| 429 (cota/limite) ou 5xx | 503 `EXTERNAL_SERVICE_UNAVAILABLE` |
| 401/403 (chave recusada) e demais 4xx | 502 `EXTERNAL_SERVICE_ERROR` |
| Resposta malformada | 502 `EXTERNAL_SERVICE_INVALID_RESPONSE` |
| 404 | lista vazia / candidato não encontrado |

## 6. Cota, atribuição e limites

* Plano gratuito: 3.000 créditos por dia e limite de requisições por segundo. O app só consulta a fonte externa por toque explícito em "Buscar em fontes externas", nunca a cada tecla.
* Atribuição obrigatória junto dos resultados: "Powered by Geoapify" (<https://www.geoapify.com/>) e "© OpenStreetMap contributors" (ODbL, <https://www.openstreetmap.org/copyright>). O backend devolve ambas em `attributions` e o app as exibe abaixo da lista.
* Os dados são do OpenStreetMap, mantidos por voluntários: cobertura e atualização variam por região, e a presença de um estabelecimento não confirma que ele está aberto.
* Resultados sem cidade, UF ou coordenadas não são adotáveis; o app oferece o cadastro manual.
* Retenção: mantém-se a regra atual (candidatos transitórios; persistem apenas o local adotado e a referência externa). Qualquer mudança de retenção exige revisar os termos do Geoapify e a licença ODbL.
