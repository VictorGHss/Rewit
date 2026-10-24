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

## 4. Região da busca

`GET /api/v1/places/discovery/search` aceita uma região, escolhida pelo usuário no app, de três formas excludentes:

| Região | Parâmetros | Efeito no Geoapify |
| :--- | :--- | :--- |
| Cidade | `city` + `state` (UF obrigatória, uma das 27 siglas) | resolve a cidade e filtra por `filter=place:<id da cidade>`: resultados de outras cidades são **excluídos** |
| Perto de mim | `latitude` + `longitude` (+ `radius`) | `filter=circle:lon,lat,raio`; sem `radius`, 10 km; teto de 50 km |
| Qualquer lugar | nenhum | sem restrição; o app avisa que os resultados podem ser de qualquer cidade |

* Cidade junto com coordenadas, UF sem cidade, ou UF inválida (incluindo regiões como "Sul") → 400 `INVALID_REGION`, sem chamada ao provedor.
* Cidade não encontrada naquela UF → 422 `REGION_NOT_FOUND`; a busca nunca é feita em outra cidade.
* Com o provedor Google, a busca por cidade responde 400 `REGION_FILTER_UNSUPPORTED` em vez de ignorar a cidade.
* No app, "Perto de mim" lê a posição uma única vez, no toque de busca (o sistema pede a permissão); a posição não é guardada. A cidade escolhida nunca é trocada pela posição do aparelho.

## 5. Chamadas usadas

1. **Resolução da cidade** (só na região "Cidade"): `GET /v1/geocode/search?city=&state=<nome oficial da UF>&country=Brasil&type=city&filter=countrycode:br&format=json&lang=&limit=5&apiKey=`. Vale só um resultado `result_type=city` com o mesmo nome (sem diferenciar acentos e maiúsculas) e a mesma UF. O `place_id` e o centro ficam num cache em memória (até 256 cidades, sem dados de usuário), então buscas seguidas na mesma cidade custam uma chamada.
2. **Busca:** `GET /v1/geocode/search?text=&type=amenity&format=json&lang=&limit=&apiKey=` com o filtro da região (`place:` ou `circle:`) e `bias=proximity` no centro. Só resultados `result_type=amenity` com nome viram candidatos: ruas, bairros e endereços geocodificados são descartados.
3. **Detalhes:** `GET /v2/place-details?id=&features=details&lang=&apiKey=`.

Campos mapeados: `place_id`, `name`, `formatted`, `lat`/`lon`, `category`/`categories`, `city`, estado e `country_code`. Nota, horário de funcionamento e avaliações **não** são lidos nem inventados.

**Estado (UF).** Brasil (`country_code=br`): só uma das 27 siglas oficiais, vinda de `state_code` ou do nome oficial do estado ("Paraná" → `PR`); regiões ("Sul", "Sudeste") e siglas desconhecidas ficam `null`. Outro país: o código ou o nome do provedor, sem regras de UF. Sem `country_code`: `null`. Coordenadas não finitas ou fora das faixas descartam o item. O app só oferece "Adotar e selecionar" para candidatos com endereço, cidade, UF válida e coordenadas válidas; os demais seguem para o cadastro manual.

**Geocoding API ou Places API.** A busca continua na Geocoding API (`type=amenity`), que aceita texto livre e devolve `state_code` e `country_code`, necessários para validar a UF. A Places API (`/v2/places`) exige `categories` (o termo digitado teria de ser convertido numa lista de categorias, uma decisão de produto) e a documentação não lista `state_code`/`country_code` na resposta. Ela é a alternativa para buscas por categoria ("todas as padarias da cidade", inclusive sem "padaria" no nome) se esse caso for priorizado.

## 6. Erros

| Situação | Resposta da API Rewit |
| :--- | :--- |
| Chave ausente | 503 `EXTERNAL_SERVICE_NOT_CONFIGURED` (sem chamada externa) |
| Timeout | 504 `EXTERNAL_SERVICE_TIMEOUT` |
| Falha de conexão | 502 `EXTERNAL_SERVICE_UNAVAILABLE` |
| 429 (cota/limite) ou 5xx | 503 `EXTERNAL_SERVICE_UNAVAILABLE` |
| 401/403 (chave recusada) e demais 4xx | 502 `EXTERNAL_SERVICE_ERROR` |
| Resposta malformada | 502 `EXTERNAL_SERVICE_INVALID_RESPONSE` |
| 404 | lista vazia / candidato não encontrado |
| Região inválida | 400 `INVALID_REGION` (sem chamada externa) |
| Cidade não encontrada na UF | 422 `REGION_NOT_FOUND` |

## 7. Cota, atribuição e limites

* Plano gratuito: 3.000 créditos por dia e limite de requisições por segundo. O app só consulta a fonte externa por toque explícito em "Buscar em fontes externas", nunca a cada tecla. Uma busca por cidade ainda não resolvida custa duas chamadas (cidade + busca); as seguintes na mesma cidade, uma.
* Atribuição obrigatória junto dos resultados: "Powered by Geoapify" (<https://www.geoapify.com/>) e "© OpenStreetMap contributors" (ODbL, <https://www.openstreetmap.org/copyright>). O backend devolve ambas em `attributions` e o app as exibe abaixo da lista.
* Os dados são do OpenStreetMap, mantidos por voluntários: cobertura e atualização variam por região, e a presença de um estabelecimento não confirma que ele está aberto.
* Resultados sem cidade, UF válida ou coordenadas válidas não são adotáveis; o app oferece o cadastro manual.
* Retenção: mantém-se a regra atual (candidatos transitórios; persistem apenas o local adotado e a referência externa). Qualquer mudança de retenção exige revisar os termos do Geoapify e a licença ODbL.
