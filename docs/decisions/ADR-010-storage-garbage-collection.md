# ADR-010: Garbage Collection do Object Storage de Mídias

## Status
Aprovado

## Contexto
Mídias de avaliação são gravadas no SeaweedFS (API S3) e referenciadas em `review_media` no PostgreSQL. Os dois sistemas não compartilham transação: falhas de compensação do upload ou remoções de linhas fora do fluxo normal podem deixar objetos sem referência, inacessíveis pela API, mas ocupando storage.

Remover objetos com base apenas numa varredura é inseguro. O upload grava o objeto antes de commitar a linha, então um objeto pode ser observado sem referência de forma legítima. Além disso, a exclusão física é irreversível e não participa de transação com o banco.

## Decisão
1. **PostgreSQL é a fonte de verdade.** Um objeto sob `reviews/` pertence à aplicação enquanto existir uma linha em `review_media` com sua chave, em qualquer status: `REMOVED` também é referência. Somente o prefixo `reviews/`, com o formato de chave gerado pelo upload, é processado.
2. **GC separado do Outbox.** O GC é uma varredura direta, com scheduler próprio; não gera nem consome mensagens do Outbox.
3. **Quarentena persistente.** Objetos vistos sem referência são registrados em `storage_object_quarantine` (uma linha por chave, garantida por constraint). A primeira observação persistida inicia o grace period.
4. **Grace period explícito e retenção mínima de 24h.** O grace period é uma política (`StorageQuarantineGracePolicy`) com duração obrigatória na configuração e sem valor padrão. Para o namespace `places/`, mídias com remoção lógica cumprem período de retenção e quarentena mínimo de 24 horas (`PT24H`) antes de qualquer elegibilidade a expurgo físico (detalhado na ADR-015).
5. **Rechecagem sob locks.** A ausência de referência é confirmada sob o mesmo lock de linha em `reviews` ou `places -> place_media` que o upload mantém do envio ao storage até o commit. A ordem dos locks é sempre entidade mãe → quarentena. Só então o objeto passa a `CONFIRMED_ORPHAN`.
6. **Exclusão idempotente.** A exclusão física repete a consulta sob os locks e só depois chama o storage. A linha de quarentena só é removida depois que o objeto fica ausente. `NOT_FOUND` é sucesso. Storage e PostgreSQL não formam transação distribuída: a ordem garante que qualquer falha deixe a quarentena para nova tentativa.
7. **Desligado por padrão e com dry-run.** O GC só é montado quando explicitamente ativado (`rewit.storage-gc.enabled=true`) e com configuração completa validada no boot. Por padrão de segurança, o GC permanece desativado (`enabled=false`) e qualquer execução de teste ou diagnóstico deve operar em modo dry-run (sem capacidade de chamada destrutiva ao Object Storage).
8. **Uma instância por vez.** O ciclo roda sob um advisory lock de sessão do PostgreSQL, não bloqueante e sem relação com linhas de domínio.
9. **Trilha de auditoria imutável e proteção append-only (V34).** A eliminação física de binários no storage nunca afeta ou altera a trilha de auditoria append-only (`place_media_moderation_audit_logs`), que preserva permanentemente o histórico forense e probatório de cada ação executada. Na migration V34, essa tabela recebeu proteção adicional por triggers no PostgreSQL contra `UPDATE`, `DELETE` e `TRUNCATE`, garantindo integridade estritamente append-only.
10. **Rejeição de denúncias sem reativação implícita.** A rejeição de uma denúncia (`REJECT_REPORT`) nunca reativa automaticamente uma mídia para `ACTIVE`. Restaurações são ações independentes, explícitas e auditadas (`RESTORE`), condicionadas à presença do objeto físico no storage e à verificação da cota de mídias ativas sob lock.
11. **Origens de remoção segregadas.** Remoções por autor (`AUTHOR`), responsável do estabelecimento (`BUSINESS_OWNER` / `PLACE_MANAGER`) e moderação (`MODERATOR`) possuem códigos e trilhas de auditoria distintas; mídias removidas pelo autor ou pelo responsável nunca são restauradas por moderação.

O detalhamento está em `docs/architecture/storage-reconciliation.md` e na ADR-015.

## Consequências
### Positivas:
- Nenhum objeto referenciado (inclusive `REMOVED` em período de carência) é removido, e uploads em andamento são protegidos por lock, não apenas por tempo.
- Falhas e quedas em qualquer ponto são recuperadas por nova tentativa, sem transação distribuída.
- Implantar uma nova versão nunca inicia exclusões: o GC exige ativação e configuração explícitas, operando desligado e em dry-run por padrão.
- Nenhuma infraestrutura nova: PostgreSQL e Micrometer já fazem parte da stack.

### Negativas:
- Objetos órfãos só são removidos após o grace period (mínimo de 24 horas) e em ciclos limitados; o espaço é recuperado de forma gradual.
- Durante a exclusão de um objeto, uploads para a mesma entidade aguardam a chamada ao storage.
- A exclusão é irreversível: o sistema não restaura objetos fisicamente removidos.
