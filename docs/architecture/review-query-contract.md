# Review Query Contract

## Visibilidade vs filtros de query

A decisão final de acesso a uma review continua sendo responsabilidade de `ReviewVisibilityPolicy`.

- `ReviewVisibilityPolicy`: responde a pergunta: "este requester pode realmente ver esta review?"
- `ReviewQuery`: responde a pergunta: "quais reviews entram no conjunto elegível desta consulta?"

Essas duas camadas não devem ser confundidas. O contrato de query não substitui autorização, apenas delimita candidatos elegíveis.

## Query Contract

O objeto compartilhado `ReviewQuery` foi criado para representar critérios de leitura de review sem acoplar a HTTP, JPA ou endpoint.

Campos principais:
- requesterUserId
- status
- visibilityScope
- targetId
- authorUserId
- verifiedOnly
- ratingMin / ratingMax
- createdFrom / createdTo
- page / size
- sort

### Defaults seguros

- status padrão: `ACTIVE`
- sort padrão: `NEWEST`
- page padrão: `0`
- size padrão: `20`
- visibilityScope padrão: `ONLY_PUBLIC`

Não é permitido passar `UNDER_REVIEW` ou `REMOVED` em queries públicas. Esse é um guardrail arquitetural para evitar fuga de conteúdo moderado.

## Sorts suportados

Atualmente, o contrato formaliza apenas:
- `NEWEST`
- `RATING_DESC`
- `RATING_ASC`

Sorts futuros como relevância, distância, popularidade e reputação exigem um contrato separado e não são aceitáveis no domínio atual.

## Paginação

- page >= 0
- size entre 1 e 50
- `PageResult` continua sendo a estrutura de aplicação reutilizada

A query contract não duplica a paginação da API; ela apenas valida e descreve os critérios.

## Anonymous projection

A projeção pública da review não revela identidade do autor quando a review é anônima. A seleção de candidatos pode incluir a review, mas a projeção pública decide como o autor será exibido.

Isso separa:
- `candidate selection`
- `public projection`

## Relação com Search e Feed V2

Este step não implementa Search nem Feed V2.

A intenção é preparar a base arquitetural para:
- Search V1 com filtros e ordenação explícitos
- Discovery com combinação de texto e geolocalização
- Feed V2 com visibilidade e anonimato reutilizados

A regra é sempre a mesma: visibilidade e autorização continuam sendo centralizadas em `ReviewVisibilityPolicy`, e a query cria apenas o conjunto elegível.

## Compatibilidade

Os endpoints existentes não mudaram de comportamento:
- Feed V1 permanece social e baseado em usuários seguidos
- target listing continua operando por alvo e status ativo
- `/me/reviews` continua com semântica privada do autor

## Banco e índices

Este step não cria migration nem índices de performance. Qualquer melhoria futura de índice textual ou geoespacial fica registrada como preparação futura, não como parte da implementação desta etapa.
