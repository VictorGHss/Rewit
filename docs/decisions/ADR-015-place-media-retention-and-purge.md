# ADR-015: Política de Retenção e Expurgo Físico de Fotografias Moderadas de Locais



## Status

Aceito (Substitui e formaliza a consequência negativa 33 da ADR-010 para o namespace `places/`)



## Contexto

Na ADR-010 (item 33), foi estabelecido que objetos referenciados em registros lógicos `REMOVED` não seriam coletados genericamente, reservando a política de retenção e expurgo para uma decisão separada.

No catálogo de estabelecimentos físicos (`places/`), fotografias removidas por moderação ou pelo autor ocupam armazenamento físico indefinidamente se nenhum fluxo de purga for executado, gerando custos de armazenamento desnecessários e retenção de dados sensíveis/inadequados. Por outro lado, a exclusão física imediata impede recursos, contestações e restaurações legítimas por moderadores.



## Decisão

1. **Diferenciação estrita de ciclo de vida:**

   - `ACTIVE`: O objeto físico é preservado no Object Storage e servido publicamente.

   - `UNDER_REVIEW`: O objeto físico é preservado no Object Storage, inacessível pela API pública, e visível exclusivamente por moderadores autenticados via rota administrativa para tomada de decisão.

   - `REMOVED` (durante período de carência de 24 horas): O objeto físico é preservado no Object Storage em quarentena de segurança para possibilitar eventual reversão ou restauração administrativa autorizada (`restoreMediaByModerator`), desde que a remoção tenha sido efetuada por moderação.

   - `REMOVED` (após período de carência de 24 horas): O objeto torna-se elegível ao ciclo de expurgo físico executado pelo processo de Garbage Collection (GC) autorizado.

   - `PURGED` (`purged_at IS NOT NULL`): O registro da mídia permanece no PostgreSQL com `purged_at` preenchido e linha na tabela de auditoria preservada; o objeto físico não existe mais no Object Storage e qualquer tentativa de restauração é estritamente bloqueada (HTTP 409 `STORAGE_OBJECT_PURGED`).



2. **Período de Carência Explícito e Retenção Mínima de 24h:**

   - A data de remoção lógica é persistida de forma confiável na coluna `place_media.removed_at` no instante em que a transição para `REMOVED` ocorre.

   - O período de carência e retenção mínimo é de 24 horas (`PT24H`). Durante esse intervalo, o objeto físico entra em quarentena de segurança e não pode ser excluído por nenhum processo em segundo plano.



3. **Origem da Remoção e Proteção da Vontade do Autor e do Estabelecimento:**

   - A coluna `place_media.removal_source` categoriza a origem da remoção como:

     - `AUTHOR`: exclusão pelo próprio usuário que enviou a fotografia;

     - `BUSINESS_OWNER` / `PLACE_MANAGER`: remoção pelo responsável legal ou verificado do local comercial;

     - `MODERATOR`: remoção administrativa por moderação do sistema;

     - `LEGACY`: registros históricos anteriores à migração V34.

   - Mídias com `removal_source = 'AUTHOR'` representam direito de arrependimento e privacidade do autor e **nunca** podem ser restauradas por moderadores (retorno HTTP 409 com código `CANNOT_RESTORE_AUTHOR_REMOVED_MEDIA`).

   - Mídias com `removal_source IN ('BUSINESS_OWNER', 'PLACE_MANAGER')` representam decisão autônoma do responsável do local e **nunca** podem ser restauradas por moderadores (retorno HTTP 409 com código `CANNOT_RESTORE_PLACE_MANAGER_REMOVED_MEDIA`).

   - Mídias com `removal_source = 'LEGACY'` não possuem histórico de consentimento auditável completo e **nunca** podem ser restauradas por moderação (retorno HTTP 409 com código `CANNOT_RESTORE_LEGACY_REMOVED_MEDIA`).

   - Restaurações administrativas são permitidas exclusivamente para fotografias com `removal_source = 'MODERATOR'`, desde que o arquivo físico permaneça presente no Object Storage e a cota do local não exceda o limite de 5 mídias ativas.



4. **Trilha de Auditoria Imutável e Proteção Append-Only (V34 e V35):**
   - Há separação estrita entre:
     - O estado operacional corrente da mídia mantido em `place_media` (`removed_at`, `removal_source`, `status`), que reflete a situação presente da entidade no catálogo;
     - O histórico probatório e irrevogável gravado na tabela append-only `place_media_moderation_audit_logs`. Esta tabela é protegida no banco PostgreSQL por triggers que impedem `UPDATE`, `DELETE` e `TRUNCATE` (fortalecimento implementado na migration V34 e comprovado contra a role de execução).
   - Todas as mutações de moderação e remoções públicas/autor/gerente são acompanhadas de registro atômico e obrigatório na auditoria, sob a mesma transação de banco de dados. Falha ao persistir o log de auditoria reverte atomicamente todas as alterações em mídias, capas e denúncias.
   - **Distinção entre decisões explícitas e resoluções automáticas (V35):**
     - O campo `actor_type` (`USER`, `SYSTEM`) e a ação `AUTO_RESOLVE_REPORT` diferenciam decisões deliberadas tomadas por operadores de transições automatizadas do sistema.
     - Quando uma denúncia é aceita (`ACCEPT_REPORT`), a auditoria da denúncia principal é registrada com a decisão explícita do moderador (`actor_type = 'USER'`, `actor_user_id = moderatorId`). As denúncias irmãs pendentes resolvidas em cascata recebem eventos individuais com ação `AUTO_RESOLVE_REPORT` e ator `SYSTEM` (`actor_user_id IS NULL`), preservando o `report_id` inequívoco de cada denúncia sem atribuir falsamente decisões deliberadas individuais aos operadores.
     - Da mesma forma, quando uma mídia é removida pelo autor ou pelo responsável do local e possui denúncias pendentes, estas são encerradas com `AUTO_RESOLVE_REPORT` atribuído ao `SYSTEM`, sem associar falsamente os autores ou proprietários ao papel de julgadores de denúncias de moderação.
   - **Quarentena automática auditada (`AUTO_QUARANTINE`):** Quando o limiar de 3 denúncias pendentes é atingido e a mídia transiciona para `UNDER_REVIEW`, um log de auditoria com a ação `AUTO_QUARANTINE` é registrado transacionalmente no banco de dados como ação do `SYSTEM` (`actor_user_id IS NULL`), não imputando a ação ao terceiro denunciante.
   - **Rejeição de denúncia sem reativação implícita:** A rejeição de uma denúncia (`REJECT`) **nunca** reativa automaticamente a mídia para `ACTIVE`. A mídia permanece em seu estado atual (`UNDER_REVIEW` ou removida); restaurações exigem comando explícito e auditado (`RESTORE`).



5. **Garbage Collection (GC) Desligado e Dry-Run por Padrão:**

   - O orquestrador de expurgo físico no Object Storage permanece estritamente desativado por padrão (`rewit.storage-gc.enabled=false`).

   - Quando ativado em ambientes controlados, o GC deve iniciar em modo dry-run, reportando em logs/métricas os candidatos elegíveis sem emitir comandos de exclusão física destrutiva ao Object Storage até validação explícita.



6. **Tratamento de Registros Legados e Backfill Conservador (V33, V35 e V36):**
   - A migration V33 preencheu `removed_at` histórico com base no instante da migração (`NOW()`) para mídias já com status `REMOVED`.
   - Para evitar a atribuição indevida de `MODERATOR` a mídias sem comprovação probatória e assegurar que evidências anteriores à última restauração não corrompam o estado atual, a migration V36 realiza a reclassificação conservadora e determinística:
     - Mídias com registro explícito de `REMOVE` ou `ACCEPT_REPORT` por moderador na tabela de auditoria, ocorridos estritamente após a última restauração (`RESTORE` ou `RELEASE_REVIEW`), são classificadas com `removal_source = 'MODERATOR'`.
     - Mídias com registro de remoção pelo autor ou responsável ocorridos após a última restauração permanecem como `AUTHOR` ou `BUSINESS_OWNER` / `PLACE_MANAGER`.
     - Mídias removidas sem comprovação de ato deliberado posterior à última restauração (incluindo mídias que possuam apenas `REJECT_REPORT` ou sem nenhum log de remoção no ciclo corrente) são classificadas estritamente como `LEGACY`.
   - Mídias com `removal_source = 'LEGACY'` não possuem histórico de consentimento auditável completo e **nunca** podem ser restauradas por moderação (retorno HTTP 409 com código `CANNOT_RESTORE_LEGACY_REMOVED_MEDIA`).

7. **Liberação Transacional de Análise (RELEASE_REVIEW) e Resoluções Automáticas (AU-1, AU-2, RS-1):**
   - **Fluxo de Liberação (R-1):** Fotografias em estado `UNDER_REVIEW` cujas denúncias foram todas rejeitadas ou encerradas sem deferimento não utilizam o endpoint de restauração (`/restore`, exclusivo para `REMOVED`). Em vez disso, são liberadas através do endpoint administrativo dedicado `POST /admin/place-media/{placeId}/{mediaId}/release-review`. A liberação transaciona o status para `ACTIVE`, reavalia a elegibilidade de capa e registra o evento `RELEASE_REVIEW` na trilha de auditoria.
   - **Classificação Estrita de Remoções (RS-1):** A origem da remoção é determinada pela autoridade e rota operacional exercida:
     1. Autor removendo a própria fotografia: `RemovalSource.AUTHOR` (inviolável, não restaurável por moderação).
     2. Moderação administrativa (via endpoint `/admin/place-media/{placeId}/{mediaId}/remove` ou usuário com role `MODERATOR`/`ADMIN` exercendo remoção em foto de terceiro): `RemovalSource.MODERATOR`. Usuários com papéis acumulados exercendo moderação não sofrem degradação para `PLACE_MANAGER` ou `BUSINESS_OWNER`.
     3. Representante comercial aprovado: `RemovalSource.BUSINESS_OWNER`.
     4. Criador/responsável direto do local: `RemovalSource.PLACE_MANAGER`.
   - **Status `AUTO_RESOLVED` para Denúncias (AU-1, AU-2):** Denúncias encerradas automaticamente em virtude da exclusão da fotografia recebem o status inequívoco `AUTO_RESOLVED`, com `decided_by_user_id = NULL`. O filtro administrativo `/api/v1/admin/place-media/reports?status=AUTO_RESOLVED` é suportado de ponta a ponta para inspeção e auditoria.

8. **Isolamento de Credenciais de Banco, Runtime e Defesa Append-Only (AO-1, AO-2, SEC-1):**
   - A execução da aplicação em runtime e das suítes de teste de integração opera sob a role de aplicação restrita `rewit_app`, sem privilégios de superuser e sem ownership sobre as tabelas.
   - O schema e as migrações pertencem exclusivamente à role de migração `rewit_user` (utilizada pelo Flyway).
   - Como non-owner, o PostgreSQL rejeita nativamente comandos DDL (como `ALTER TABLE ... DISABLE TRIGGER` ou `DROP TABLE`) disparados pela aplicação.
   - Comandos `UPDATE`, `DELETE` e `TRUNCATE` sobre as tabelas de auditoria (`place_media_moderation_audit_logs`, `moderation_audit_logs`, `discussion_moderation_audit_logs`, `business_response_moderation_audit_logs`) e sobre `flyway_schema_history` são categoricamente vedados para `rewit_app`.
   - **Provisionamento e Rotação de Credenciais Externas (SEC-1):** O provisionamento operacional de `rewit_app` deve ser efetuado através de segredos externos fornecidos pelo ambiente de deploy (`APP_DB_PASSWORD`), sem senhas fixas em migrations. Ambientes com volumes persistentes existentes devem executar a rotação de senhas através de scripts operacionais dedicados (`ALTER ROLE rewit_app WITH PASSWORD '...'`).
   - **Bloqueio de Migração V36:** A verificação independente demonstrou que bases V35 históricas com denúncias resolvidas sem operador humano associado violam a check constraint da V36 na aplicação da migration. Qualquer alteração de dados históricos ou criação de V37 deve aguardar a reconciliação e decisão explícita do mantenedor sobre a V36.

9. **Configuração Fail-Closed de Testes e Isolamento de Infraestrutura (A-1b, A-1c):**
   - Todos os testes de integração operam exclusivamente contra serviços descartáveis em portas dedicadas (PostgreSQL 55432, Redis 56379, MinIO/SeaweedFS 58333).
   - O validador `TestInfrastructureSafetyValidator` analisa as URLs JDBC PostgreSQL e nós de Redis (standalone, cluster e sentinel), rejeitando URLs sem porta explícita, portas padrão (5432, 5433, 6379, 8333) e variáveis genéricas de ambiente de desenvolvimento.

10. **Persistência Segura de Operações Pendentes Mobile (MB-1c, MB-1d):**
    - A recuperação de cadastros manuais com falha de foto é gerenciada pela `PendingPlaceRegistrationStore`, indexada estritamente pelo `userId` autenticado.
    - O seletor textual `TargetSearchSelectorWidget` recebe a identidade através de `TargetItemInputWidget` e `ReviewCreateScreen`, impedindo que trocas de conta vazem estados ou fotos entre usuários distintos.
    - Se o arquivo físico desaparecer do armazenamento local, a retomada alerta o usuário e permite capturar uma nova fotografia mantendo o local já criado (sem criar duplicatas nem erro 409).

11. **Ordem de Locks e Revalidação Pessimista:**

    - Antes de efetuar o expurgo físico, o ciclo de GC adquire os locks pessimistas na ordem global:

      1. Local (`places`);

      2. Mídia (`place_media`).

    - Sob lock, o GC revalida que:

      - O status permanece `REMOVED`;

      - `removed_at` somado a 24 horas é anterior ao instante atual (`now()`);

      - `purged_at` ainda é nulo;

      - Não há nenhuma transação de restauração ou concorrência pendente.

12. **Exclusão Física Idempotente e Registro Atômico:**

    - A exclusão física no Object Storage é realizada exclusivamente pela porta autorizada de storage. `NOT_FOUND` no storage é tratado como idempotência com sucesso.

    - Imediatamente após a confirmação da ausência do objeto físico no storage, `purged_at` é gravado no banco de dados como `now()`.

    - A linha de metadados da mídia e o log de auditoria nunca são deletados, garantindo rastreabilidade legal e integridade relacional.



## Consequências

### Positivas:

- Eliminação física definitiva de conteúdo impróprio ou removido após a janela de segurança de 24 horas e quarentena explícita.

- Respeito inviolável à exclusão pelo autor: fotos deletadas pelo usuário não podem ser reativadas por terceiros.

- Moderadores dispõem de 24 horas para reverter erros operacionais antes da destruição física irreversível do arquivo.

- Impossibilidade de restauração de ponteiros órfãos para objetos já expurgados.

- Trilha de auditoria integralmente preservada, append-only e protegida por constraints e triggers no PostgreSQL.

- Riscos de exclusão acidental mitigados por GC desligado e em dry-run por padrão.



### Negativas / Riscos:

- Após 24 horas da remoção, a fotografia expurgada não pode ser recuperada sob nenhuma circunstância; novo upload pelo usuário é exigido caso necessário.

- A purga física envolve chamada de rede ao Object Storage, exigindo timeout configurado e reprocessamento idempotente em caso de falha transitória de conectividade.
