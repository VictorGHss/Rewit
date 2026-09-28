# Integração com Google Places API (New) — Descoberta Externa de Lugares

## 1. Visão Geral

Este documento formaliza a integração de **descoberta externa de lugares** utilizando a geração atual **Google Places API (New)**, implementada no **STEP 8** da arquitetura do Rewit.

A finalidade desta integração é permitir a consulta sob demanda de locais externos para descoberta, sem transformar o Google na fonte da verdade do catálogo do Rewit:

```
Cliente (App/Web)
  → API REST Rewit (/api/v1/places/discovery/...)
  → PlaceDiscoveryService (Application)
  → PlaceDiscoveryPort (Port)
  → GooglePlacesAdapter (Infrastructure)
  → Google Places API (New)
  → PlaceCandidate (Transitório)
```

O banco relacional do Rewit (PostgreSQL + PostGIS) permanece sendo a fonte de verdade exclusiva do catálogo local.

---

## 2. Provedor e Escopo de Operações

* **Provedor Suportado:** `GOOGLE`
* **Geração da API:** **Places API (New)** (API moderna REST v1 da Google Cloud).
* **APIs Legadas:** APIs legacy (`https://maps.googleapis.com/maps/api/place/...`) **não** são suportadas nem utilizadas.

### Operações Suportadas no STEP 8:

1. **Text Search (New)**:
   * **Endpoint do Google:** `POST https://places.googleapis.com/v1/places:searchText`
   * **Finalidade:** Busca textual de estabelecimentos por termo livre com viés geográfico opcional (latitude, longitude, raio).
2. **Place Details (New)**:
   * **Endpoint do Google:** `GET https://places.googleapis.com/v1/places/{placeId}`
   * **Finalidade:** Obtenção de dados pontuais de um local externo específico a partir de seu identificador externo.

> **Restrições de Escopo (STEP 8):** Não são suportados Nearby Search, Autocomplete, Fotos do Google, Avaliações/Reviews do Google, Google Business Profile ou Google OAuth.

---

## 3. Autenticação e Gestão Segura de Credenciais

* **Chave de API:** Gerenciada exclusivamente no backend através da propriedade:
  * Variável de ambiente: `GOOGLE_PLACES_API_KEY`
  * Propriedade Spring: `rewit.google.places.api-key`
* **Placeholder:** No arquivo `.env.example`, o valor documentado é estritamente:
  ```properties
  GOOGLE_PLACES_API_KEY=change-me
  ```
* **Proteção contra Vazamento:**
  * O cliente web/mobile **nunca** recebe nem envia a chave de API da Google.
  * A chave é injetada internamente pelo adapter no cabeçalho HTTP:
    ```http
    X-Goog-Api-Key: <CHAVE_INTERNA>
    ```
  * O backend **nunca** loga a chave de API em logs de aplicação e nem inclui a credencial em mensagens de erro ou rastreamentos de exceção.
* **Operação sem Chave Configurada:**
  * A aplicação sobe normalmente mesmo se a variável não estiver definida.
  * Caso um usuário autenticado chame o endpoint de descoberta sem a chave configurada no servidor, a API retorna erro HTTP 503 (`EXTERNAL_SERVICE_NOT_CONFIGURED`) de forma controlada.

---

## 4. Política de FieldMask e Categorias de Cobrança da Google

A documentação oficial do Google Places API (New) exige explicitamente o cabeçalho `X-Goog-FieldMask` para todas as chamadas. Os campos solicitados determinam o SKU / tier de cobrança cobrado pela Google Cloud.

> **Importante:** As máscaras adotadas **não** devem ser chamadas genericamente de `Basic`, pois a precificação da Places API (New) é segmentada por campo.

### Máscaras Adotadas e Tiers Reais de Cobrança:

| Operação | Cabeçalho `X-Goog-FieldMask` | Categorias e Tiers por Campo (Places API New) |
| :--- | :--- | :--- |
| **Text Search** | `places.id,places.displayName,places.formattedAddress,places.location,places.types,places.attributions` | **Text Search Pro**<br>• `id` e `attributions`: Essentials (IDs Only)<br>• `displayName`, `formattedAddress`, `location` e `types`: **Text Search Pro** (determina a cobrança da chamada). |
| **Place Details** | `id,displayName,formattedAddress,location,types,attributions` | **Essentials + Pro**<br>• `id` e `attributions`: Essentials (IDs Only)<br>• `formattedAddress`, `location`, `types`: Essentials<br>• `displayName`: Pro (eleva a requisição para **Place Details Pro**) |

### Regras Estritas de FieldMask:
1. **Proibição de Wildcard (`*`):** O caractere `*` acionaria a tarifação mais cara da Google (incluindo chamadas caras de fotos e avaliações completas), além de desperdiçar banda e aumentar a latência. Testes unitários do adapter garantem que `*` nunca é enviado.
2. **Sem Espaços e Não Vazia:** A string de FieldMask não contém espaços após as vírgulas e não pode ser vazia.
3. **Prefixos Estritos:**
   - Chamadas de **Text Search** utilizam obrigatoriamente o prefixo `places.` em todos os campos (`places.id,places.displayName,...,places.attributions`).
   - Chamadas de **Place Details** utilizam os nomes dos campos diretamente sem prefixo (`id,displayName,...,attributions`).
4. **Campos Estritamente Necessários:** Apenas identificador, nome, endereço, latitude/longitude, categorias e atribuições legais obrigatórias são solicitados, suprindo com precisão as necessidades de catálogo do Rewit.
5. **Impacto de Custo Neutro para Atribuições:** A inclusão de `attributions` (Essentials IDs Only) tem custo neutro, sendo absorvida pelo tier Pro já determinado por `displayName`.

---

## 5. Google Place ID e Ciclo de Vida da Identidade Externa

### Place ID Não é Identidade Primária:
* O Google Place ID (ex: `ChIJN1t_tDeuEmsRUsoyG83frY4`) é tratado como **identificador externo volátil**.
* O Rewit **nunca** substitui seu identificador primário interno (`Place.id`, UUID) pelo Google Place ID.
* O Place ID **nunca** é utilizado como chave estrangeira relacional interna.

### Volatilidade e Ciclo de Vida:
* Conforme documentado pela Google, Place IDs podem expirar, mudar ou sofrer fusão (*merge*) ao longo do tempo (por exemplo, quando estabelecimentos fecham, mudam de endereço ou são reclassificados).
* A representação de vínculo com provedores externos no domínio do Rewit pertence exclusivamente à entidade:
  ```
  PlaceExternalReference (place_id: UUID, provider: "GOOGLE", external_id: "ChIJ...")
  ```

---

## 6. Ausência de Persistência Automática

* A busca e consulta de candidatos via API de descoberta é **estritamente informativa e volátil**.
* O resultado retornado pela aplicação é um modelo transitório `PlaceCandidate` / DTO `PlaceCandidateResponse`.
* Nenhuma tupla é inserida na tabela `places` ou `rateable_targets` durante as requisições de descoberta.
* O processo de importação, curadoria ou associação (*linking*) de um candidato externo ao catálogo persistente local será objeto de etapas posteriores.

---

## 7. Tratamento de Erros e Problem Details (RFC 7807)

Todas as falhas originadas no provedor externo são interceptadas pelo adapter e mapeadas para respostas HTTP padronizadas, sem expor endpoints internos, chaves ou stack traces:

| Cenário Externo / Falha | Status HTTP | Código RFC 7807 (`code`) | Comportamento |
| :--- | :--- | :--- | :--- |
| Chave não configurada no servidor | 503 Service Unavailable | `EXTERNAL_SERVICE_NOT_CONFIGURED` | Bloqueia a chamada antes da rede externa. |
| Timeout de conexão ou leitura | 504 Gateway Timeout | `EXTERNAL_SERVICE_TIMEOUT` | Disparado se a Google exceder connect/read timeout. |
| Google indisponível ou 5xx | 503 Service Unavailable | `EXTERNAL_SERVICE_UNAVAILABLE` | Falha temporária da infraestrutura externa. |
| Google 429 (Limite de taxa) | 503 Service Unavailable | `EXTERNAL_SERVICE_UNAVAILABLE` | Esgotamento de cota na Google Cloud. |
| Google 400 / 401 / 403 | 502 Bad Gateway | `EXTERNAL_SERVICE_ERROR` | Falha de autorização ou parâmetros upstream. |
| Google 404 (Place Details) | 404 Not Found | `EXTERNAL_PLACE_NOT_FOUND` | Local externo inexistente no provedor. |
| Resposta truncada ou JSON inválido | 502 Bad Gateway | `EXTERNAL_SERVICE_INVALID_RESPONSE` | Resposta ilegível recebida do upstream. |

---

## 8. Endpoints da API REST

Ambos os endpoints exigem autenticação via token Bearer JWT (`Authorization: Bearer <token>`).

### 1. Busca de Candidatos
```http
GET /api/v1/places/discovery/search?query={texto}&latitude={lat}&longitude={lng}&radius={metros}&limit={limite}
```
* `query` (obrigatório, não vazio): termo de pesquisa.
* `latitude` / `longitude` (opcionais, par obrigatório se informado): viés espacial em WGS 84.
* `radius` (opcional, metros): raio de viés (default 5000m se coordenadas fornecidas; teto máximo 50000m / 50km).
* `limit` (opcional, inteiro de 1 a 20, default 10).

#### Semântica do Parâmetro `limit` e Paginação
* O port e endpoint expõem o parâmetro `limit` (intervalo de 1 a 20, default 10).
* **Não se trata de paginação completa:** Não existe `pageToken` nem `nextPageToken` no contrato exposto no STEP 8.
* O comportamento implementado é estritamente o **controle do tamanho da página inicial / quantidade máxima solicitada ao upstream**.
* O adapter envia esse valor no corpo JSON da busca textual sob o campo **`pageSize`** (em conformidade com a recomendação da Google Places API New, substituindo o campo descontinuado `maxResultCount`).
* A implementação de paginação entre páginas subsequentes fica reservada para etapas futuras.

#### Comportamento Geográfico e Viés Espacial (`locationBias`)
* **Latitude/Longitude ausentes:** Nenhum objeto `locationBias` é enviado ao Google. A chamada HTTP para a API do Google é executada server-side pelo backend do Rewit; portanto, o Google recebe o IP de saída do servidor backend (ou do NAT Gateway / proxy de saída da infraestrutura). **Quando latitude/longitude não são fornecidas, a busca fica sujeita ao IP visto pelo Google, que no fluxo atual corresponde ao componente de saída do backend.** Consequentemente, para garantir que o resultado seja contextualizado geograficamente ao usuário final, o cliente (App Mobile ou Web) deve encaminhar coordenadas explícitas (`latitude` e `longitude`).
* **Latitude/Longitude presentes sem raio:** Aplica-se raio circular padrão de `5000.0` metros (5 km).
* **Latitude/Longitude presentes com raio:** O raio informado é respeitado até o teto máximo de `50000.0` metros (50 km), em conformidade com o limite estabelecido pela documentação do Google Places API para círculos de `locationBias`.
* **Raio inválido (`<= 0`):** Requisições com raio menor ou igual a zero são interceptadas na camada de aplicação pelo `PlaceDiscoveryService` e rejeitadas com erro HTTP 400 (`INVALID_RADIUS`). Como salvaguarda de defesa em profundidade, o `GooglePlacesAdapter` possui fallback para o raio padrão de `5000.0` metros.
* **Distinção entre Bias e Restriction:** A integração adota propositalmente `locationBias` e **não** `locationRestriction`. O parâmetro `locationBias` atua como viés de preferência (o algoritmo do Google prioriza lugares dentro da área circular, mas ainda pode retornar correspondências fortes fora do raio caso sejam mais relevantes). Não se trata de geofencing restritivo ou excludente.

**Resposta de Sucesso (200 OK):**
```json
[
  {
    "provider": "GOOGLE",
    "externalId": "ChIJN1t_tDeuEmsRUsoyG83frY4",
    "displayName": "Restaurante Exemplo",
    "formattedAddress": "Av. Paulista, 1000 - Bela Vista, São Paulo - SP",
    "latitude": -23.565,
    "longitude": -46.651,
    "types": ["restaurant", "food", "point_of_interest"],
    "attributions": [
      {
        "provider": "OpenStreetMap",
        "providerUri": "https://www.openstreetmap.org"
      }
    ]
  }
]
```

### 2. Detalhes de Candidato
```http
GET /api/v1/places/discovery/{provider}/{externalId}
```
* `{provider}`: No STEP 8, o único provedor suportado é `GOOGLE`. Outros valores resultam em HTTP 400 (`UNSUPPORTED_PROVIDER`).
* `{externalId}`: Identificador externo do local (Google Place ID).

**Resposta de Sucesso (200 OK):**
```json
{
  "provider": "GOOGLE",
  "externalId": "ChIJN1t_tDeuEmsRUsoyG83frY4",
  "displayName": "Restaurante Exemplo",
  "formattedAddress": "Av. Paulista, 1000 - Bela Vista, São Paulo - SP",
  "latitude": -23.565,
  "longitude": -46.651,
  "types": ["restaurant", "food", "point_of_interest"],
  "attributions": []
}
```

---

## 7. Conformidade com a Google Maps Platform: Atribuições, Termos e Privacidade

1. **Metadados de Atribuição (`attributions`):**
   * O backend transporta no DTO `PlaceCandidateResponse` a lista de atribuições (`PlaceAttributionResponse`) com `provider` e `providerUri`.
   * **Obrigação do Cliente (Web / Mobile):** Aplicações clientes que exibirem dados retornados da Google Places API devem renderizar visualmente os créditos de autoria e links aos licenciantes contidos em `attributions`.
2. **Requisito de Terms of Use e Privacy Policy:**
   * De acordo com a [Seção 3.2.2 dos Termos da Google Maps Platform](https://cloud.google.com/maps-platform/terms), qualquer aplicativo consumidor da API deve possuir Termos de Uso e Política de Privacidade publicamente acessíveis.
   * Os termos da aplicação devem notificar explicitamente a incorporação dos [Termos de Serviço Adicionais do Google Maps/Google Earth](https://maps.google.com/help/terms_maps.html) e da [Política de Privacidade do Google](https://www.google.com/policies/privacy/).
   * O detalhamento dos requisitos e plano de ação pré-produção está formalizado em [`docs/legal/terms-and-privacy-requirements.md`](../legal/terms-and-privacy-requirements.md).
3. **Política de Armazenamento Zero-Store:**
   * Conforme as [Políticas da Places API](https://developers.google.com/maps/documentation/places/web-service/policies), é proibido armazenar em cache ou espelhar dados da Google além de um período transitório operacional.
   * O STEP 8 adota **Zero-Store absoluto**: nenhum dado textual, categoria ou coordenada do Google é persistido em banco de dados ou armazenado em cache no Redis/SeaweedFS. O `place_id` é o único dado mantido como identificador externo de vínculo relacional no modelo do Rewit.
