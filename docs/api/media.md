# API REST de Mídias de Avaliações (docs/api/media.md)

Este documento especifica os contratos, requisitos de segurança, limites e arquitetura da API RESTful para upload, recuperação, listagem e exclusão de **imagens anexadas a Reviews** no ecossistema **Rewit** (**Step 21.0**).

---

## 1. Princípios Arquiteturais e de Privacidade

### 1.1 Isolamento do Storage e Não Exposição de Chaves Internas
> [!IMPORTANT]
> **O Rewit nunca expõe diretamente a chave do object storage (`object_key`), bucket, volume ou URL interna do SeaweedFS/MinIO.**
> Todas as mídias são disponibilizadas exclusivamente através de endpoints da própria API Rewit (`/api/v1/reviews/{reviewId}/media/{mediaId}`), intermediadas por serviços que aplicam validação rigorosa de autorização e mitigação de vulnerabilidades (como IDOR).

### 1.2 Sanitização Obrigatória e Proteção Anti-Decompression Bomb
Seguindo as diretrizes estritas de privacidade do projeto (ADR-005 e LGPD):
* **Validação de Cabeçalho Antecipada (Anti-Decompression Bomb)**:
  1. O arquivo tem seus magic bytes validados contra as assinaturas de JPEG e PNG.
  2. Um `ImageReader` de baixo nível lê exclusivamente os metadados do cabeçalho binário (IHDR/SOF0) através de `ImageInputStream`, inspecionando largura, altura e contagem total de pixels **sem alocar ou decodificar o raster em memória**.
  3. Imagens que excedem 10.000 x 10.000 pixels ou 100 megapixels são rejeitadas imediatamente (`INVALID_IMAGE_DIMENSIONS`), protegendo a JVM contra ataques de negação de serviço por exaustão de heap.
* **Sanitização de Metadados e Re-encoding**:
  * Somente após a aprovação dimensional o raster é decodificado para uma `BufferedImage` virgem em memória.
  * O re-encoding binário é executado gerando um novo stream JPEG ou PNG sem qualquer bloco EXIF, IPTC ou XMP.
  * Coordenadas geográficas (tags EXIF GPS `0x0001` a `0x001F`), modelo de dispositivo, dados de captura e comentários embutidos são **irreversivelmente eliminados**.
  * Em caso de falha em qualquer etapa de validação ou sanitização, a operação é imediatamente cancelada e nenhum arquivo original é persistido no Object Storage.

### 1.3 Preservação de Anonimato
* Uma avaliação anônima (`isAnonymous = true`) estende sua proteção à API de Mídia:
  * Nenhuma resposta pública (`ReviewMediaResponse`) ou cabeçalho HTTP expõe o `userId`, `authorId`, `handle` ou perfil do autor do upload.
  * O identificador do autor é persistido internamente no PostgreSQL apenas para auditoria, governança e validação de permissão de exclusão.

### 1.4 Herança de Autorização da Review
A mídia herda a autorização e ciclo de vida da Review correspondente via `ReviewVisibilityPolicy`:
* **`PUBLIC`**: Usuário autenticado com acesso à avaliação pode fazer upload e visualizar as mídias.
* **`FOLLOWERS`**: Autor da avaliação + seguidores legítimos podem fazer upload e visualizar mídias. Terceiros não-seguidores recebem `403 Forbidden`.
* **`PRIVATE`**: Somente o próprio autor da avaliação tem permissão para upload, listagem e download. Terceiros recebem `403 Forbidden`.
* **`UNDER_REVIEW`** (moderação preventiva) ou **`REMOVED`**: Acesso à avaliação e mídias associadas é bloqueado (`404 NOT_FOUND`).

### 1.5 Autorização e Consistência de Exclusão (DELETE)
* **Somente o autor da Review pode excluir anexos de mídia.**
* Tentativas de exclusão por seguidores, autores de comentários ou terceiros retornam `403 Forbidden`.
* **Ordem de Operações e Consistência Lógica**:
  1. O registro em `review_media` é marcado com status `REMOVED` e comitado no PostgreSQL em primeiro lugar.
  2. Em seguida, a remoção física no Object Storage (`objectStoragePort.delete(objectKey)`) é executada.
  3. Essa ordem impede a ocorrência de inconsistência crítica onde uma mídia permaneceria `ACTIVE` no banco apontando para um objeto inexistente no storage.
  4. Caso ocorra falha de comunicação ou indisponibilidade no storage durante o delete, o banco permanece como `REMOVED`. Como todos os endpoints públicos exigem `status = ACTIVE`, o objeto torna-se imediatamente inacessível para qualquer usuário, garantindo a segurança lógica dos dados.
* Operação idempotente: requisições repetidas para mídia já removida retornam sucesso (`204 No Content`).

### 1.6 Limitações de Consistência e Garbage Collection
* **Ausência de Transações Distribuídas (2PC)**: PostgreSQL e SeaweedFS/S3 não participam de transação atômica compartilhada. Em falha de exclusão física do storage após atualização do banco, o blob binário permanece no storage, sem no entanto ser exposto publicamente por nenhuma rota da API. Como a linha `REMOVED` continua sendo referência, esse blob **não** é coletado pelo GC de storage do STEP 28; uma política de retenção para mídias `REMOVED` é uma decisão separada.
* **Exclusão Física em Cascata (ON DELETE CASCADE)**: A foreign key relacional `review_media.review_id -> reviews(id) ON DELETE CASCADE` exclui os metadados relacionais caso uma Review seja excluída fisicamente do banco de dados (ex: scripts SQL manuais de manutenção). No fluxo padrão da aplicação Rewit, Reviews utilizam exclusivamente soft delete (`status = 'REMOVED'`). Objetos que ficam sem nenhuma linha em `review_media` são tratados pelo GC de storage (STEP 28): quarentena, grace period, rechecagem sob lock e exclusão idempotente, desligado por padrão. Ver [Reconciliação e GC de Storage de Mídia](../architecture/storage-reconciliation.md).

### 1.7 Rate Limiting Process-Local
* O limitador de taxa (10 uploads/minuto por usuário) é mantido em memória local da JVM (`ConcurrentHashMap`), com limpeza reativa de janelas expiradas.
* Em arquiteturas com múltiplas réplicas, cada nó gerencia sua própria cota local, não dependendo de chamadas centralizadas ao Redis neste estágio.

---

## 2. Limites Operacionais e Formatos Suportados

| Parâmetro | Limite / Regra | Comportamento em caso de Violação |
| :--- | :--- | :--- |
| **Formatos Permitidos** | JPEG (`image/jpeg`), PNG (`image/png`) | `415 Unsupported Media Type` (`UNSUPPORTED_MEDIA_TYPE`) |
| **Detecção de Tipo** | Magic Bytes reais do cabeçalho binário | `415 Unsupported Media Type` (rejeita SVG, HTML, PDF, binários) |
| **Tamanho Máximo** | **10 MB** (10.485.760 bytes) por arquivo | `413 Payload Too Large` (`MEDIA_SIZE_EXCEEDED`) |
| **Tamanho Mínimo** | Maior que 0 bytes | `400 Bad Request` (`EMPTY_MEDIA_FILE`) |
| **Dimensões Máximas** | **10.000 x 10.000 pixels** e máx. 100 MP (validado antes do decode) | `400 Bad Request` (`INVALID_IMAGE_DIMENSIONS`) |
| **Quantidade Máxima** | **5 imagens ativas** por Review (protegido por lock pessimista) | `400 Bad Request` (`MAX_MEDIA_LIMIT_REACHED`) |
| **Rate Limiting** | **10 uploads por minuto** por usuário (process-local) | `429 Too Many Requests` (`RATE_LIMIT_EXCEEDED`) |

---

## 3. Endpoints da API

### 3.1 Upload de Mídia
Envia um anexo de imagem sanitizada para a avaliação especificada.

* **Método**: `POST`
* **Rota**: `/api/v1/reviews/{reviewId}/media`
* **Content-Type**: `multipart/form-data`
* **Autenticação**: Obrigatória (`Bearer <JWT>`)
* **Parâmetro de Formulário**: `file` (`MultipartFile`)

#### Resposta de Sucesso (`201 Created`):
```json
{
  "id": "7b58797f-1d4e-4f38-9ec1-3f48a1d7c34b",
  "reviewId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
  "url": "/api/v1/reviews/f47ac10b-58cc-4372-a567-0e02b2c3d479/media/7b58797f-1d4e-4f38-9ec1-3f48a1d7c34b",
  "mediaType": "IMAGE",
  "mimeType": "image/jpeg",
  "sizeBytes": 245123,
  "width": 1920,
  "height": 1080,
  "status": "ACTIVE",
  "createdAt": "2026-09-30T12:00:00Z"
}
```

---

### 3.2 Listagem de Mídias de uma Avaliação
Retorna a lista de imagens ativas vinculadas à avaliação informada, em ordem cronológica de inclusão (`created_at ASC, id ASC`).

* **Método**: `GET`
* **Rota**: `/api/v1/reviews/{reviewId}/media`
* **Autenticação**: Obrigatória (`Bearer <JWT>`)

#### Resposta de Sucesso (`200 OK`):
```json
[
  {
    "id": "7b58797f-1d4e-4f38-9ec1-3f48a1d7c34b",
    "reviewId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
    "url": "/api/v1/reviews/f47ac10b-58cc-4372-a567-0e02b2c3d479/media/7b58797f-1d4e-4f38-9ec1-3f48a1d7c34b",
    "mediaType": "IMAGE",
    "mimeType": "image/jpeg",
    "sizeBytes": 245123,
    "width": 1920,
    "height": 1080,
    "status": "ACTIVE",
    "createdAt": "2026-09-30T12:00:00Z"
  }
]
```

---

### 3.3 Visualização / Download de Imagem Sanitizada
Recupera o stream binário da imagem sanitizada para exibição na interface web ou aplicativo móvel.

* **Método**: `GET`
* **Rota**: `/api/v1/reviews/{reviewId}/media/{mediaId}`
* **Autenticação**: Obrigatória (`Bearer <JWT>`)

#### Cabeçalhos de Resposta:
* `Content-Type`: `image/jpeg` ou `image/png`
* `Content-Length`: `<tamanho-em-bytes>`
* `Cache-Control`: depende da visibilidade da avaliação:
  * `PUBLIC`: `public, max-age=86400`;
  * `PRIVATE` e `FOLLOWERS`: `private, no-store`. O acesso depende de quem lê, então a mídia nunca pode ser guardada por cache compartilhado (proxy/CDN).

#### Prevenção Anti-IDOR:
Caso a `mediaId` exista no sistema mas pertença a uma avaliação distinta daquela presente no path (`reviewId`), o backend retorna imediatamente `404 Not Found` (`MEDIA_NOT_FOUND`), impedindo a inferência ou correlação de identificadores entre avaliações.

---

### 3.4 Exclusão de Mídia
Remove o anexo de imagem da avaliação.

* **Método**: `DELETE`
* **Rota**: `/api/v1/reviews/{reviewId}/media/{mediaId}`
* **Autenticação**: Obrigatória (`Bearer <JWT>`, requer autor da Review)

#### Resposta de Sucesso (`204 No Content`):
* Corpo vazio.

---

## 4. Códigos de Erro Padronizados (RFC 7807)

| Status HTTP | `code` | Descrição |
| :--- | :--- | :--- |
| `400 Bad Request` | `EMPTY_MEDIA_FILE` | O arquivo enviado no upload possui 0 bytes. |
| `400 Bad Request` | `INVALID_IMAGE_DIMENSIONS` | A imagem excede o limite dimensional de 10.000x10.000 pixels. |
| `400 Bad Request` | `MAX_MEDIA_LIMIT_REACHED` | A avaliação já possui 5 mídias ativas anexadas. |
| `401 Unauthorized` | `UNAUTHORIZED` | Token JWT ausente, expirado ou inválido. |
| `403 Forbidden` | `FORBIDDEN` | Usuário não autorizado a acessar avaliação privada/restrita ou a excluir mídia de terceiros. |
| `404 Not Found` | `REVIEW_NOT_FOUND` | Avaliação não encontrada ou em estado `UNDER_REVIEW`/`REMOVED`. |
| `404 Not Found` | `MEDIA_NOT_FOUND` | Mídia inexistente, inativa ou desacoplada da avaliação informada (IDOR). |
| `413 Payload Too Large` | `MEDIA_SIZE_EXCEEDED` | Arquivo físico enviado superior a 10 MB. |
| `415 Unsupported Media Type` | `UNSUPPORTED_MEDIA_TYPE` | Formato não aceito ou magic bytes incompatíveis com JPEG/PNG. |
| `429 Too Many Requests` | `RATE_LIMIT_EXCEEDED` | Limite de 10 uploads por minuto atingido pelo usuário. |
