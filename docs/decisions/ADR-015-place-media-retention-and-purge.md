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
   - `REMOVED` (durante período de carência de 24 horas): O objeto físico é preservado no Object Storage para possibilitar eventual reversão ou restauração administrativa autorizada (`restoreMediaByModerator`).
   - `REMOVED` (após período de carência de 24 horas): O objeto torna-se elegível ao ciclo de expurgo físico executado pelo processo de Garbage Collection (GC) autorizado.
   - `PURGED` (`purged_at IS NOT NULL`): O registro da mídia permanece no PostgreSQL com `purged_at` preenchido e linha na tabela de auditoria preservada; o objeto físico não existe mais no Object Storage e qualquer tentativa de restauração é estritamente bloqueada (HTTP 409 `STORAGE_OBJECT_PURGED`).

2. **Período de Carência Explícito e Confiável:**
   - A data de remoção lógica é persistida de forma confiável e imutável na coluna `place_media.removed_at` no instante em que a transição para `REMOVED` ocorre.
   - O período de carência mínimo é de 24 horas (`PT24H`). Durante esse intervalo, o objeto físico não pode ser excluído por nenhum processo em segundo plano.

3. **Ordem de Locks e Revalidação Pessimista:**
   - Antes de efetuar o expurgo físico, o ciclo de GC adquire os locks pessimistas na ordem global:
     1. Local (`places`);
     2. Mídia (`place_media`).
   - Sob lock, o GC revalida que:
     - O status permanece `REMOVED`;
     - `removed_at` somado a 24 horas é anterior ao instante atual (`now()`);
     - `purged_at` ainda é nulo;
     - Não há nenhuma transação de restauração ou concorrência pendente.

4. **Exclusão Física Idempotente e Registro Atômico:**
   - A exclusão física no Object Storage é realizada exclusivamente pela porta autorizada de storage. `NOT_FOUND` no storage é tratado como idempotência com sucesso.
   - Imediatamente após a confirmação da ausência do objeto físico no storage, `purged_at` é gravado no banco de dados como `now()`.
   - A linha de metadados da mídia e o log de auditoria nunca são deletados, garantindo rastreabilidade legal e integridade relacional.

## Consequências
### Positivas:
- Eliminação física definitiva de conteúdo impróprio ou removido após a janela de segurança de 24 horas.
- Moderadores dispõem de 24 horas para reverter erros operacionais antes da destruição física irreversível do arquivo.
- Impossibilidade de restauração de ponteiros órfãos para objetos já expurgados.
- Trilha de auditoria integralmente preservada para conformidade legal.

### Negativas / Riscos:
- Após 24 horas da remoção, a fotografia expurgada não pode ser recuperada sob nenhuma circunstância; novo upload pelo usuário é exigido caso necessário.
- A purga física envolve chamada de rede ao Object Storage, exigindo timeout configurado e reprocessamento idempotente em caso de falha transitória de conectividade.

