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

## 3. Códigos de Erro Esperados
* `400 Bad Request`: Payload malformado, valores de coordenadas fora do intervalo WGS 84, identificador inválido (`Erro de Validação de Dados`).
* `401 Unauthorized`: Ausência de token JWT ou token expirado (`AUTHENTICATION_REQUIRED`).
* `404 Not Found`: Local ou produto não localizado (`PLACE_NOT_FOUND`, `PRODUCT_NOT_FOUND`).
* `409 Conflict`: Conflito de integridade relacional (`PLACE_SLUG_ALREADY_EXISTS`, `IDENTIFIER_ALREADY_EXISTS`, `PRODUCT_PRESENCE_ALREADY_EXISTS`).
