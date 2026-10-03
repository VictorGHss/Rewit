# Reconciliação e GC de Storage de Mídia (STEP 28)

Este documento descreve o garbage collection (GC) do Object Storage de mídias (SeaweedFS via API S3): o contrato de listagem (28.1), a quarentena e a rechecagem (28.3), a exclusão física segura (28.4) e a operação agendada (28.5). A decisão arquitetural está resumida em [ADR-010](../decisions/ADR-010-storage-garbage-collection.md).

```text
SeaweedFS → ObjectStorageListingPort → ReconcileReviewMediaStorageUseCase → storage_object_quarantine
         → StorageQuarantineGracePolicy → rechecagem PostgreSQL → CONFIRMED_ORPHAN
         → exclusão física segura → ObjectStorageDeletionPort → SeaweedFS

StorageGcScheduler → RunStorageGcCycleUseCase → use cases acima
```

O GC vem **desligado por padrão**, não tem endpoint HTTP e não usa o Outbox.

## 1. Motivação

PostgreSQL e SeaweedFS não compartilham transação. Alguns caminhos podem deixar objetos sem linha correspondente em `review_media`:

- falha na compensação do upload: o objeto já foi gravado, o `INSERT` falhou e o delete de compensação também falhou;
- remoção de linhas fora do fluxo normal da aplicação, por exemplo o `ON DELETE CASCADE` de `reviews` em manutenção manual.

Esses objetos não são acessíveis pela API, mas ocupam storage. O GC os encontra e os remove com segurança.

O GC **não** remove objetos de mídias `REMOVED`. Uma linha `REMOVED` continua sendo referência. A remoção física dessas mídias continua sendo a do fluxo de exclusão de mídia (melhor esforço), e uma política de retenção específica para elas está fora do STEP 28.

## 2. Ownership e namespace

- O PostgreSQL (`review_media.object_key`) é a fonte de verdade das referências. Um objeto pertence à aplicação enquanto existir uma linha com sua chave, **em qualquer status**.
- O único prefixo gerenciado é `reviews/` (`ReviewMediaObjectKey.MANAGED_PREFIX`). O upload gera `reviews/{reviewId}/{mediaId}/image.{jpg|png}`, com UUIDs em minúsculas.
- Objetos fora de `reviews/` são ignorados, mesmo que a listagem os devolva. Chaves sob `reviews/` fora desse formato são contadas como **não reconhecidas** e nunca viram candidatas.
- O bucket é sempre o configurado em `rewit.minio.bucket-name`; nenhum input externo escolhe bucket, prefixo ou chave.

## 3. Enumeração e paginação

- `ObjectStorageListingPort.listObjects(prefix, startAfter, maxKeys)` é somente leitura e separada de `ObjectStoragePort`. `MinioStorageAdapter` a implementa com `prefix`, `start-after` e `max-keys` do S3 e não cria bucket.
- A página devolve `nextStartAfter`: a última chave lida quando a página veio cheia, ou `null` ao fim do prefixo. É o único cursor do sistema: sem estado no servidor, em ordem lexicográfica e retomável.
- `ReconcileReviewMediaStorageUseCase` cruza cada página com o banco numa única consulta (`object_key IN (...)`, sobre o índice único existente) e registra os candidatos daquela página na quarentena. A memória por ciclo fica limitada a `page-size × max-pages`.
- O marcador só é aceito se pertencer a `reviews/`. Uma listagem que não avança o marcador interrompe a execução.

| Situação na listagem | Resultado |
| :--- | :--- |
| Linha `ACTIVE` em `review_media` | protegido (`activeReferences`) |
| Linha `REMOVED` em `review_media` | protegido (`removedReferences`); **REMOVED ≠ sem referência** |
| Sem linha, chave no formato gerenciado | candidato observado (`OrphanCandidate`) |
| Fora de `reviews/`, formato não reconhecido ou chave repetida no ciclo | apenas contado |

## 4. Quarentena (`storage_object_quarantine`, migration V15)

- Uma linha por `object_key` (`uq_storage_object_quarantine_object_key`). Registra a observação de storage: não copia `review_media` nem tem FK para `reviews`, pois o objeto justamente pode não ter referência.
- `status`: `OBSERVED` (visto sem referência) ou `CONFIRMED_ORPHAN` (grace period cumprido e ausência de referência confirmada sob lock). Constraints garantem que só esses estados existem, que `last_observed_at >= first_observed_at` e que `confirmed_at` só existe em `CONFIRMED_ORPHAN`.
- Registro por upsert `ON CONFLICT (object_key)`: a chave nova cria a linha; a chave conhecida preserva `first_observed_at`, avança `last_observed_at` (sem regredir) e atualiza `last_modified_at` quando o storage o informa. Execuções repetidas ou concorrentes convergem para uma única linha.
- A quarentena vive no PostgreSQL: reiniciar o processo não a afeta.
- Se aparecer referência, a linha é encerrada. Uma observação futura sem referência cria uma linha nova e reinicia o grace period.
- Não existe estado "DELETED": após a exclusão física, a linha é removida.

## 5. Grace period

- `first_observed_at` é a primeira observação persistida sem referência e marca o início do grace period. Timestamps de `review_media` não são usados.
- `StorageQuarantineGracePolicy` é a única autoridade temporal. A operação monta `FixedStorageQuarantineGracePolicy` com `rewit.storage-gc.grace-period`, que é **obrigatório e não tem valor padrão**.
- O grace period cobre a janela em que o upload já gravou o objeto e ainda não commitou a linha: um objeto observado nesse intervalo é legítimo.

## 6. Rechecagem e proteção contra a corrida com o upload

`ReviewMediaService.uploadMedia` adquire `ReviewJpaRepository.findByIdForUpdate` (`PESSIMISTIC_WRITE` na linha de `reviews`) **antes** de gravar o objeto e o mantém até o commit da linha em `review_media`. Como toda chave contém o `reviewId` e cada upload gera um `mediaId` aleatório, uma transação que detém esse mesmo lock e não encontra referência sabe que não há upload em andamento para aquela chave.

`RecheckQuarantinedStorageObjectUseCase` → `resolveUnderCreationLock`, numa transação PostgreSQL sem nenhuma operação de storage:

1. lock da review dona da chave (`findByIdForUpdate`). Review inexistente dispensa o lock: nenhum upload consegue criar referência para ela;
2. lock da linha de quarentena, exigindo o `first_observed_at` avaliado pela política;
3. consulta a `review_media` pela chave.

| Desfecho | Significado | Quarentena |
| :--- | :--- | :--- |
| `NOT_QUARANTINED` | não há linha correspondente à observação avaliada | nenhum efeito |
| `GRACE_PERIOD_NOT_ELAPSED` | grace period em curso (o banco não é consultado) | nenhum efeito |
| `WITH_REFERENCE` | existe linha `ACTIVE` ou `REMOVED` | encerrada |
| `CONFIRMED_ORPHAN` | nenhuma referência sob o lock | `CONFIRMED_ORPHAN`, preservando o primeiro `confirmed_at` |

`CONFIRMED_ORPHAN` torna o objeto **elegível**, mas não autoriza a exclusão sozinho: o lock é liberado no commit, e a exclusão repete a consulta.

## 7. Exclusão física segura

### Contrato

`ObjectStorageDeletionPort.delete(key)` devolve `ObjectDeletionResult`, sem lançar erros de storage. `ObjectStoragePort` estende essa porta, então os chamadores existentes (compensação do upload e exclusão de mídia) não mudaram. `PurgeConfirmedOrphanStorageObjectUseCase` recebe **somente** `ObjectStorageDeletionPort`.

| Resultado | Origem (verificada contra o SeaweedFS 4.47) | Significado |
| :--- | :--- | :--- |
| `DELETED` | `statObject` e `removeObject` com sucesso | removido |
| `NOT_FOUND` | `NoSuchKey`/`ResourceNotFound` | **sucesso idempotente** |
| `PERMANENT_FAILURE` | `AccessDenied`, `InvalidAccessKeyId`, `SignatureDoesNotMatch`, `NoSuchBucket`; `InvalidKeyException`/`NoSuchAlgorithmException`; `IllegalArgumentException` | credencial/configuração/contrato |
| `TRANSIENT_FAILURE` | demais `ErrorResponseException`; `ServerException`, `IOException`, `InsufficientDataException`, `InternalException`, `InvalidResponseException`, `XmlParserException` | repetir é seguro |

O `statObject` existe porque o `DeleteObject` do S3 responde sucesso também para chave ausente. Exceções fora dessas categorias não são engolidas.

### Ordem das operações

PostgreSQL e SeaweedFS **não formam uma transação distribuída**. A exclusão mantém uma transação PostgreSQL com os locks ativos enquanto chama o storage, mas a exclusão no storage é externa: **rollback do PostgreSQL não a desfaz**. Em `purgeConfirmedOrphanUnderCreationLock`:

1. lock da review (o mesmo do upload e da rechecagem);
2. lock da linha de quarentena, exigindo `CONFIRMED_ORPHAN` e o `first_observed_at` lido antes;
3. nova consulta a `review_media`: se houver linha `ACTIVE` ou `REMOVED`, o storage não é tocado e a quarentena é encerrada;
4. exclusão no storage;
5. remoção da linha de quarentena, **somente** se o resultado for `DELETED` ou `NOT_FOUND`;
6. commit.

A linha de quarentena nunca é removida antes da exclusão física: uma falha entre as duas apagaria o único registro que permite repetir a operação. A ordem dos locks é sempre review → quarentena. O upload trava apenas a review, e a reconciliação trava apenas a linha da quarentena, então não há ciclo. Durante uma exclusão, uploads para a mesma review aguardam a chamada ao storage terminar.

| Desfecho | Storage | Quarentena |
| :--- | :--- | :--- |
| `INVALID_OBJECT_KEY` | intocado (sem consulta nem lock) | intocada |
| `NOT_QUARANTINED` | intocado | inexistente ou alterada |
| `NOT_CONFIRMED` | intocado | permanece `OBSERVED` |
| `WITH_REFERENCE` | intocado | encerrada |
| `PURGED` | `DELETED` ou `NOT_FOUND` | removida |
| `RETRYABLE_FAILURE` | `TRANSIENT_FAILURE` | mantida `CONFIRMED_ORPHAN` |
| `PERMANENT_FAILURE` | `PERMANENT_FAILURE` | mantida `CONFIRMED_ORPHAN`, para investigação |

`review_media` nunca é alterada pelo GC.

## 8. Idempotência e janela de queda

```text
exclusão no storage → processo cai → rollback do PostgreSQL → a quarentena continua existindo
→ próxima tentativa → NOT_FOUND → quarentena removida
```

Isso é idempotência operacional, não atomicidade entre banco e storage. Duas exclusões da mesma chave são serializadas pelos locks: a primeira remove, e a segunda termina como `NOT_QUARANTINED` sem chamar o storage.

## 9. Operação agendada

`StorageGcScheduler` (`@Scheduled`, fixedDelay) apenas chama `RunStorageGcCycleUseCase.runCycle()`. O orquestrador coordena os use cases acima, sem duplicar locks, rechecagem ou grace period:

1. adquire o lock global; se estiver ocupado, o ciclo é `SKIPPED_LOCKED`;
2. listagem e reconciliação, uma página por vez, com o marcador do item 3;
3. rechecagem das linhas `OBSERVED` mais antigas;
4. exclusão das `CONFIRMED_ORPHAN` mais antigas ou, em dry-run, apenas a contagem;
5. relatório (`StorageGcCycleReport`), métricas e um log de resumo.

- O marcador `nextStartAfter` fica em memória na instância entre ciclos e não é persistido: um reinício recomeça do início do prefixo, sem pular objetos. Um marcador recusado, ou uma listagem que não avança, também recomeça do início.
- Listagem incompleta (`PARTIAL`) nunca é tomada como prova de ausência de objetos: a segurança vem da consulta sob lock.
- O processamento é sequencial, sem `@Async` nem paralelismo de exclusão.
- Não há endpoint HTTP, CLI nem command runner. O disparo manual interno é a própria chamada `runCycle()` do bean, com a mesma configuração, modo, limites, lock e métricas do agendamento.

Status do ciclo: `COMPLETED`; `PARTIAL` (algum limite atingido); `TIMED_OUT` (`max-duration`, verificado entre etapas sem interromper uma chamada em curso); `ABORTED`; `FAILED`; `SKIPPED_LOCKED`.

### Múltiplas instâncias

O ciclo roda sob um advisory lock **de sessão** do PostgreSQL (`pg_try_advisory_lock`, chave exclusiva do GC), mantido por uma conexão dedicada:

- não espera: se outra instância detém o lock, o ciclo é ignorado;
- é liberado com `pg_advisory_unlock` ao fim, com sucesso ou falha. Se a liberação falhar, a conexão é descartada do pool; se o processo morrer, o PostgreSQL encerra a sessão e libera o lock;
- não envolve linhas de domínio: uploads e operações de usuário nunca o aguardam. Os locks por candidato continuam sendo a garantia de correção, e o lock global evita apenas a disputa pelas mesmas páginas.

## 10. Falhas

| Situação | Comportamento |
| :--- | :--- |
| Storage indisponível na listagem | `FAILED`; nenhuma rechecagem ou exclusão neste ciclo; o marcador é mantido |
| PostgreSQL indisponível (lock ou quarentena) | `FAILED`; nenhuma exclusão; quarentena preservada pelas transações |
| Timeout | `TIMED_OUT` entre etapas |
| Erro isolado em um candidato | contado em `candidateErrors`; os demais seguem; a quarentena do candidato não muda |
| `TRANSIENT_FAILURE` | candidato mantido; `max-consecutive-failures` falhas seguidas → `ABORTED` |
| `PERMANENT_FAILURE` | exclusões do ciclo param imediatamente (`ABORTED`); candidato mantido |

Cada linha é tentada no máximo uma vez por ciclo; o próximo ciclo tenta de novo.

## 11. Dry-run

O orquestrador em dry-run é construído **sem** o use case de exclusão, e a configuração nem obtém a porta de exclusão do storage, então não existe caminho até o storage delete. O dry-run lista, registra observações e recheca, mexendo só no PostgreSQL. A fase de exclusão apenas informa `wouldDelete`, que é um limite superior: a exclusão real repete a consulta sob os locks. O resumo do log diz "dry-run: nada foi removido", existe o contador `runs.dry_run`, e o timer tem a tag `mode`.

## 12. Configuração (`rewit.storage-gc`)

| Propriedade | Padrão | Regra |
| :--- | :--- | :--- |
| `enabled` | `false` | Desligado: nenhum componente do GC é criado (scheduler, orquestrador, lock, métricas, acesso de exclusão). Um aviso é registrado uma vez no boot. |
| `dry-run` | sem padrão no código; `application.yml`: `${STORAGE_GC_DRY_RUN:true}` | Obrigatório quando habilitado. O modo destrutivo exige `false` explícito. |
| `interval-ms` | — | Obrigatório, positivo. |
| `initial-delay-ms` | `60000` | Não negativo. |
| `page-size` | — | Obrigatório, de 1 a 1000 (teto do `max-keys` do S3). |
| `max-pages` | — | Obrigatório, de 1 a 10000: páginas por ciclo. |
| `max-candidates` | — | Obrigatório, de 1 a 10000: rechecagens por ciclo. |
| `max-deletes` | — | Obrigatório, de 1 a 10000: exclusões (ou contagens, em dry-run) por ciclo. |
| `grace-period` | — | Obrigatório, duração positiva. **Sem valor padrão.** |
| `max-duration` | — | Obrigatório, duração positiva: timeout do ciclo. |
| `max-consecutive-failures` | `3` | De 1 a 10000. |

- Com `enabled=true`, a configuração é validada no boot: a aplicação não sobe e a mensagem lista todas as violações.
- `enabled` e `dry-run` vêm das variáveis `STORAGE_GC_ENABLED` e `STORAGE_GC_DRY_RUN`. As demais usam o binding padrão do Spring, por exemplo `REWIT_STORAGE_GC_GRACE_PERIOD`, `REWIT_STORAGE_GC_INTERVAL_MS`, `REWIT_STORAGE_GC_PAGE_SIZE`, `REWIT_STORAGE_GC_MAX_PAGES`, `REWIT_STORAGE_GC_MAX_CANDIDATES`, `REWIT_STORAGE_GC_MAX_DELETES` e `REWIT_STORAGE_GC_MAX_DURATION`.
- Este repositório não define valores de produção. Valores de infraestrutura local nunca são padrões de produção.
- **`STORAGE_GC_DRY_RUN=false` apaga objetos fisicamente e de forma irreversível.** Não há restauração de objeto removido. Só use esse valor depois do rollout descrito no item 16.

## 13. Métricas (`rewit.storage_gc.*`)

Os counters são derivados do relatório do ciclo, sem tags e sem chaves:

- execuções: `runs.started`, `runs.completed`, `runs.partial`, `runs.timed_out`, `runs.aborted`, `runs.failed`, `runs.skipped` (lock ocupado), `runs.dry_run`;
- listagem e candidatos: `objects.listed`, `objects.ignored`, `candidates.observed`, `rechecks`, `candidates.grace_pending`, `candidates.eligible`, `candidates.protected`, `candidates.errors`;
- exclusões: `deletes.would_delete` (somente dry-run), `deletes.attempted`, `deletes.succeeded`, `deletes.already_absent`, `deletes.retryable_failure`, `deletes.permanent_failure`.

O timer `run.duration` tem a tag `mode=dry_run|destructive`. Os gauges `quarantine.observed` e `quarantine.confirmed` medem o backlog consultando o PostgreSQL. Com o GC desligado, nenhuma métrica do GC é registrada. As métricas do Outbox são separadas e não mudaram.

## 14. Logs

Por ciclo há um log de início e um resumo agregado (status, modo, duração, contagens). Os logs por candidato ficam em `debug`, exceto falhas de exclusão. Nunca são registrados object key, UUIDs da chave, endpoint, URL, credenciais, conteúdo ou mensagem bruta do SDK; erros aparecem como classificação, código S3 ou classe da exceção, e contagem.

## 15. Segurança e exposição

- Somente `CONFIRMED_ORPHAN`, rechecado sob os locks, chega ao storage delete. A chave é revalidada antes de derivar o `reviewId`.
- Nenhum tipo do GC é exposto pela API pública; não há endpoint, e o GC não aceita chave, prefixo ou bucket vindos de fora.
- Domínio e aplicação não importam o SDK do MinIO. Testes de fronteira verificam isso e também que o storage delete só é chamado pela compensação do upload, pela exclusão de mídia e por `PurgeConfirmedOrphanStorageObjectUseCase`.
- Relação com o Outbox: nenhuma. O GC é uma varredura direta (decisão do STEP 27.0); não gera nem consome `outbox_messages` e não compartilha métricas.

## 16. Rollout operacional seguro

1. **Estado inicial:** GC desligado (`STORAGE_GC_ENABLED=false`, o padrão).
2. **Primeira ativação, em dry-run:** `STORAGE_GC_ENABLED=true` com `STORAGE_GC_DRY_RUN=true`, um grace period definido pela operação e limites baixos (`page-size`, `max-pages`, `max-candidates`, `max-deletes`, `max-duration`). Em dry-run o GC só escreve na quarentena do PostgreSQL.
3. **Observação, por alguns ciclos:**
   - quantidade de candidatos (`candidates.observed`, `quarantine.observed`, `quarantine.confirmed`) e o que seria removido (`deletes.would_delete`);
   - idade dos candidatos e frequência de reobservação (`first_observed_at`/`last_observed_at` na quarentena);
   - proteções por referência (`candidates.protected`);
   - falhas e status dos ciclos (`runs.failed`, `runs.aborted`, `runs.partial`, `runs.timed_out`);
   - comportamento do storage (duração do ciclo, erros de listagem).
4. **Ativação destrutiva:** somente depois de definir explicitamente o grace period, os limites, a janela de execução (`interval-ms`, `initial-delay-ms`, `max-duration`), o monitoramento dos contadores `deletes.*` e `runs.*`, e o procedimento de retry.
5. **Rollback e retry:**
   - para interromper o GC, basta voltar a `STORAGE_GC_DRY_RUN=true` ou `STORAGE_GC_ENABLED=false`. Linhas de quarentena pendentes permanecem e são retomadas com segurança;
   - falhas deixam a quarentena para a próxima tentativa, e `NOT_FOUND` encerra a linha.
   - "Rollback" aqui é da camada PostgreSQL ou da configuração. **O sistema não restaura objetos fisicamente excluídos**; qualquer recuperação depende de backup do storage, fora do escopo do Rewit.
