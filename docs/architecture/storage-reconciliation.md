# Reconciliação de Storage de Mídia — Contrato (STEPs 28.1, 28.3 e 28.4)

Este documento registra o contrato que compara os objetos do Object Storage (SeaweedFS via API S3) com as referências de mídia persistidas no PostgreSQL, a quarentena e a exclusão física segura. A varredura (28.1) e a rechecagem (28.3) nunca removem objetos. A exclusão física (28.4) só atinge objetos `CONFIRMED_ORPHAN`, após nova consulta sob os locks. Ainda não há scheduler, job periódico nem endpoint: nada dispara esses use cases automaticamente.

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

A segunda consulta usa exatamente esse lock. Em uma única transação PostgreSQL (sem nenhuma operação de storage), `resolveUnderCreationLock`:

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

### `CONFIRMED_ORPHAN` não autoriza delete sozinho

O lock é liberado no commit da rechecagem. `CONFIRMED_ORPHAN` torna o objeto **elegível** para a exclusão física do STEP 28.4, que repete a consulta sob os locks antes de tocar o storage. Nem a rechecagem nem o adapter da quarentena têm acesso a `ObjectStoragePort`, o que é verificado por testes de fronteira. Os logs registram apenas desfecho, status da referência e duração, nunca a `object_key`.

## Exclusão física segura (STEP 28.4)

### Contrato de exclusão

`ObjectStorageDeletionPort.delete(key)` devolve `ObjectDeletionResult`, sem lançar erros de storage. `ObjectStoragePort` estende essa porta, então os chamadores existentes (compensação do upload e exclusão de mídia) não mudaram. `PurgeConfirmedOrphanStorageObjectUseCase` recebe **somente** `ObjectStorageDeletionPort`, sem gravação, leitura nem listagem, e atua sempre no bucket configurado.

| Resultado | Origem no SeaweedFS/S3 (verificada contra o SeaweedFS 4.47) | Significado |
| :--- | :--- | :--- |
| `DELETED` | `statObject` e `removeObject` com sucesso | objeto removido |
| `NOT_FOUND` | `NoSuchKey`/`ResourceNotFound` no `statObject` (ou na remoção) | **sucesso idempotente**: o estado desejado já existe |
| `PERMANENT_FAILURE` | `AccessDenied`, `InvalidAccessKeyId`, `SignatureDoesNotMatch`, `NoSuchBucket`; `InvalidKeyException`/`NoSuchAlgorithmException`; `IllegalArgumentException` dos argumentos | credencial/configuração/contrato: repetir não resolve |
| `TRANSIENT_FAILURE` | demais `ErrorResponseException`; `ServerException`, `IOException` (ex.: `ConnectException`), `InsufficientDataException`, `InternalException`, `InvalidResponseException`, `XmlParserException` | repetir é seguro |

- O `statObject` existe porque o `DeleteObject` do S3 responde sucesso também para chave ausente. Sem ele, `DELETED` e `NOT_FOUND` seriam indistinguíveis.
- Exceções fora dessas categorias não são engolidas: propagam.
- O log de falha registra apenas o resultado e o código/classe do erro, nunca a chave, o endpoint ou a mensagem do SDK.

### Ordem das operações

PostgreSQL e SeaweedFS **não formam uma transação distribuída**. A exclusão mantém uma transação PostgreSQL com os locks ativos **enquanto** chama o storage, mas a exclusão no storage é uma operação externa: rollback do PostgreSQL **não** a desfaz. A ordem existe para que qualquer falha deixe o estado recuperável por nova tentativa:

1. lock da review dona da chave (`findByIdForUpdate`, o mesmo do upload e da rechecagem);
2. lock da linha de quarentena, exigindo `CONFIRMED_ORPHAN` e o `first_observed_at` lido antes;
3. nova consulta a `review_media`. Se houver referência, ACTIVE ou REMOVED, o storage não é tocado e a quarentena é liberada (`WITH_REFERENCE`);
4. exclusão no storage;
5. remoção da linha de quarentena, **somente** se o resultado for `DELETED` ou `NOT_FOUND`;
6. commit.

Remover a linha de quarentena antes da exclusão física é proibido: uma falha entre as duas apagaria o único registro persistido que permite repetir a operação.

A ordem dos locks é a mesma da rechecagem (review → quarentena). Durante a exclusão, uploads para a mesma review aguardam a chamada ao storage terminar.

### Desfechos

| Desfecho | Storage | Quarentena |
| :--- | :--- | :--- |
| `INVALID_OBJECT_KEY` | intocado (nenhuma consulta nem lock) | intocada |
| `NOT_QUARANTINED` | intocado | inexistente ou alterada desde a leitura |
| `NOT_CONFIRMED` | intocado | permanece `OBSERVED` |
| `WITH_REFERENCE` | intocado | liberada |
| `PURGED` | `DELETED` ou `NOT_FOUND` | removida |
| `RETRYABLE_FAILURE` | `TRANSIENT_FAILURE` | mantida `CONFIRMED_ORPHAN`, para nova tentativa |
| `PERMANENT_FAILURE` | `PERMANENT_FAILURE` | mantida `CONFIRMED_ORPHAN`, para investigação |

Nenhuma falha altera `review_media`. A chave é validada de novo antes de derivar o `reviewId`.

### Janela de queda e retry

Se o processo cair (ou a transação falhar) depois da exclusão física e antes do commit, a linha `CONFIRMED_ORPHAN` permanece, porque o rollback a preserva, mas o objeto já não existe. A próxima execução refaz os locks e a consulta, recebe `NOT_FOUND` do storage e encerra a quarentena. Nenhum estado intermediário "DELETED" é necessário. Isso é coberto por teste de integração contra PostgreSQL e SeaweedFS reais.

### Concorrência

- **Upload em andamento:** a exclusão espera o lock da review, vê a referência após o commit do upload e não apaga. O teste de integração falha se o lock for removido.
- **Duas exclusões da mesma chave:** são serializadas pelos locks. A primeira chama o storage e remove a linha; a segunda não encontra mais `CONFIRMED_ORPHAN` e termina como `NOT_QUARANTINED`, sem chamar o storage.

## Separação do Outbox

A reconciliação e a quarentena são uma varredura direta, independente do Outbox (decisão do STEP 27.0). Não geram nem consomem mensagens de `outbox_messages`.

## Exposição

Os tipos de reconciliação carregam chaves internas do storage e são exclusivamente internos. Nenhum deles é exposto pela API pública, e o use case não aceita chaves vindas de usuário: o prefixo é fixo e o marcador precisa estar sob `reviews/`. O log técnico registra apenas contagens.
