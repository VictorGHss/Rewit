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

---

## 3. Códigos de Erro Esperados
* `400 Bad Request`:
  * Payload malformado, valores de coordenadas fora do intervalo WGS 84, identificador inválido (`Erro de Validação de Dados`).
  * Coordenadas inválidas para busca nearby (`INVALID_NEARBY_COORDINATES`).
  * Raio inválido para busca nearby (`INVALID_NEARBY_RADIUS`).
  * Limite inválido para busca nearby (`INVALID_NEARBY_LIMIT`).
* `401 Unauthorized`: Ausência de token JWT ou token expirado/inválido (`AUTHENTICATION_REQUIRED` / `UNAUTHORIZED`).
* `404 Not Found`: Local, produto ou referência externa não localizada (`PLACE_NOT_FOUND`, `PRODUCT_NOT_FOUND`, `PLACE_EXTERNAL_REFERENCE_NOT_FOUND`).
* `409 Conflict`: Conflito de integridade relacional (`PLACE_SLUG_ALREADY_EXISTS`, `IDENTIFIER_ALREADY_EXISTS`, `PRODUCT_PRESENCE_ALREADY_EXISTS`).
