# PADRONIZAÇÃO E CONVENÇÕES DE API REST (docs/api/conventions.md)

Este documento especifica os padrões de projeto para os endpoints HTTP RESTful da API central do **Rewit**.

---

## 1. Versionamento de Endpoints

Todas as rotas públicas e autenticadas devem obrigatoriamente conter o prefixo de versão:
```
/api/v1/{recurso}
```
Exemplos:
- `GET /api/v1/me`
- `PATCH /api/v1/me/profile`
- `GET /api/v1/places/nearby`
- `POST /api/v1/reviews`
- `GET /api/v1/products/scan/{barcode}`

---

## 2. Estrutura de Resposta de Sucesso

As respostas de sucesso utilizam envelope padronizado para coleções e respostas estruturadas:

### Resposta de Objeto Único:
```json
{
  "success": true,
  "data": {
    "id": "c3b6f001-e28b-4a5c-9c71-4608c0f5f7a1",
    "name": "Café do Centro",
    "averageRating": 4.85
  },
  "timestamp": "2026-09-27T16:30:00Z"
}
```

### Resposta Paginada (Paginação Baseada em Offset/Página):
```json
{
  "success": true,
  "data": [ ... ],
  "pagination": {
    "page": 0,
    "size": 20,
    "totalElements": 142,
    "totalPages": 8,
    "isLast": false
  },
  "timestamp": "2026-09-27T16:30:00Z"
}
```

---

## 3. Tratamento de Erros no Padrão RFC 7807 (Problem Details)

Erros HTTP (4xx e 5xx) utilizam o padrão internacional [RFC 7807](https://tools.ietf.org/html/rfc7807):

```json
{
  "type": "https://api.rewit.com/errors/spatial-validation-failed",
  "title": "Validação Espacial Falhou",
  "status": 422,
  "detail": "O usuário encontra-se a 145.2 metros do local, excedendo o raio máximo permitido de 50 metros para check-in.",
  "instance": "/api/v1/reviews",
  "code": "OUT_OF_BOUNDS_CHECKIN",
  "errors": [
    {
      "field": "coordinates",
      "message": "Distância do centróide incompatível com check-in presencial"
    }
  ],
  "timestamp": "2026-09-27T16:30:00Z"
}
```

---

## 4. Idempotência em Operações de Escrita

Para prevenir duplicação de publicações, pagamentos ou check-ins em cenários de reconexão de rede móvel intermitente:
- Clientes mobile devem gerar e enviar o cabeçalho HTTP:
  ```http
  Idempotency-Key: {UUIDv4}
  ```
- O backend registra o resultado da operação no Redis associado a essa chave com TTL de 24 horas. Requisições subsequentes com o mesmo identificador retornam o resultado original instantaneamente sem reprocessar mutações no banco.
