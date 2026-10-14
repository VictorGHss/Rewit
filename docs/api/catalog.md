# Catálogo Persistente: Place, Product e RateableTarget (docs/api/catalog.md)

Este documento especifica a fundação arquitetural de persistência, modelo de domínio e contratos de API RESTful para o núcleo do catálogo do **Rewit** (Step 7).

---

## 1. Arquitetura de Domínio e Persistência

O catálogo fundamenta-se na separação estrita entre alvos avaliáveis (`RateableTarget`), especializações físicas e globais (`Place` e `Product`), identificadores externos (`ProductIdentifier`) e contextualização geográfica (`ProductPresence`).

### 1.1 Raiz Polimórfica Relacional: `RateableTarget` (ADR-009)
* **Conceito**: `RateableTarget` é o nó raiz persistente para qualquer entidade que possa ser alvo de avaliações, métricas e estatísticas agregadas (`RateableTargetStats`).
* **Tipos Suportados**: `PLACE`, `PRODUCT`, `SERVICE`, `EVENT`.
* **Garantia de Integridade**: A tabela `rateable_targets` atua como chave primária compartilhada para as tabelas especializadas (`places.id REFERENCES rateable_targets(id)` e `products.id REFERENCES rateable_targets(id)`).
* **Triggers de Especialização**: Triggers relacionais (`trg_validate_place_specialization` e `trg_validate_product_specialization`) impedem a inserção de alvos em tabelas incompatíveis (ex: um alvo `PRODUCT` em `places`) e a trigger `trg_prevent_rateable_target_type_change` impede alteração de tipo uma vez vinculado.

### 1.2 Local Físico: `Place`
* Representa estabelecimentos físicos e pontos geolocalizados.
* **Geometria PostGIS**: Utiliza o tipo `GEOGRAPHY(Point, 4326)` no PostgreSQL com índice GiST (`idx_places_coordinates`).
* **Busca Geoespacial**: A consulta de proximidade (`findNearby`) utiliza `ST_DWithin` com coordenadas em WGS 84 (`SRID 4326`) e distância esférica em metros.
* **Unicidade**: O campo `slug` é obrigatório, normalizado para minúsculas e protegido pela constraint de banco `uq_places_slug`.
* **Endereço Granular**: Campos `street_number` e `neighborhood` persistidos conforme a migração V2.

### 1.3 Produto Global: `Product`
* Representa itens comercializáveis de forma independente do local onde são vendidos.
* Não armazena preços globais, pois preço e disponibilidade pertencem ao contexto local da presença (`ProductPresence`).
* Herda a identidade relacional de `RateableTarget`.

### 1.4 Identificadores Estruturados: `ProductIdentifier`
* Mapeia códigos de barras e padrões internacionais como `EAN`, `UPC`, `GTIN` e `ISBN` sem concatenações de strings frágeis.
* **Unicidade Estrita**: A constraint composta `uq_product_identifier` no PostgreSQL garante que um mesmo código e tipo não sejam duplicados.
* **Integridade Referencial**: FK obrigatória `product_id REFERENCES products(id) ON DELETE CASCADE`.

### 1.5 Presença de Produto em Local: `ProductPresence`
* Representa a relação n:m contextualizada: `Product ↔ Place`.
* Permite descobrir onde determinado produto foi visto ou está disponível.
* **Unicidade Composta**: A constraint `uq_product_place UNIQUE (product_id, place_id)` impede duplicidade do par no mesmo estabelecimento.
* **Auditoria de Descoberta**: Registra `first_discovered_at`, `last_confirmed_at`, `verification_status` e `reported_by_user_id`.

---

## 2. Endpoints da API REST (`/api/v1`)

Todos os endpoints utilizam JSON (`Content-Type: application/json;charset=UTF-8`), exigem Bearer Token JWT autenticado e seguem a especificação RFC 7807 (`ProblemDetail`) para tratamento de erros.

### 2.1 Criar Local Físico
* **Método**: `POST`
* **Rota**: `/api/v1/places`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)

#### Requisição
```json
{
  "name": "Café do Centro",
  "slug": "cafe-do-centro",
  "category": "CAFE",
  "description": "Cafeteria tradicional com grãos artesanais",
  "addressText": "Rua XV de Novembro",
  "streetNumber": "150",
  "neighborhood": "Centro",
  "city": "Curitiba",
  "state": "PR",
  "country": "BR",
  "latitude": -25.4297,
  "longitude": -49.2719,
  "validationRadiusMeters": 50
}
```

#### Resposta (`201 Created`)
```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "name": "Café do Centro",
  "slug": "cafe-do-centro",
  "category": "CAFE",
  "description": "Cafeteria tradicional com grãos artesanais",
  "addressText": "Rua XV de Novembro",
  "streetNumber": "150",
  "neighborhood": "Centro",
  "city": "Curitiba",
  "state": "PR",
  "country": "BR",
  "latitude": -25.4297,
  "longitude": -49.2719,
  "validationRadiusMeters": 50,
  "origin": "USER",
  "isVerified": false,
  "status": "ACTIVE"
}
```

---

### 2.2 Consultar Local por ID
* **Método**: `GET`
* **Rota**: `/api/v1/places/{id}`
* **Autenticação**: Obrigatória
* **Resposta (`200 OK`)**: Retorna o `PlaceResponse` completo.

#### Visibilidade pública dos detalhes (place e product)
Vale para `GET /api/v1/places/{id}` e `GET /api/v1/products/{id}`, com a mesma regra da busca global (2.9):
* Só alvos com status `ACTIVE` são expostos; a resposta de um alvo `ACTIVE` não muda.
* Qualquer outro status do catálogo (places `INACTIVE`/`CLOSED`, products `INACTIVE`/`DISCONTINUED`) responde exatamente como um id inexistente: `404` com `PLACE_NOT_FOUND` ou `PRODUCT_NOT_FOUND`. O corpo não informa status, nome, slug nem que o alvo existiu.
* O registro não é alterado: a regra vale só para a leitura pública.
* As estatísticas e as avaliações do alvo seguem a mesma regra (`docs/api/reviews.md`, seções 3.3 e 3.4).
* Também seguem a mesma regra os identificadores do produto (2.10), o lookup por código (2.11) e os produtos de um local (2.12).

#### Composição da tela de produto
O detalhe de produto é composto no cliente, sem endpoint agregado: `GET /api/v1/products/{id}` (dados), `GET /api/v1/products/{id}/identifiers` (códigos), `GET /api/v1/targets/{id}/stats` (média e total) e `GET /api/v1/targets/{id}/reviews` (avaliações paginadas, com helpful, `verifiedOnly`, visibilidade e anonimização de contas excluídas). O `id` do produto é o próprio `RateableTarget`.

---

### 2.3 Criar Produto Global
* **Método**: `POST`
* **Rota**: `/api/v1/products`
* **Autenticação**: Obrigatória

#### Requisição
```json
{
  "name": "Café em Grãos Especial 250g",
  "brand": "Grão Real",
  "model": "Catuaí Vermelho",
  "description": "Torra média, notas florais",
  "category": "ALIMENTOS",
  "imageUrl": "https://storage.rewit.app/products/cafe.png"
}
```

#### Resposta (`201 Created`)
```json
{
  "id": "7ca85f64-5717-4562-b3fc-2c963f66afb2",
  "name": "Café em Grãos Especial 250g",
  "brand": "Grão Real",
  "model": "Catuaí Vermelho",
  "description": "Torra média, notas florais",
  "category": "ALIMENTOS",
  "imageUrl": "https://storage.rewit.app/products/cafe.png",
  "status": "ACTIVE"
}
```

---

### 2.4 Adicionar Código/Identificador ao Produto
* **Método**: `POST`
* **Rota**: `/api/v1/products/{id}/identifiers`
* **Autenticação**: Obrigatória

#### Requisição
```json
{
  "identifierType": "EAN",
  "identifierValue": "7891000100100"
}
```

#### Resposta (`201 Created`)
```json
{
  "id": "8ca85f64-5717-4562-b3fc-2c963f66afc3",
  "productId": "7ca85f64-5717-4562-b3fc-2c963f66afb2",
  "identifierType": "EAN",
  "identifierValue": "7891000100100"
}
```

---

### 2.5 Associar Presença de Produto ao Local
* **Método**: `POST`
* **Rota**: `/api/v1/products/{id}/presence`
* **Autenticação**: Obrigatória (o `reportedByUserId` é associado automaticamente pelo JWT)

#### Requisição
```json
{
  "placeId": "3fa85f64-5717-4562-b3fc-2c963f66afa6"
}
```

#### Resposta (`201 Created`)
```json
{
  "id": "9ca85f64-5717-4562-b3fc-2c963f66afd4",
  "productId": "7ca85f64-5717-4562-b3fc-2c963f66afb2",
  "placeId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "reportedByUserId": "1ca85f64-5717-4562-b3fc-2c963f66af01",
  "verificationStatus": "UNCONFIRMED",
  "status": "AVAILABLE"
}
```

* Se a presença do produto no local já existe, a resposta devolve a presença existente, com `reportedByUserId` de quem a relatou primeiro.
* **Privacidade**: se essa conta foi excluída (`DELETED`), `reportedByUserId` volta `null`; o UUID da conta excluída nunca aparece na resposta. Para contas ativas, o comportamento não muda. O dado gravado não é alterado por esta leitura; a minimização é feita pelo purge da conta.

---

### 2.6 Adoção de Local no Catálogo (POST /api/v1/places/adopt)
* **Método**: `POST`
* **Rota**: `/api/v1/places/adopt`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)
* **Propósito**: Permite que usuários autenticados adotem/cadastrem um local no catálogo soberano Rewit, vinculando opcionalmente uma referência externa (ex: Google Place ID).
* **Zero-Store**: Não realiza chamadas à Google Places API (ex: `PlaceDiscoveryPort`, `getPlaceDetails`, etc.) nem copia conteúdo de terceiros. Apenas persiste os dados fornecidos pelo cliente como soberanos do Rewit.
* **Segurança e Identidade**:
  * A identidade do criador (`reportedByUserId`) é extraída exclusivamente do contexto de segurança JWT validado.
  * Campos como `userId`, `ownerId`, `createdBy`, `origin`, `status`, `metadataJson` ou payloads Google brutos não são aceitos e são ignorados/rejeitados pelo backend.
  * O local é sempre cadastrado com `origin = USER` e `status = ACTIVE`.

#### Requisição
```json
{
  "name": "Padaria Central",
  "slug": null,
  "address": "Rua Exemplo, 123",
  "latitude": -25.0,
  "longitude": -50.0,
  "validationRadiusMeters": 100.0,
  "externalReference": {
    "provider": "GOOGLE",
    "externalId": "ChIJd8BlQ2BZwokRAFUEcm_qrcA"
  }
}
```

#### Respostas
* **`201 Created`**: Para nova criação física de local.
  * Header `Location: /api/v1/places/{id}`
  * Body: `PlaceResponse` representando a entidade persistida.
* **`200 OK`**: Para adoção idempotente de referência externa já existente (`provider`, `externalId`).
  * Sem header `Location`.
  * Retorna o local previamente existente de forma imutável (não altera nome, endereço, coordenadas, slug ou status).

```json
{
  "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "name": "Padaria Central",
  "slug": "padaria-central",
  "category": "OUTROS",
  "description": null,
  "addressText": "Rua Exemplo, 123",
  "streetNumber": null,
  "neighborhood": null,
  "city": null,
  "state": null,
  "country": null,
  "latitude": -25.0,
  "longitude": -50.0,
  "validationRadiusMeters": 100.0,
  "origin": "USER",
  "isVerified": false,
  "status": "ACTIVE"
}
```

---

### 2.7 Consulta de Local por Referência Externa (GET /api/v1/places/external/{provider}/{externalId})
* **Método**: `GET`
* **Rota**: `/api/v1/places/external/{provider}/{externalId}`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)
* **Propósito**: Localiza o `Place` Rewit soberano associado a uma identidade externa previamente adotada.
* **Tratamento de `externalId`**: Tratado estritamente como **token opaco** (sem lowercase, uppercase, trim interno ou alteração semântica).
* **Respostas**:
  * **`200 OK`**: Retorna o `PlaceResponse` associado.
  * **`404 Not Found`**: Caso a referência externa não exista no catálogo, retorna Problem Details RFC 7807 com código de erro `PLACE_EXTERNAL_REFERENCE_NOT_FOUND`.

---

### 2.8 Descoberta de Locais Próximos por Proximidade Geográfica (GET /api/v1/places/nearby)
* **Método**: `GET`
* **Rota**: `/api/v1/places/nearby`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`), seguindo o padrão unificado de leitura do catálogo.
* **Propósito**: Permite descobrir estabelecimentos físicos cadastrados no catálogo Rewit por proximidade geoespacial utilizando PostGIS.
* **Zero-Store**: Consulta estritamente os dados persistidos no PostgreSQL do Rewit. Não realiza nenhuma chamada a APIs externas (Google Places, etc.).
* **Privacidade e Segurança**: As coordenadas fornecidas pelo cliente atuam exclusivamente como ponto de consulta transitório em memória. O backend não persiste localização do usuário, não mantém histórico de consultas e não registra coordenadas em logs de aplicação.

#### Query Parameters
| Parâmetro | Tipo | Obrigatório | Faixa Permitida | Default | Descrição |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `latitude` | `Double` | Sim | `[-90.0, 90.0]` | - | Latitude central WGS 84 (graus decimais). Rejeita `null`, `NaN` e `Infinity`. |
| `longitude` | `Double` | Sim | `[-180.0, 180.0]` | - | Longitude central WGS 84 (graus decimais). Rejeita `null`, `NaN` e `Infinity`. |
| `radiusMeters` | `Double` | Sim | `(0, 50000.0]` | - | Raio de busca esférico em metros (mínimo > 0, teto máximo de 50 km). |
| `limit` | `Integer` | Não | `[1, 100]` | `20` | Quantidade máxima de resultados a retornar. |

#### Implementação Geoespacial PostGIS
* **Índice GiST**: Utiliza o índice espacial nativo `idx_places_coordinates ON places USING GIST (coordinates)`.
* **Filtragem e Distância**: A consulta executa `ST_DWithin` com coordenadas em `GEOGRAPHY(Point, 4326)` e calcula a distância exata em metros via `ST_Distance`.
* **Ordenação Determinística**: Os resultados são ordenados por proximidade crescente (`ORDER BY ST_Distance(...) ASC, p.id ASC`). Havendo empate na distância, o identificador do local (`id`) assegura ordenação determinística e estável.
* **Locais Ativos e Sem Coordenadas**: Somente locais com `status = 'ACTIVE'` e coordenadas geográficas válidas participam da busca. Locais inativos ou sem coordenadas são filtrados diretamente no banco de dados.

#### Resposta de Sucesso (`200 OK`)
Mesmo quando nenhum local for encontrado dentro do raio solicitado, a API retorna `200 OK` com `items = []`.

```json
{
  "items": [
    {
      "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "name": "Padaria Central",
      "slug": "padaria-central",
      "category": "OUTROS",
      "description": null,
      "addressText": "Rua Exemplo, 123",
      "streetNumber": "123",
      "neighborhood": "Centro",
      "city": "Curitiba",
      "state": "PR",
      "country": "BR",
      "latitude": -25.4297,
      "longitude": -49.2719,
      "validationRadiusMeters": 50,
      "origin": "USER",
      "isVerified": false,
      "status": "ACTIVE",
      "distanceMeters": 143.72
    }
  ],
  "limit": 20
}
```

### 2.9 Busca Global de Locais e Produtos (GET /api/v1/search)
* **Método**: `GET`
* **Rota**: `/api/v1/search`
* **Autenticação**: Obrigatória

#### Query Parameters
| Parâmetro | Obrigatório | Padrão | Regra |
|---|---|---|---|
| `q` | sim | — | Termo da busca; não pode ser vazio nem só espaços (espaços nas pontas são ignorados) |
| `page` | não | `0` | Página, a partir de 0; negativa é recusada |
| `size` | não | `20` | Itens por página, de 1 a 50 |

#### Escopo
* Locais (`PLACE`): casa por nome, categoria ou cidade.
* Produtos (`PRODUCT`): casa por nome ou categoria. Marca e código de barras não fazem parte da busca.
* Só itens com status `ACTIVE`.

#### Correspondência
* O termo é procurado como trecho do texto (contém), sem diferença de maiúsculas/minúsculas e sem acentos: `cafe` encontra `Café`, `sao` encontra `São`, `acai` encontra `Açaí` (e vice-versa).
* `%`, `_` e `\` são caracteres literais, não curingas: `q=%` encontra apenas textos que contêm `%`, nunca o catálogo inteiro; `a_b` não encontra `axb`.
* A normalização (minúsculas e `unaccent`) é a função `rewit_search_normalize` (migration V22). A mesma expressão é indexada com trigram (`idx_places_search_*`, `idx_products_search_*`), e a busca usa esses índices em vez de varrer as tabelas.

#### Ordenação e paginação
* Ordem: relevância decrescente (maior similaridade trigram do termo com o nome ou a categoria), depois nome crescente, depois `id` crescente.
* A ordem é total: com o catálogo inalterado, a mesma consulta sempre devolve a mesma ordem, e as páginas não repetem nem perdem itens.
* `totalElements` e `totalPages` contam todos os itens que casam com o termo.

#### Rate limit
* Por usuário autenticado, no Redis compartilhado entre instâncias (ADR-013). Padrão: **60 buscas por janela deslizante de 60 segundos**. Configurável com `RATE_LIMIT_SEARCH_LIMIT` e `RATE_LIMIT_SEARCH_WINDOW` (`rewit.rate-limit.query.search.*`).
* Acima do limite: `429 Too Many Requests`, RFC 7807, `code` `RATE_LIMIT_EXCEEDED` e cabeçalho `Retry-After` (segundos).
* Requisições recusadas por validação (`400`) não consomem o limite.
* O lookup de produto por código (2.11) consome o mesmo limite.

#### Resposta de Sucesso (`200 OK`)
```json
{
  "content": [
    {
      "id": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "name": "Café Central",
      "slug": "cafe-central-curitiba",
      "category": "CAFE",
      "targetType": "PLACE",
      "status": "ACTIVE"
    },
    {
      "id": "7ca85f64-5717-4562-b3fc-2c963f66afb2",
      "name": "Café em Grãos",
      "slug": null,
      "category": "BEBIDA",
      "targetType": "PRODUCT",
      "status": "ACTIVE"
    }
  ],
  "pageNumber": 0,
  "pageSize": 20,
  "totalElements": 2,
  "totalPages": 1,
  "isLast": true
}
```
* `slug` é sempre `null` para produtos.

### 2.10 Identificadores Públicos do Produto (GET /api/v1/products/{id}/identifiers)
* **Método**: `GET`
* **Rota**: `/api/v1/products/{id}/identifiers`
* **Autenticação**: Obrigatória
* **Produto indisponível**: produto fora de `ACTIVE` ou inexistente responde `404` `PRODUCT_NOT_FOUND`, sem listar códigos.
* **Tipos expostos**: só códigos comerciais padronizados: `EAN`, `UPC`, `GTIN` e `ISBN`. O tipo gravado é texto livre (2.4); identificadores de qualquer outro tipo nunca aparecem em leituras públicas.
* **Ordem**: por tipo e depois por valor. Sem paginação: a lista é a dos códigos do produto.
* Cada item traz só tipo e valor: sem id da linha, id do produto, datas ou quem cadastrou.

#### Resposta (`200 OK`)
```json
{
  "identifiers": [
    { "identifierType": "EAN", "identifierValue": "7891234567890" },
    { "identifierType": "GTIN", "identifierValue": "07891234567890" }
  ]
}
```
Produto sem códigos públicos: `{"identifiers": []}`.

---

### 2.11 Produto por Código (GET /api/v1/products/identifiers/{type}/{value})
* **Método**: `GET`
* **Rota**: `/api/v1/products/identifiers/{type}/{value}` (mesmo formato de `GET /api/v1/places/external/{provider}/{externalId}`)
* **Autenticação**: Obrigatória
* **Chave**: o código é único por par (tipo, valor). O mesmo valor pode existir em tipos diferentes, por isso o tipo é obrigatório e não se presume que todo código seja EAN.
* **Tipos suportados**: `EAN`, `UPC`, `GTIN`, `ISBN`. Outro tipo: `400` `INVALID_IDENTIFIER_TYPE`.
* **Normalização**: a mesma da gravação (2.4). O tipo é comparado sem diferença de maiúsculas e minúsculas; o valor, sem os espaços das pontas. O valor é comparado exatamente como foi cadastrado (hífens contam).
* **Validação do valor**: de 1 a 128 caracteres (o tamanho da coluna), só letras, dígitos e hífen. Fora disso: `400` `INVALID_IDENTIFIER_VALUE`.
* **Resposta (`200 OK`)**: o `ProductResponse` do detalhe (2.3).
* **Não encontrado**: código inexistente e produto fora de `ACTIVE` respondem o mesmo `404` `PRODUCT_NOT_FOUND`. Um código de produto inativo ou descontinuado não é revelado.
* **Rate limit**: consome o limite de buscas do usuário (2.9, 60 por minuto por padrão), compartilhado com `GET /api/v1/search`, contra a enumeração de códigos. Acima do limite: `429` `RATE_LIMIT_EXCEEDED` com `Retry-After`. Requisição inválida (`400`) não consome o limite.

---

### 2.12 Produtos de um Local (GET /api/v1/places/{id}/products)
* **Método**: `GET`
* **Rota**: `/api/v1/places/{id}/products`
* **Autenticação**: Obrigatória
* **Significado**: produtos com presença registrada no local (2.5).
* **Local indisponível**: local fora de `ACTIVE` ou inexistente responde `404` `PLACE_NOT_FOUND`, sem listar produtos.
* **Itens**: só produtos `ACTIVE`, no formato `ProductResponse` do detalhe (2.3).
* **Privacidade**: nenhum dado da presença é exposto: nem quem a relatou (`reportedByUserId`), nem o id, as datas ou o status da presença.
* **Ordem**: nome crescente e depois `id` crescente. A ordem é total: as páginas não repetem nem perdem itens.

#### Query Parameters
| Parâmetro | Padrão | Regra |
|---|---|---|
| `page` | `0` | A partir de 0; negativa: `400` `INVALID_PAGE` |
| `size` | `20` | De 1 a 50; fora disso: `400` `INVALID_PAGE_SIZE` |

#### Resposta (`200 OK`)
```json
{
  "content": [
    {
      "id": "7ca85f64-5717-4562-b3fc-2c963f66afb2",
      "name": "Café em Grãos",
      "brand": "Marca",
      "model": null,
      "description": null,
      "category": "BEBIDA",
      "imageUrl": null,
      "status": "ACTIVE"
    }
  ],
  "pageNumber": 0,
  "pageSize": 20,
  "totalElements": 1,
  "totalPages": 1,
  "isLast": true
}
```

---

### 2.13 Consulta de Horários de Funcionamento (GET /api/v1/places/{id}/opening-hours)
* **Método**: `GET`
* **Rota**: `/api/v1/places/{id}/opening-hours`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)
* **Propósito**: Retorna a grade semanal recorrente dos horários de funcionamento do estabelecimento físico.
* **Local indisponível**: Local fora de `ACTIVE` ou inexistente responde `404 PLACE_NOT_FOUND` (indistinguível).
* **Estrutura de Resposta e Elegibilidade Comercial (Decisão F2)**:
  * Se o local ainda não possui horários configurados: responde `200 OK` com `configured = false` e `days = []`.
  * **Elegibilidade da representação e ocultação protetiva**: Se o local possui horários gravados, mas a representação comercial titular deixar temporariamente de ser elegível (por exemplo: conta comercial pendente/não aprovada, usuário administrador suspenso, desativado ou inoperante, ou estabelecimento não mais vinculado/reivindicado por essa conta comercial), os horários ficam automaticamente ocultos do público. O endpoint responde `200 OK` com `configured = false` e `days = []`, sendo indistinguível de um local sem horários cadastrados e não revelando se há registros persistidos de uma representação inelegível.
  * **Reversibilidade sem reescrita**: Se a mesma representação comercial voltar a ser elegível e continuar sendo a responsável pelo estabelecimento, a configuração previamente armazenada volta a ser visível automaticamente, sem exigir nova gravação e sem modificar a auditoria ou os timestamps (`created_at`/`updated_at`).
  * **Transferência de reivindicação**: Se o estabelecimento passar a ser reivindicado por outra conta comercial (titular B), os horários armazenados em nome da conta anterior (titular A) permanecem ocultos (`configured = false`, `days = []`) até que a conta B configure sua própria grade via `PUT /api/v1/places/{id}/opening-hours`. A simples perda de elegibilidade ou desvinculação da conta anterior nunca apaga registros do banco de dados.
  * **Horários visíveis**: Se o local possui horários configurados e a representação comercial responsável está plenamente elegível, responde `200 OK` com `configured = true` e a lista completa dos 7 dias da semana ordenados de 1 (Segunda-feira) a 7 (Domingo). Dias sem expediente são retornados com `closed = true` e `intervals = []`.

#### Resposta (`200 OK`) - Horários Configurados
```json
{
  "placeId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "configured": true,
  "days": [
    {
      "dayOfWeek": 1,
      "dayName": "MONDAY",
      "closed": false,
      "intervals": [
        { "openTime": "08:00", "closeTime": "12:00" },
        { "openTime": "14:00", "closeTime": "18:00" }
      ]
    },
    {
      "dayOfWeek": 2,
      "dayName": "TUESDAY",
      "closed": false,
      "intervals": [
        { "openTime": "08:00", "closeTime": "18:00" }
      ]
    },
    {
      "dayOfWeek": 7,
      "dayName": "SUNDAY",
      "closed": true,
      "intervals": []
    }
  ]
}
```

#### Resposta (`200 OK`) - Sem Horários Cadastrados ou Representação Inelegível
```json
{
  "placeId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "configured": false,
  "days": []
}
```

---

### 2.14 Configuração de Horários de Estabelecimento Reivindicado (PUT /api/v1/places/{id}/opening-hours)
* **Método**: `PUT`
* **Rota**: `/api/v1/places/{id}/opening-hours`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)
* **Propósito**: Permite que o administrador de uma conta comercial aprovada configure ou atualize os horários de funcionamento semanais do estabelecimento que a empresa reivindica (F2.1).
* **Autorização e Regras de Negócio**:
  * O autor autenticado precisa ter conta pessoal operacional (`ACTIVE`); contas suspensas ou excluídas recebem `401 ACCOUNT_DISABLED`.
  * A conta comercial (`businessAccountId`) precisa existir e ser administrada pelo autor (`404 BUSINESS_ACCOUNT_NOT_FOUND` se inexistente ou de outro usuário).
  * A conta comercial precisa estar aprovada (`409 BUSINESS_ACCOUNT_NOT_APPROVED` se `PENDING`).
  * O estabelecimento precisa estar ativo e atualmente reivindicado pela conta comercial informada (`403 BUSINESS_NOT_RESPONSIBLE` se de outra empresa ou sem reivindicação; `404 PLACE_NOT_FOUND` se inativo ou inexistente).
  * Concorrência de atualização é serializada no banco de dados via `SELECT ... FOR UPDATE` no local.
* **Validação dos Horários e Contrato Temporal**:
  * Formato dos horários: os horários utilizam estritamente o formato `HH:mm` (ex.: `"08:00"`, `"18:30"`).
  * Escopo intradiário: cada intervalo começa e termina estritamente no mesmo dia civil/comercial.
  * Fechamento posterior: o horário de fechamento (`closeTime`) precisa ser estritamente posterior ao horário de abertura (`openTime`) (`400 INVALID_OPENING_HOURS_INTERVAL`).
  * Restrição de 24:00: `"24:00"` não é um valor aceito pelo padrão temporal (`LocalTime` opera estritamente de `00:00` a `23:59`).
  * Sem transposição de meia-noite: intervalos que atravessam a meia-noite (como `"22:00"` até `"02:00"`) e horários de fechamento exatamente às `"00:00"` não são suportados nesta versão (`00:00` é cronologicamente anterior a qualquer horário diurno, violando a regra `openTime < closeTime`). Estabelecimentos com funcionamento contínuo ou noturno devem ajustar os intervalos aos limites do mesmo dia ou aguardar a evolução futura da plataforma para suporte a intervalos interdiários.
  * Formato de envio e divisões: o payload da requisição deve dividir os intervalos estritamente conforme as regras documentadas, sem inventar representações artificiais para atravessar a meia-noite.
  * Validação de elementos: tanto a lista de dias (`days`) quanto a lista de intervalos (`intervals`) rejeitam elementos nulos (`days: [null]` ou `intervals: [null]`), respondendo `400 Bad Request` com código `VALIDATION_ERROR` no padrão RFC 7807, preservando integralmente o estado persistido no banco de dados.
  * `dayOfWeek`: inteiro entre 1 (Segunda-feira) e 7 (Domingo). Rejeita duplicatas no mesmo payload (`400 DUPLICATE_DAY_OF_WEEK`).
  * Dias omitidos na requisição são automaticamente interpretados como fechados (`closed = true`).
  * Dias fechados (`closed = true`): rejeita qualquer intervalo enviado (`400 INVALID_OPENING_HOURS`).
  * Dias abertos (`closed = false`): exige pelo menos um intervalo válido (`400 INVALID_OPENING_HOURS`).
  * Múltiplos intervalos no mesmo dia são permitidos, mas não podem se sobrepor nem colidir (`400 OPENING_HOURS_OVERLAPPING_INTERVALS`).
* **Substituição Atômica e Transferência**: Os horários anteriores do local são substituídos integralmente em transação única. Caso o estabelecimento tenha sido transferido para uma nova conta comercial, ao salvar com sucesso via `PUT`, a configuração anterior é substituída atomicamente e a nova configuração passa a ser associada à nova conta comercial e ao seu administrador. A simples perda de elegibilidade anterior nunca apaga registros do banco de dados.

#### Requisição
```json
{
  "businessAccountId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "days": [
    {
      "dayOfWeek": 1,
      "closed": false,
      "intervals": [
        { "openTime": "08:00", "closeTime": "12:00" },
        { "openTime": "14:00", "closeTime": "18:00" }
      ]
    },
    {
      "dayOfWeek": 2,
      "closed": false,
      "intervals": [
        { "openTime": "08:00", "closeTime": "18:00" }
      ]
    },
    {
      "dayOfWeek": 7,
      "closed": true,
      "intervals": []
    }
  ]
}
```

#### Resposta (`200 OK`)
Retorna a grade semanal completa de 7 dias no formato de `PlaceOpeningHoursResponse`.

---

### 2.15 Catálogo de Comodidades (GET /api/v1/places/amenity-options)
* **Método**: `GET`
* **Rota**: `/api/v1/places/amenity-options`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)
* **Propósito**: Lista as comodidades aceitas por `PUT /api/v1/places/{id}/amenities` (F2.2). O catálogo é controlado: não há texto livre, e o código é estável na API e no banco (`CHECK chk_place_amenity_code`, V27).
* **Escopo**: Apenas características físicas e factuais do local. Ficam fora alegações subjetivas ("ambiente agradável"), preços, horários (ver 2.13/2.14), delivery, disponibilidade de produtos e serviços avaliáveis: atendimento, delivery e drive-thru continuam em `services` (`PlaceService`, um `RateableTarget`, ADR-009), que não é reutilizado aqui.
* **Ordem**: A resposta segue a ordem do catálogo, que também é a ordem usada na leitura das comodidades de um local.

| Código | Rótulo |
| :--- | :--- |
| `WIFI` | Wi-Fi para clientes |
| `PARKING` | Estacionamento |
| `WHEELCHAIR_ACCESSIBLE` | Acesso para cadeira de rodas |
| `ACCESSIBLE_RESTROOM` | Banheiro acessível |
| `CUSTOMER_RESTROOM` | Banheiro para clientes |
| `AIR_CONDITIONING` | Ar-condicionado |
| `OUTDOOR_SEATING` | Área externa |
| `PET_FRIENDLY` | Aceita animais de estimação |

Incluir um código exige nova constante em `PlaceAmenity` e migração que amplie o `CHECK`; remover exige antes tratar as seleções gravadas. O teste de integração compara o enum com o `CHECK`.

#### Resposta (`200 OK`)
```json
{
  "options": [
    { "code": "WIFI", "label": "Wi-Fi para clientes" },
    { "code": "PARKING", "label": "Estacionamento" }
  ]
}
```
(Exemplo truncado: a resposta traz os oito códigos da tabela.)

---

### 2.16 Consulta de Comodidades (GET /api/v1/places/{id}/amenities)
* **Método**: `GET`
* **Rota**: `/api/v1/places/{id}/amenities`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)
* **Propósito**: Retorna as comodidades declaradas pela empresa titular do estabelecimento (F2.2).
* **Local indisponível**: Local fora de `ACTIVE` ou inexistente responde `404 PLACE_NOT_FOUND` (indistinguível).
* **Estados da resposta** (mesma regra de visibilidade da Decisão F2 aplicada aos horários em 2.13):
  * **Nunca configurado**: `configured = false`, `amenities = []`.
  * **Configurado sem comodidades**: `configured = true`, `amenities = []`. A empresa declarou que o local não oferece nenhuma comodidade do catálogo; é diferente de nunca ter configurado.
  * **Configurado com comodidades**: `configured = true` e a lista em ordem de catálogo, cada item com `code` e `label`.
  * **Representação inelegível**: se a conta comercial que gravou deixar de ser elegível (conta não aprovada, administrador inoperante, local desvinculado ou reivindicado por outra conta), a resposta é igual à de nunca configurado. Não expõe seleção, conta comercial, autor nem datas.
  * **Reversibilidade sem reescrita**: a mesma representação, de novo elegível, volta a ter a configuração visível sem nova gravação e sem alterar `created_at`/`updated_at`.
  * **Transferência**: a nova titular não herda a configuração da anterior; a leitura fica como nunca configurada até a nova titular gravar a sua via `PUT`. Se o local voltar à titular anterior antes de a nova gravar, a configuração antiga (ainda dela) volta a aparecer; se a nova já tiver gravado, a configuração passa a ser da nova e fica oculta para a anterior até que esta grave de novo.
  * Inelegibilidade, desvinculação ou transferência nunca apagam registros do banco.

#### Resposta (`200 OK`) - Comodidades Configuradas
```json
{
  "placeId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "configured": true,
  "amenities": [
    { "code": "WIFI", "label": "Wi-Fi para clientes" },
    { "code": "PET_FRIENDLY", "label": "Aceita animais de estimação" }
  ]
}
```

#### Resposta (`200 OK`) - Sem Configuração ou Representação Inelegível
```json
{
  "placeId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "configured": false,
  "amenities": []
}
```

---

### 2.17 Configuração de Comodidades de Estabelecimento Reivindicado (PUT /api/v1/places/{id}/amenities)
* **Método**: `PUT`
* **Rota**: `/api/v1/places/{id}/amenities`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)
* **Propósito**: Substitui integralmente a seleção de comodidades do estabelecimento reivindicado pela conta comercial (F2.2). Códigos omitidos deixam de estar selecionados; lista vazia declara que o local não oferece nenhuma comodidade.
* **Validação do payload** (antes de qualquer leitura, trava ou escrita; erro não altera nada):
  * `businessAccountId` obrigatório (`400 VALIDATION_ERROR`, `fieldErrors.businessAccountId`).
  * `amenities` obrigatório, no máximo 50 elementos (`400 VALIDATION_ERROR`, `fieldErrors.amenities`). Elemento nulo responde `400 VALIDATION_ERROR` com `fieldErrors["amenities[i]"]`.
  * Código fora do catálogo, inclusive com caixa ou espaços diferentes (`"wifi"`, `" WIFI"`): `400 INVALID_AMENITY_CODE`.
  * Código repetido na mesma requisição: `400 DUPLICATE_AMENITY`.
  * JSON malformado ou `amenities` que não seja lista: `400 MALFORMED_REQUEST`.
* **Autorização** (Política B, mesma ordem de 2.14):
  * Autor com conta pessoal operacional (`401 ACCOUNT_DISABLED`).
  * Conta comercial existente e administrada pelo autor (`404 BUSINESS_ACCOUNT_NOT_FOUND` se inexistente ou de outro usuário) e aprovada (`409 BUSINESS_ACCOUNT_NOT_APPROVED` se `PENDING` ou `REJECTED`).
  * Local ativo (`404 PLACE_NOT_FOUND`) e reivindicado agora pela conta informada (`403 BUSINESS_NOT_RESPONSIBLE` se de outra conta ou sem titular).
* **Concorrência**: A linha do local é travada (`SELECT ... FOR NO KEY UPDATE`) antes de conferir a representação e gravar. Gravações simultâneas no mesmo local, e com reivindicações ou transferências que travam o local, são serializadas; o resultado final é sempre uma seleção inteira, nunca a mistura de duas.
* **Auditoria**: A configuração guarda a conta comercial e o usuário da última gravação (`business_account_id`, `updated_by_user_id`), `created_at` da primeira gravação e `updated_at` da última. Esses campos não aparecem na API.

#### Requisição
```json
{
  "businessAccountId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "amenities": ["WIFI", "PET_FRIENDLY"]
}
```

#### Resposta (`200 OK`)
Mesmo formato de 2.16, com `configured = true` e as comodidades gravadas em ordem de catálogo.

#### Resposta (`400 Bad Request`) - Código fora do catálogo
```json
{
  "type": "https://api.rewit.app/errors/invalid_amenity_code",
  "title": "Regra de Negócio Violada",
  "status": 400,
  "detail": "Comodidade desconhecida: use um código do catálogo de comodidades",
  "instance": "/api/v1/places/3fa85f64-5717-4562-b3fc-2c963f66afa6/amenities",
  "code": "INVALID_AMENITY_CODE",
  "timestamp": "2026-10-14T12:00:00Z"
}
```

---

### 2.18 Ofertas Comerciais de um Local (GET /api/v1/places/{id}/offers)
* **Método**: `GET`
* **Rota**: `/api/v1/places/{id}/offers?page=0&size=20`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)
* **Propósito**: Vitrine oficial do estabelecimento (F2.3): produtos globais que a empresa titular oferta no local, com disponibilidade e preço opcional. É separada da presença comunitária (`POST /products/{id}/presence` e `GET /places/{id}/products`, 2.5 e 2.12), que não muda.
* **Paginação**: `page` a partir de 0 (`400 INVALID_PAGE` se negativa) e `size` de 1 a 50, padrão 20 (`400 INVALID_PAGE_SIZE`). Ordenação por nome do produto e id.
* **Local indisponível**: Local fora de `ACTIVE` ou inexistente responde `404 PLACE_NOT_FOUND` (indistinguível).
* **O que aparece**:
  * Só ofertas da conta comercial titular atual do local, com produto global `ACTIVE`. Produto desativado some da vitrine sem a oferta ser apagada e volta se for reativado.
  * Titular inelegível (conta pendente ou rejeitada, administrador inoperante) ou local sem titular responde página vazia (`content = []`, `totalElements = 0`), sem preço, disponibilidade ou identificadores. O banco não muda; a mesma conta, elegível de novo, volta a ter as ofertas visíveis.
  * **Troca de titular (decisão T2)**: quando `places.claimed_by_business_id` muda (outra conta, ou o local fica sem titular), as ofertas da titular anterior são apagadas na mesma transação, por um trigger da V28. Uma empresa que volte a ser titular não recupera ofertas, preços nem disponibilidade antigos: precisa cadastrá-los de novo. Hoje não existe API de transferência; a regra vale para qualquer caminho que altere o vínculo. A decisão vale só para ofertas: horários (2.13) e comodidades (2.16) seguem a regra F2 de ocultação e não são apagados.
* **Campos**: `product` (resumo do produto global: `id`, `name`, `brand`, `model`, `category`, `imageUrl`), `availability` (`AVAILABLE` ou `OUT_OF_STOCK`), `price` (número com duas casas ou `null` quando não divulgado; `0.00` é gratuito) e `currency` (sempre `BRL`). Não expõe conta comercial, autor nem datas.

#### Resposta (`200 OK`)
```json
{
  "content": [
    {
      "product": {
        "id": "8d0f2b1e-4a55-4c3f-9a8e-2f6b7c1d9e10",
        "name": "Café Torrado 500g",
        "brand": "Marca",
        "model": null,
        "category": "BEBIDA",
        "imageUrl": null
      },
      "availability": "AVAILABLE",
      "price": 12.90,
      "currency": "BRL"
    }
  ],
  "pageNumber": 0,
  "pageSize": 20,
  "totalElements": 1,
  "totalPages": 1,
  "isLast": true
}
```

---

### 2.19 Criação ou Atualização de Oferta (PUT /api/v1/places/{id}/offers/{productId})
* **Método**: `PUT`
* **Rota**: `/api/v1/places/{id}/offers/{productId}`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)
* **Propósito**: Cria a oferta do produto global no local ou substitui os termos da oferta existente. Um produto por operação; não substitui a lista inteira. O produto global e a presença comunitária nunca são alterados.
* **Idempotência**: repetir o mesmo estado (titular, disponibilidade, preço pelo valor e moeda) não regrava a linha nem muda `updated_at`. Mudança de termos preserva o id e a data de criação da oferta e registra o novo autor.
* **Validação do payload** (antes de qualquer leitura, trava ou escrita; erro não altera nada):
  * `businessAccountId` e `availability` obrigatórios (`400 VALIDATION_ERROR` com `fieldErrors`).
  * `availability` fora de `AVAILABLE`/`OUT_OF_STOCK`, inclusive em minúsculas: `400 INVALID_OFFER_AVAILABILITY`.
  * `price` opcional: omitido ou `null` = não divulgado (limpa o preço anterior); `0` = gratuito; negativo, mais de duas casas decimais ou acima de `9999999999.99`: `400 INVALID_OFFER_PRICE`.
  * `currency` opcional: ausente = `BRL`; qualquer outro valor (inclusive `"brl"`): `400 UNSUPPORTED_CURRENCY`.
  * JSON malformado ou `price` não numérico: `400 MALFORMED_REQUEST`.
* **Autorização** (Política B, mesma ordem de 2.14 e 2.17):
  * Autor com conta pessoal operacional (`401 ACCOUNT_DISABLED`).
  * Conta comercial existente e administrada pelo autor (`404 BUSINESS_ACCOUNT_NOT_FOUND`) e aprovada (`409 BUSINESS_ACCOUNT_NOT_APPROVED` se `PENDING` ou `REJECTED`).
  * Local ativo (`404 PLACE_NOT_FOUND`) e reivindicado agora pela conta (`403 BUSINESS_NOT_RESPONSIBLE`).
  * Produto global existente e `ACTIVE` (`404 PRODUCT_NOT_FOUND` para inexistente, `INACTIVE` ou `DISCONTINUED`).
* **Concorrência**: A linha do local é travada (`SELECT ... FOR NO KEY UPDATE`) antes de conferir a representação e gravar. Gravações simultâneas no mesmo local são serializadas e terminam com uma oferta inteira. Uma troca de titular concorrente também espera ou é esperada: se vier antes, o PUT recebe `403`; se vier depois, apaga também a oferta recém-gravada.

#### Requisição
```json
{
  "businessAccountId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "availability": "AVAILABLE",
  "price": 12.90,
  "currency": "BRL"
}
```

#### Resposta (`200 OK`)
Um item no formato de 2.18 (`product`, `availability`, `price`, `currency`), tanto na criação quanto na atualização.

---

### 2.20 Retirada de Oferta (DELETE /api/v1/places/{id}/offers/{productId})
* **Método**: `DELETE`
* **Rota**: `/api/v1/places/{id}/offers/{productId}?businessAccountId={uuid}`
* **Autenticação**: Obrigatória (`Authorization: Bearer <token>`)
* **Propósito**: Retira a oferta individual da vitrine. Não altera o produto global nem a presença comunitária. Funciona também para produto global já desativado.
* **Conta comercial**: `businessAccountId` é obrigatório na query (`400 MISSING_PARAMETER`; UUID inválido responde `400 TYPE_MISMATCH`). Não serve como prova: a mesma cadeia de autorização e a trava do PUT são aplicadas.
* **Respostas**: `204 No Content` ao retirar. Oferta inexistente para a conta no local responde `404 PLACE_PRODUCT_OFFER_NOT_FOUND`, avaliado só depois de o autor provar que representa o local; para os demais, os erros são os da autorização (`401`, `404 BUSINESS_ACCOUNT_NOT_FOUND`, `409`, `404 PLACE_NOT_FOUND`, `403`).

---

## 3. Códigos de Erro Esperados
* `400 Bad Request`:
  * Payload malformado, valores de coordenadas fora do intervalo WGS 84, identificador inválido (`Erro de Validação de Dados`).
  * Coordenadas inválidas para busca nearby (`INVALID_NEARBY_COORDINATES`).
  * Raio inválido para busca nearby (`INVALID_NEARBY_RADIUS`).
  * Limite inválido para busca nearby (`INVALID_NEARBY_LIMIT`).
  * Busca global: termo vazio (`INVALID_SEARCH_QUERY`), página negativa (`INVALID_PAGE`), tamanho fora de 1..50 (`INVALID_PAGE_SIZE`).
  * Produtos de um local: página negativa (`INVALID_PAGE`), tamanho fora de 1..50 (`INVALID_PAGE_SIZE`).
  * Produto por código: tipo fora de `EAN`/`UPC`/`GTIN`/`ISBN` (`INVALID_IDENTIFIER_TYPE`) ou valor vazio, longo demais ou com caractere inválido (`INVALID_IDENTIFIER_VALUE`).
  * Horários de funcionamento: identificadores ausentes (`MISSING_PLACE_ID`, `MISSING_BUSINESS_ACCOUNT_ID`), dia da semana inválido (`INVALID_DAY_OF_WEEK`), dia repetido na requisição (`DUPLICATE_DAY_OF_WEEK`), incoerência entre dia fechado e intervalos (`INVALID_OPENING_HOURS`), intervalo com horário de abertura igual ou posterior ao fechamento (`INVALID_OPENING_HOURS_INTERVAL`), ou intervalos sobrepostos no mesmo dia (`OPENING_HOURS_OVERLAPPING_INTERVALS`).
  * Comodidades: código fora do catálogo (`INVALID_AMENITY_CODE`), código repetido na requisição (`DUPLICATE_AMENITY`), `businessAccountId`/`amenities` ausentes, lista acima de 50 elementos ou elemento nulo (`VALIDATION_ERROR`), `amenities` que não seja lista (`MALFORMED_REQUEST`).
  * Ofertas comerciais: preço negativo, com mais de duas casas ou acima do máximo (`INVALID_OFFER_PRICE`), moeda diferente de `BRL` (`UNSUPPORTED_CURRENCY`), disponibilidade fora de `AVAILABLE`/`OUT_OF_STOCK` (`INVALID_OFFER_AVAILABILITY`), `businessAccountId`/`availability` ausentes (`VALIDATION_ERROR`), preço não numérico (`MALFORMED_REQUEST`), `businessAccountId` ausente no DELETE (`MISSING_PARAMETER`), página negativa (`INVALID_PAGE`) e tamanho fora de 1..50 (`INVALID_PAGE_SIZE`).
* `401 Unauthorized`: Ausência de token JWT ou token expirado/inválido (`AUTHENTICATION_REQUIRED` / `UNAUTHORIZED`).
* `401 Unauthorized` (`ACCOUNT_DISABLED`): nas escritas (`POST /places`, `POST /places/adopt`, `POST /products`, `POST /products/{id}/identifiers`, `POST /products/{id}/presence`, `PUT /places/{id}/opening-hours`, `PUT /places/{id}/amenities`, `PUT` e `DELETE /places/{id}/offers/{productId}`), a conta do autor não está operacional (desativada, suspensa, excluída ou inexistente), mesmo com access token ainda válido.
* `403 Forbidden`: Conta comercial não responde pelo estabelecimento informado (`BUSINESS_NOT_RESPONSIBLE`).
* `404 Not Found`: Local, produto ou referência externa não localizada (`PLACE_NOT_FOUND`, `PRODUCT_NOT_FOUND`, `PLACE_EXTERNAL_REFERENCE_NOT_FOUND`). No detalhe público, nos horários de funcionamento, nas comodidades e nas ofertas, local fora de `ACTIVE` também responde `PLACE_NOT_FOUND`, indistinguível de inexistente. Conta comercial inexistente ou de outro usuário responde `BUSINESS_ACCOUNT_NOT_FOUND`. Na oferta, produto global inexistente ou fora de `ACTIVE` responde `PRODUCT_NOT_FOUND` e oferta inexistente no DELETE responde `PLACE_PRODUCT_OFFER_NOT_FOUND`.
* `409 Conflict`: Conflito de integridade relacional (`PLACE_SLUG_ALREADY_EXISTS`, `IDENTIFIER_ALREADY_EXISTS`, `PRODUCT_PRESENCE_ALREADY_EXISTS`). Conta comercial pendente de aprovação ao tentar configurar horários (`BUSINESS_ACCOUNT_NOT_APPROVED`); nas comodidades e nas ofertas, conta pendente ou rejeitada (`BUSINESS_ACCOUNT_NOT_APPROVED`).
* `429 Too Many Requests` (`RATE_LIMIT_EXCEEDED`): limite de buscas do usuário atingido (busca global e produto por código); acompanha `Retry-After`.
