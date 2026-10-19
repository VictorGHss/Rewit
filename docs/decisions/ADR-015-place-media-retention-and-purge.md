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

3. **Origem da Remoção e Proteção da Vontade do Autor:**
   - A coluna `place_media.removal_source` categoriza a origem da remoção como `AUTHOR` (exclusão pelo próprio usuário que enviou a foto) ou `MODERATOR` (remoção administrativa após deferimento de denúncia ou ação direta da moderação).
   - Mídias com `removal_source = 'AUTHOR'` representam direito de arrependimento e privacidade do autor e **nunca** podem ser restauradas por moderadores (retorno HTTP 409 com código `CANNOT_RESTORE_AUTHOR_REMOVED_MEDIA`).

4. **Trilha de Auditoria Imutável vs Estado Lógico Corrente:**
   - Há separação estrita entre:
     - O estado operacional corrente da mídia mantido em `place_media` (`removed_at`, `removal_source`, `status`), que reflete a situação presente da entidade no catálogo;
     - O histórico probatório e irrevogável gravado na tabela append-only `place_media_moderation_audit_logs`. Esta tabela é protegida no banco por triggers contra `UPDATE` e `DELETE`, registrando permanentemente o identificador da denúncia (`report_id`), o motivo real informado e os metadados do autor de cada ação.
   - Todas as mutações de moderação e remoções públicas/autor são acompanhadas de registro atômico e obrigatório na auditoria, sob a mesma transação de banco de dados.

5. **Garbage Collection (GC) Desligado e Dry-Run por Padrão:**
   - O orquestrador de expurgo físico no Object Storage permanece estritamente desativado por padrão (`rewit.storage-gc.enabled=false`).
   - Quando ativado em ambientes controlados, o GC deve iniciar em modo dry-run, reportando em logs/métricas os candidatos elegíveis sem emitir comandos de exclusão física destrutiva ao Object Storage até validação explícita.

6. **Tratamento de Registros Legados via Migration V33:**
   - A migration V33 identifica registros históricos em `place_media` com `status = 'REMOVED'` cujo `removed_at` era nulo.
   - Para esses registros legados, o `removed_at` foi preenchido com o valor de `updated_at` (ou `created_at` na ausência deste) e a coluna `removal_source` foi inicializada como `'MODERATOR'`. Isso assegura que fotos legadas cumpram deterministicamente o período de retenção de 24h a partir de sua última atualização histórica e não fiquem em estado inconsistente perante o banco.

7. **Ordem de Locks e Revalidação Pessimista:**
   - Antes de efetuar o expurgo físico, o ciclo de GC adquire os locks pessimistas na ordem global:
     1. Local (`places`);
     2. Mídia (`place_media`).
   - Sob lock, o GC revalida que:
     - O status permanece `REMOVED`;
     - `removed_at` somado a 24 horas é anterior ao instante atual (`now()`);
     - `purged_at` ainda é nulo;
     - Não há nenhuma transação de restauração ou concorrência pendente.

8. **Exclusão Física Idempotente e Registro Atômico:**
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

