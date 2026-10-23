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
   - A V36 (canônica, S3-A.11) recalcula `removal_source` de toda mídia `REMOVED` exclusivamente a partir de evidência causal inequívoca; sem ela, a origem é `LEGACY`:
     - Só contam eventos do ciclo atual, a partir da última restauração/liberação (`RESTORE`, `RELEASE_REVIEW`); empate de timestamp com a restauração é ambíguo.
     - Candidatos: remoções diretas (`REMOVE`, `AUTHOR_REMOVE`, `PLACE_MANAGER_REMOVE`, `BUSINESS_OWNER_REMOVE`) e `ACCEPT_REPORT`. `AUTO_RESOLVE_REPORT`, `AUTO_QUARANTINE` e `REJECT_REPORT` não demonstram remoção humana.
     - A causa é o **primeiro** candidato do ciclo (remoções posteriores são no-ops no serviço). Se mais de um candidato tem o menor timestamp, não há sequência confiável — o UUID aleatório nunca é usado como desempate.
     - `removed_at` precisa ser coerente com o evento: posterior à restauração e não posterior ao evento.
     - `ACCEPT_REPORT` só prova remoção por moderador quando a mídia ainda não estava removida no aceite: a denúncia vinculada está `ACCEPTED` e seu `decided_at` (gravado antes da remoção na mesma transação) não é posterior a `removed_at`. Uma remoção não auditada anterior ao aceite fica `LEGACY`.
     - `REMOVE` genérico (versões anteriores) só é atribuído quando o papel do ator é inequívoco (moderador que não é autor nem criador; autor ou criador sem papel de moderação).
   - Mídias com `removal_source = 'LEGACY'` não possuem histórico de consentimento auditável completo e **nunca** podem ser restauradas por moderação (retorno HTTP 409 com código `CANNOT_RESTORE_LEGACY_REMOVED_MEDIA`).

7. **Liberação Transacional de Análise (RELEASE_REVIEW) e Resoluções Automáticas (AU-1, AU-2, RS-1):**
   - **Fluxo de Liberação (R-1):** Fotografias em estado `UNDER_REVIEW` cujas denúncias foram todas rejeitadas ou encerradas sem deferimento não utilizam o endpoint de restauração (`/restore`, exclusivo para `REMOVED`). Em vez disso, são liberadas através do endpoint administrativo dedicado `POST /admin/place-media/{placeId}/{mediaId}/release-review`. A liberação transaciona o status para `ACTIVE`, reavalia a elegibilidade de capa e registra o evento `RELEASE_REVIEW` na trilha de auditoria.
   - **Classificação Estrita de Remoções (RS-1):** A origem da remoção é determinada pela autoridade e rota operacional exercida:
     1. Autor removendo a própria fotografia: `RemovalSource.AUTHOR` (inviolável, não restaurável por moderação).
     2. Moderação administrativa (via endpoint `/admin/place-media/{placeId}/{mediaId}/remove` ou usuário com role `MODERATOR`/`ADMIN` exercendo remoção em foto de terceiro): `RemovalSource.MODERATOR`. Usuários com papéis acumulados exercendo moderação não sofrem degradação para `PLACE_MANAGER` ou `BUSINESS_OWNER`.
     3. Representante comercial aprovado: `RemovalSource.BUSINESS_OWNER`.
     4. Criador/responsável direto do local: `RemovalSource.PLACE_MANAGER`.
   - **Status `AUTO_RESOLVED` para Denúncias (AU-1, AU-2):** Denúncias encerradas automaticamente em virtude da exclusão da fotografia recebem o status inequívoco `AUTO_RESOLVED`, com `decided_by_user_id = NULL`. O filtro administrativo `/api/v1/admin/place-media/reports?status=AUTO_RESOLVED` é suportado de ponta a ponta para inspeção e auditoria.
   - **Decisões históricas e proveniência (LEG-2, AU-3):** a V36 só converte uma denúncia para `AUTO_RESOLVED` quando há evento `AUTO_RESOLVE_REPORT`/`SYSTEM` com o mesmo `report_id` e nenhuma decisão humana registrada para ela. Uma decisão `ACCEPTED`/`REJECTED` é humana validada apenas se o decisor registrado for o ator `USER` do evento correspondente e não houver evento automático contraditório; qualquer outra é marcada `is_legacy_decision = TRUE`, preservando (sem verificar) o decisor eventualmente registrado — nenhum moderador é inventado e a trilha não é alterada. A API administrativa expõe `legacyDecision` e `decisionOrigin` (`MODERATOR`, `SYSTEM`, `LEGACY`), aceita o filtro `?decisionOrigin=` e só preenche `decidedByUserId` para decisão humana validada; o painel administrativo apresenta decisões legadas como "sem moderador verificável".

8. **Isolamento de Credenciais de Banco, Runtime e Defesa Append-Only (AO-1, AO-2, SEC-1, LEG-1, S3-A.12, S3-A.13):**
   - A execução da aplicação em runtime e das suítes de teste de integração opera sob a role de aplicação restrita `rewit_app`, sem privilégios de superuser e sem ownership sobre as tabelas.
   - O schema e as migrações pertencem exclusivamente à role de migração (utilizada pelo Flyway). Como non-owner, o PostgreSQL rejeita nativamente comandos DDL (como `ALTER TABLE ... DISABLE TRIGGER` ou `DROP TABLE`) disparados pela aplicação.
   - Comandos `UPDATE`, `DELETE` e `TRUNCATE` sobre as quatro trilhas (`place_media_moderation_audit_logs`, `moderation_audit_logs`, `discussion_moderation_audit_logs`, `business_response_moderation_audit_logs`) e sobre `flyway_schema_history` são vedados para `rewit_app` por privilégio e, nas trilhas, também por triggers de `UPDATE`, `DELETE` e `TRUNCATE` válidos para qualquer role.
   - **Trilhas desacopladas das entidades operacionais (AO-2):** as FKs `ON DELETE CASCADE` de `review_id`, `discussion_id` e `response_id` eram incompatíveis com a retenção (apagar avaliação, comentário ou resposta apagava a auditoria) e foram removidas na V36. Os identificadores permanecem como referência histórica, validados na inserção por trigger com `FOR KEY SHARE` (equivalente à FK), e as trilhas de comentários e respostas gravam snapshot do contexto (`review_id`; `business_account_id` nas respostas). Registros anteriores à V36 mantêm o snapshot nulo, pois preenchê-lo exigiria `UPDATE` no histórico. A FK do moderador para `users` continua `RESTRICT`. A V36 aborta se restar FK que cascateie ou dependa de entidade apagável, se faltar algum guard ou se `rewit_app` mantiver privilégio de mutação. A trilha de fotografias, que já nasceu sem FK, passou a verificar na inserção (V37, `trg_bind_place_media_audit_reference`) que a fotografia existe, que o local gravado é o dela e que a denúncia citada é da mesma fotografia; `ACCEPT_REPORT`, `REJECT_REPORT` e `AUTO_RESOLVE_REPORT` exigem a denúncia. Registros anteriores não são revalidados.
   - **Decisões legadas fora do alcance do runtime (LEG-1):** `is_legacy_decision` é condição histórica fixada pela V36. O privilégio de tabela em `place_media_reports` foi substituído por privilégios de coluna que a excluem, o mapeamento JPA é somente leitura e o trigger `trg_guard_place_media_report_decision` (válido para qualquer role) impede inserir ou alterar decisões legadas e exige moderador/administrador existente em toda decisão humana nova. Uma correção futura de dados legados precisa desabilitar o trigger explicitamente, o que exige ser owner da tabela.
   - **Funções de trigger imunes a tabelas temporárias (P2-1):** as funções que validam referências e decisões consultam as tabelas permanentes qualificadas (`public.`), e todas as funções de trigger do schema (exceto as de extensões) têm `search_path = pg_catalog, public, pg_temp`. Sem isso, uma sessão de `rewit_app` podia criar tabelas temporárias homônimas e forjar referências, snapshots ou moderadores. `TEMPORARY` não foi revogado de `PUBLIC`: a qualificação e o `search_path` bastam e a revogação global afetaria outros consumidores do banco.
   - **Decisões finais e denúncias (P2-2, S3-A.13):** denúncias nascem `PENDING` e sem campos de decisão (V37); a decisão é sempre uma transição. Denúncias `ACCEPTED`, `REJECTED` ou `AUTO_RESOLVED` têm `status`, `decided_by_user_id`, `decided_at`, `decision_notes`, `reason` e `detail` imutáveis (o produto não reabre denúncias; um novo ciclo é uma nova denúncia); updates sem mudança efetiva, como os do Hibernate, passam. `rewit_app` não tem `DELETE` em `place_media_reports` nem nas entidades das quais elas cascateiam.
   - **Papéis somente por operação administrativa (P2-2):** nenhum fluxo da aplicação altera `users.role`; o trigger `trg_guard_user_role_assignment` recusa, para qualquer role que não seja owner da tabela ou superuser, criar usuário com papel diferente de `USER` ou alterar papel. Assim o runtime não consegue promover um usuário a `MODERATOR` para satisfazer a checagem de moderador das decisões.
   - **DELETE do runtime por lista explícita (P3-1, S3-A.13):** usuários, locais, alvos, mídias, avaliações, comentários, respostas e denúncias usam exclusão lógica; exclusões físicas são administrativas (e as cascatas de FK executam com o owner). A V37 revoga `DELETE` de todas as tabelas e o concede só às 16 com exclusão física legítima no código (purge de contas, sessões, outbox, seguidores, reações, ofertas, horários, comodidades, referências externas, quarentena do GC e as invalidações por troca de titular da V28/V29, executadas pelo runtime). Nenhuma cascata a partir delas alcança tabela fora da lista. `spatial_ref_sys` e as views do PostGIS ficam somente leitura (apagar o SRID 4326 derrubava toda consulta geográfica), e os privilégios padrão da role de migração não dão escrita ao runtime: a migration que cria uma tabela concede explicitamente `INSERT`/`UPDATE`/`DELETE`.
   - **Verificação reutilizável (S3-A.13):** `rewit_assert_runtime_privileges()` (V37, sem `EXECUTE` para o runtime) aborta se `rewit_app` tiver atributos administrativos ou memberships, `DELETE` fora da lista, `TRUNCATE`/`REFERENCES`/`TRIGGER`, escrita em auditoria, extensões, `flyway_schema_history` ou `place_media_reports` (fora das colunas permitidas), cascata acionável, privilégio padrão de escrita em tabelas de qualquer role, guard ausente ou função de trigger sem `search_path` fixo. A V37 termina chamando-a; migrations futuras que mudem privilégios devem fazer o mesmo e, se uma tabela nova precisar de `DELETE`, atualizar a lista da função.
   - **Limites da role compartilhada:** o banco garante a integridade da linha (estados, transições, referências reais, snapshots derivados e imutabilidade), mas não a autorização da aplicação nem a autenticidade do evento: com uma única credencial de runtime ele não distingue qual usuário autenticado age. Quem detém essa credencial pode gravar uma decisão citando um moderador existente, ou um evento de auditoria para entidades reais. Uma garantia completa exigiria separar a credencial de moderação (ex.: API administrativa implantada à parte com role própria, e `rewit_app` sem `UPDATE` nas colunas de decisão) ou decisões assinadas por uma identidade fora do runtime; variáveis de sessão ou funções `SECURITY DEFINER` chamadas pelo runtime não resolvem, porque o próprio runtime as controla.
   - **F1 — tomada de conta ADMIN com a credencial de runtime (auditoria independente end-to-end, pós-S3-A.13):** a mesma limitação alcança a autenticação. Os mecanismos de credencial não dependem de nenhum segredo fora do banco, e duas sequências foram confirmadas:
     1. **Senha:** `rewit_app` altera `users.password_hash` de uma conta `ADMIN` para um hash Argon2id de senha conhecida; o login pela API com essa senha é aceito.
     2. **Sessão de refresh:** `rewit_app` insere em `auth_sessions` uma sessão para um token escolhido, gravando em `token_hash` o seu SHA-256 (sem segredo de servidor); `POST /api/v1/auth/refresh` com esse token emite um access token válido.
     - **Evidência:** as duas sequências foram executadas em ambiente descartável contra controllers, filtros e endpoints reais, e a API confirmou a autoridade administrativa obtida por meio de uma operação restrita a `ADMIN`.
     - **Pré-condição:** somente a credencial SQL de runtime. O ataque não exige sessão anterior, outro segredo (como a chave de assinatura JWT) nem acesso adicional. O privilégio mínimo do S3-A.13 não o bloqueia, porque são as mesmas escritas que o runtime faz legitimamente (troca de senha, login e rotação de sessão), e o guard `trg_guard_user_role_assignment` impede promover papéis, não assumir uma conta que já é `ADMIN`.
     - **Origem:** a vulnerabilidade já existia na `main`; não é regressão introduzida pelo S3-A.13.
     - **Classificação reportada pela auditoria: P2.** O impacto é alto (autoridade administrativa completa pela API, incluindo moderação), mas a pré-condição é a posse da credencial de runtime, que já é um comprometimento grave e não está ao alcance de um cliente da API. F1 converte esse comprometimento do banco em identidade autenticada na aplicação.
   - **F1 e F2 — riscos arquiteturais da credencial compartilhada:** F2 é a limitação descrita em **Limites da role compartilhada** (a credencial de runtime grava decisões e eventos de auditoria atribuídos a moderadores existentes); F1 é a mesma causa aplicada à autenticação. Os dois decorrem de o runtime ser, para o banco, uma única identidade com escrita sobre os dados de identidade, sessão e moderação. As garantias deste ADR sobre as trilhas (append-only, referências verificadas, decisões finais imutáveis) são de integridade estrutural; elas não provam a autenticidade de quem realizou uma ação, e com F1 nem mesmo a identidade autenticada é prova suficiente.
     - **Mitigação obrigatória antes da produção.** Alternativas já discutidas, não exclusivas entre si:
       - segredo de servidor mantido fora do banco nos mecanismos de autenticação (por exemplo, pepper/HMAC no hash de senha e HMAC com chave do servidor no hash do refresh token), de modo que um valor gravado pelo runtime sem o segredo não autentique;
       - separação das operações de autenticação e de moderação em credenciais ou serviços próprios, retirando de `rewit_app` a escrita sobre `password_hash`, `auth_sessions` e as colunas de decisão.
     - **Situação:** risco conhecido e registrado. Não há aceite do mantenedor para F1 nem para F2; enquanto não houver confirmação explícita, nenhum dos dois deve ser tratado como aceito. Um eventual aceite temporário para desenvolvimento, em bancos descartáveis, não autoriza produção: a autorização para produção depende da mitigação implementada e verificada.
   - **Ressalvas F3 a F7:** as demais ressalvas da mesma auditoria permanecem abertas no backlog; este registro não as corrige nem altera sua situação.
   - **Owner único do schema (S3-A.13):** a V36 verifica, antes de qualquer DDL, que a role de migração é owner (ou herda o owner) de todas as tabelas e funções de trigger do schema `public`, fora as de extensões; caso contrário aborta citando objeto e owner, sem aplicar nada. Superuser ou membro da role owner atendem à pré-condição.
   - **Provisionamento e Rotação de Credenciais Externas (SEC-1, SEC-2, SEC-3):** `rewit_app` é provisionada por `infrastructure/postgres/provision-runtime-role.sql`, executado pelo init do container (`init/02-create-app-role.sh`) e reutilizável para rotação (`docker compose exec postgres /docker-entrypoint-initdb.d/02-create-app-role.sh`). A senha (`APP_DB_PASSWORD`, obrigatória e sem valor padrão) segue pela entrada padrão até o `\password` do psql, que envia ao servidor apenas o verificador SCRAM: não há concatenação em SQL nem senha em argumentos ou logs. O script remove atributos privilegiados e memberships, recusa ownership e não concede privilégios de tabela (responsabilidade exclusiva das migrations). O nome da role é fixo (`rewit_app`): é a role que recebe os privilégios das migrations, e o provisionamento recusa outro `APP_DB_USER` já na inicialização (P3-2). O teste `infrastructure/postgres/tests/provisioning-test.sh` reproduz a inicialização a partir de um clone limpo com `core.autocrlf=true`; `.gitattributes` força LF nos scripts.

9. **Configuração Fail-Closed de Testes e Isolamento de Infraestrutura (A-1b, A-1c):**
   - Todos os testes de integração usam exclusivamente infraestrutura descartável declarada por variáveis `TEST_*` explícitas (ver `docs/development/local-setup.md`), sem valores padrão nem fallback para variáveis de desenvolvimento; `TestInfrastructureConfig` é a fonte única para testes fora do contexto Spring.
   - O validador `TestInfrastructureSafetyValidator` analisa as URLs JDBC PostgreSQL, nós de Redis (standalone, cluster e sentinel) e o endpoint S3, rejeitando ausência de configuração, URLs sem porta explícita e portas compartilhadas (5432, 5433, 6379, 8333) antes de qualquer conexão.
   - O teste de upgrade de migrations cria o próprio database descartável (nome aleatório prefixado e marcador em `COMMENT ON DATABASE`) e só o remove depois de confirmar nome e marcador; não há `flyway.clean()`.

10. **Persistência Segura de Operações Pendentes Mobile (MB-1c, MB-1d):**
    - `HomeScreen` e `AppRouter` repassam `authNotifier` e o `PendingPlaceRegistrationStore` a todos os caminhos de `ReviewCreateScreen` e `SearchScreen`; `main.dart` injeta a persistência no armazenamento seguro do dispositivo.
    - O registro é mínimo e por usuário autenticado: uma operação por local canônico (`place.id`) com a chave UUID v4 do upload; a leitura descarta registros de outra conta ou corrompidos. Leituras e gravações assíncronas da sessão anterior não restauram estado na conta atual, e os avisos de pendência são fechados na troca de sessão.
    - A operação é gravada pelo próprio sheet de cadastro logo depois de o servidor confirmar o local e antes do upload (S3-A.13), sob a conta capturada na abertura, sem depender de a tela chamadora continuar montada, e só sai do store quando o envio conclui: o encerramento do processo durante o upload, ou um 401 que expira a sessão e desmonta a Home, não perde a retomada, e a conta que entrar depois não a recebe (P2-3). Resta a janela entre a resposta do `createPlace` e a gravação local.
    - O store tem uma fila por conta, com prazo por operação (10 s): uma escrita travada não bloqueia outra conta; na mesma conta, operações que vencem o prazo na espera falham e nunca executam depois, de modo que uma escrita atrasada não é seguida fora de ordem por outra e um `clear` concluído não é desfeito por um `save` anterior. Leituras vencidas não exibem pendências (os dados não são alterados).
    - Retomar reaproveita o local já criado (sem novo `createPlace`) e a mesma chave em todos os retries; uma nova fotografia é nova operação lógica de upload. Se o arquivo local desaparecer, a operação retorna como recuperação indisponível para o MESMO local, exigindo nova foto.

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

- As migrations V16 e V36 foram canonicalizadas (S3-A.11, autorizado pelo mantenedor antes de qualquer ambiente produtivo). Os checksums Flyway passaram a ser V16 = `-52031498` e V36 = `-783562037` (V36 corrigida novamente no S3-A.12 e no S3-A.13, pré-condição de owner, enquanto ainda exclusiva da branch de trabalho, sem publicação ou integração); a V37 (`-530091290`) é nova; bancos criados a partir das versões anteriores falham na validação do Flyway e precisam ser recriados (ambiente de desenvolvimento) ou reconciliados explicitamente pelo processo autorizado (`flyway repair` somente após conferir que o schema corresponde à versão canônica).

- Com a credencial de runtime compartilhada, F1 (tomada de conta `ADMIN` por escrita direta em `password_hash` ou `auth_sessions`) e F2 (decisões e auditoria atribuídas a moderadores sem prova de autoria) continuam abertos. A integridade das trilhas não equivale à autenticidade do ator, e a mitigação descrita no item 8 é obrigatória antes da produção.

- Sem as FKs em cascata, a exclusão física (não prevista pelo domínio) de avaliações, comentários ou respostas deixa as auditorias com identificadores sem entidade correspondente; o contexto mínimo para investigação fica nos snapshots gravados na inserção.
