# Reconciliação de Storage de Mídia — Contrato (STEPs 28.1 e 28.3)

Este documento registra o contrato que compara os objetos do Object Storage (SeaweedFS via API S3) com as referências de mídia persistidas no PostgreSQL, e a quarentena que prepara uma futura remoção. **Até aqui a reconciliação é somente diagnóstico/planejamento: nenhum objeto é removido**, não há scheduler, job periódico nem endpoint.

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

## Quarentena persistente e rechecagem (STEP 28.3)

O caminho até uma futura remoção separa quatro perguntas. Este passo responde às três primeiras; a quarta pertence ao STEP 28.4:

| Pergunta | Onde |
| :--- | :--- |
| A. Existe referência em `review_media`? | Segunda consulta em `StorageQuarantineRepository.resolveUnderCreationLock` |
| B. O grace period já terminou? | `StorageQuarantineGracePolicy` |
| C. A segunda consulta confirmou a ausência de referência? | `RecheckQuarantinedStorageObjectUseCase` → `CONFIRMED_ORPHAN` |
| D. O objeto pode ser fisicamente removido? | **Não respondida aqui** (STEP 28.4) |

### Tabela `storage_object_quarantine` (migration V15)

- Uma linha por `object_key`, garantida por `uq_storage_object_quarantine_object_key`. Ela registra a observação de storage e não copia `review_media`, nem tem FK para `reviews`: o objeto justamente pode não ter referência.
- Colunas: `status` (`OBSERVED` | `CONFIRMED_ORPHAN`), `first_observed_at`, `last_observed_at`, `last_modified_at` (do storage, opcional) e `confirmed_at` (preenchido somente em `CONFIRMED_ORPHAN`, por constraint).
- `ReconcileReviewMediaStorageUseCase` registra os `OrphanCandidate` de cada página ao fim dela, então uma passada interrompida não perde observações. Reiniciar o processo não afeta a quarentena, que vive no PostgreSQL.
- O registro é um upsert `ON CONFLICT (object_key)`: a chave nova cria a linha; a chave conhecida preserva `first_observed_at`, avança `last_observed_at` (sem regredir em observações atrasadas) e atualiza `last_modified_at` quando o storage o informa. Execuções repetidas ou concorrentes convergem para uma única linha.

### `first_observed_at` e grace period

- `first_observed_at` é a **primeira observação persistida** sem referência e marca o início do grace period. Timestamps de `review_media` não são usados, porque o objeto pode não ter referência.
- O grace period é uma política (`StorageQuarantineGracePolicy`). A implementação `FixedStorageQuarantineGracePolicy` recebe a `Duration` de quem a monta e não tem valor padrão. **Nenhum valor operacional está definido**: ele será decidido junto com a etapa que agendar a reconciliação.
- Antes do fim do grace period, a rechecagem devolve `GRACE_PERIOD_NOT_ELAPSED` sem consultar `review_media`.

### Segunda consulta e proteção contra a corrida de criação

`ReviewMediaService.uploadMedia` adquire `ReviewJpaRepository.findByIdForUpdate` (`PESSIMISTIC_WRITE` na linha de `reviews`) **antes** de enviar o objeto ao storage e o mantém até o commit da linha em `review_media`. Como toda chave contém o `reviewId` e cada upload gera um `mediaId` aleatório novo, uma transação que detém esse mesmo lock e não encontra referência sabe que não há upload em andamento para aquela chave.

A segunda consulta usa exatamente esse lock. Em uma única transação, `resolveUnderCreationLock`:

1. adquire `findByIdForUpdate(reviewId)`, com o `reviewId` extraído da chave. Se a review não existe, nenhum upload consegue criar referência para ela;
2. trava a linha de quarentena exigindo o `first_observed_at` avaliado pelo grace period. Se a linha sumiu ou foi recriada, nada muda (`NOT_QUARANTINED`);
3. consulta `review_media` pela chave.

Desfechos de `RecheckQuarantinedStorageObjectUseCase`:

| Desfecho | Significado | Efeito na quarentena |
| :--- | :--- | :--- |
| `NOT_QUARANTINED` | não há linha correspondente à observação avaliada | nenhum |
| `GRACE_PERIOD_NOT_ELAPSED` | grace period em curso | nenhum |
| `WITH_REFERENCE` | existe linha em `review_media`, **ACTIVE ou REMOVED** | linha liberada; uma nova observação reinicia o grace period |
| `CONFIRMED_ORPHAN` | sob o lock, nenhuma referência | `CONFIRMED_ORPHAN`, preservando o primeiro `confirmed_at` |

`REMOVED` continua sendo referência. Nenhuma política de retenção para mídias `REMOVED` é definida aqui.

Rechecagens concorrentes da mesma chave são serializadas pelo lock da review. Uma rechecagem que encontra um upload em andamento espera o commit e então vê a referência. Isso é coberto por teste de integração, e o teste falha se o lock for removido.

### O que o STEP 28.4 deve fazer

`CONFIRMED_ORPHAN` **não autoriza delete**: o lock é liberado no commit da rechecagem. A remoção física deverá, numa única transação, adquirir `findByIdForUpdate` da review da chave, travar a linha de quarentena `CONFIRMED_ORPHAN`, repetir a consulta a `review_media`, remover o objeto e só então encerrar a quarentena.

Nem a rechecagem nem o adapter da quarentena têm acesso a `ObjectStoragePort`, o que é verificado por testes de fronteira. Os logs registram apenas desfecho, status da referência e duração, nunca a `object_key`.

## Separação do Outbox

A reconciliação e a quarentena são uma varredura direta, independente do Outbox (decisão do STEP 27.0). Não geram nem consomem mensagens de `outbox_messages`.

## Exposição

Os tipos de reconciliação carregam chaves internas do storage e são exclusivamente internos. Nenhum deles é exposto pela API pública, e o use case não aceita chaves vindas de usuário: o prefixo é fixo e o marcador precisa estar sob `reviews/`. O log técnico registra apenas contagens.
