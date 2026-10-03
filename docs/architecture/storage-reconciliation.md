# Reconciliação de Storage de Mídia — Contrato (STEP 28.1)

Este documento registra o contrato que compara os objetos do Object Storage (SeaweedFS via API S3) com as referências de mídia persistidas no PostgreSQL. **Neste passo a reconciliação é somente diagnóstico/planejamento: nenhum objeto é removido**, não há scheduler, job periódico nem endpoint.

## Fonte de verdade e namespace gerenciado

- O PostgreSQL (`review_media.object_key`) é a fonte de verdade das referências.
- O único prefixo gerenciado é `reviews/` (`ReviewMediaObjectKey.MANAGED_PREFIX`). As chaves geradas pelo upload seguem `reviews/{reviewId}/{mediaId}/image.{jpg|png}`.
- Objetos fora de `reviews/` são ignorados, mesmo que a listagem os devolva. Chaves sob `reviews/` que não seguem o formato gerado são contadas como **não reconhecidas** e nunca viram candidatas: a aplicação não as criou.

## Componentes

| Componente | Local | Papel |
| :--- | :--- | :--- |
| `ObjectStorageListingPort` | `application/port` | Listagem paginada **somente leitura** por prefixo. Separada de `ObjectStoragePort`, então quem depende só dela não consegue gravar nem remover. |
| `StorageReconciliationDtos` | `application/dto/storage` | `StoredObject`, `StoredObjectPage`, `ReviewMediaReference`, `OrphanCandidate`, `StorageReconciliationReport`. Nenhum tipo do SDK do MinIO. |
| `ReviewMediaRepository.findReferencesByObjectKeys` | `application/port` | Referências (qualquer status) das chaves de **uma página**, via `object_key IN (...)` sobre o índice único existente. |
| `ReviewMediaObjectKey` | `domain/model` | Prefixo gerenciado e reconhecimento do formato de chave. |
| `ReconcileReviewMediaStorageUseCase` | `application/usecase` | Cruza páginas do storage com o banco e produz o relatório. Livre de Spring; não recebe `ObjectStoragePort`. |
| `MinioStorageAdapter.listObjects` | `infrastructure/storage` | Converte o `listObjects` do SDK (com `prefix`, `start-after`, `max-keys`) no contrato, sem criar bucket. |

## Paginação e lotes

- A página é pedida com `(prefix, startAfter, maxKeys)` e devolve `nextStartAfter`: a última chave lida quando a página veio cheia, ou `null` ao fim do prefixo. É o `start-after` do S3: sem estado no servidor, em ordem lexicográfica e retomável.
- Cada página gera uma única consulta ao banco, limitada às chaves daquela página.
- Uma execução lê no máximo `maxPages` páginas de `pageSize` objetos. Se o prefixo não terminou, o relatório traz `complete = false` e o `nextStartAfter` para a próxima passada. A memória fica limitada a `pageSize × maxPages`, nunca ao bucket inteiro.
- O marcador de retomada só é aceito se pertencer a `reviews/`, e uma listagem que não avança o marcador interrompe a execução.

## Classificação

| Situação | Resultado |
| :--- | :--- |
| Referência `ACTIVE` | contada em `activeReferences` |
| Referência `REMOVED` com objeto presente | listada em `removedReferences`; **não é órfã** (a remoção física falhou ou está pendente). Nenhuma política de retenção é definida aqui. |
| Sem referência, chave no formato gerenciado | `OrphanCandidate` (órfão **observado**) |
| Fora de `reviews/` / formato não reconhecido / chave repetida na mesma execução | apenas contadas, nunca candidatas |

## Órfão observado não é órfão removível

O upload grava o objeto no storage **antes** de commitar a linha em `review_media`. Logo, a sequência abaixo é legítima:

1. T1 — a reconciliação lista o objeto;
2. T2 — o upload commita a referência;
3. T3 — a reconciliação conclui que o objeto não tinha referência.

Por isso `OrphanCandidate` carrega `observedAt` (e `lastModified` do storage) e **não autoriza remoção**. Uma futura etapa de remoção precisa, no mínimo:

1. aguardar um grace period a partir da observação/última modificação — o valor **não** é definido neste passo;
2. reconsultar o PostgreSQL imediatamente antes do delete;
3. só então tentar o delete.

Em `docs/api/media.md`, "objeto órfão" descreve o blob que sobra quando a remoção física falha após o `REMOVED`. Neste contrato esse caso é uma referência `REMOVED` com objeto presente, e não um `OrphanCandidate`.

## Separação do Outbox

A reconciliação é uma varredura direta, independente do Outbox (decisão do STEP 27.0). Não gera nem consome mensagens de `outbox_messages`.

## Exposição

Os tipos de reconciliação carregam chaves internas do storage e são exclusivamente internos. Nenhum deles é exposto pela API pública, e o use case não aceita chaves vindas de usuário: o prefixo é fixo e o marcador precisa estar sob `reviews/`. O log técnico registra apenas contagens.
