# ADR-011: Retenção e Cleanup de Sessões de Autenticação

## Status
Proposto

Pendente: decisão de produto/jurídico sobre a coleta e a retenção de `ip_address` e `user_agent` enquanto a sessão está ativa. As decisões técnicas abaixo já estão implementadas (Step 29.3).

## Contexto
A tabela `auth_sessions` guarda um registro por refresh token emitido: o hash SHA-256 do token, a expiração, a revogação, a sessão que a substituiu na rotação (`replaced_by_session_id`, FK com `ON DELETE SET NULL`), e os metadados `ip_address` e `user_agent`. Até o Step 29.3 nenhuma linha era removida.

A detecção de reúso de refresh token (corrigida no Step 29.1) depende de a sessão revogada continuar no banco: reapresentar um token revogado fora da janela de 10 segundos revoga todas as sessões do usuário. Se um atacante rotacionar um token roubado antes do cliente legítimo, sua cadeia de rotação pode ser renovada indefinidamente, e só é derrubada quando o cliente legítimo reapresenta o token original. Apagar a sessão revogada por prazo fixo eliminaria essa detecção para cadeias ainda vivas. `ip_address` e `user_agent` são gravados, mas não são lidos por nenhum código.

## Decisão
1. **Critério de purge estrutural**: uma sessão só é removida quando `expires_at < now() AND replaced_by_session_id IS NULL`. Sessões ativas nunca são removidas; sessões revogadas ficam até a própria expiração; antecessoras de rotação ficam enquanto a sucessora existir. Não há prazo de retenção configurável.
2. **Cadeia sem recursão**: ao remover a sucessora, o `ON DELETE SET NULL` limpa a referência da antecessora, que fica elegível em lote posterior. A cadeia inteira é preservada enquanto a cauda estiver viva e desmontada um elo por lote depois.
3. **Índice V16**: `idx_auth_sessions_replaced_by_session_id` (parcial, `WHERE replaced_by_session_id IS NOT NULL`), criado com `CONCURRENTLY` fora de transação, para que o `SET NULL` não percorra a tabela a cada linha removida. O Flyway usa lock de sessão (`spring.flyway.postgresql.transactional-lock=false`); com o lock transacional padrão, a migration esperaria a transação do próprio Flyway indefinidamente.
4. **Lotes com `FOR UPDATE SKIP LOCKED`**, cada um em transação própria, sem advisory lock nem lock global: instâncias concorrentes processam lotes disjuntos. Falhas desfazem só o lote em curso; o próximo ciclo tenta de novo.
5. **Metadados separados da retenção de segurança**: `ip_address` e `user_agent` são apagados (`NULL`) assim que a sessão deixa de estar ativa (`revoked_at IS NOT NULL OR expires_at < now()`), sem esperar a remoção da linha.
6. **Operação**: job agendado fino, ligado por padrão como o purge do Outbox, sem dry-run e sem endpoint.
7. **Corrida do logout aceita**: o logout lê a sessão sem lock; se o purge a remover antes do `save`, a linha é recriada já revogada e sai no ciclo seguinte.

## Consequências
### Positivas:
- A detecção de reúso continua valendo para toda cadeia de rotação viva.
- A tabela deixa de crescer indefinidamente com sessões mortas.
- IP e user agent não acompanham o histórico de segurança das cadeias.
- Nenhuma alteração no fluxo de autenticação nem número de retenção arbitrário.

### Negativas:
- O histórico de rotações de usuários ativos permanece enquanto a cadeia estiver viva (cerca de uma linha por refresh).
- Cadeias mortas são desmontadas linearmente, um elo por lote.
- Após o purge, o refresh token removido recebe `INVALID_REFRESH_TOKEN` em vez de `REFRESH_TOKEN_EXPIRED`/`REFRESH_TOKEN_REVOKED`, e seu reúso deixa de revogar as demais sessões.
- Risco raro de deadlock entre um lote do purge e uma revogação em massa do mesmo usuário; o PostgreSQL aborta uma das transações.

## Alternativas Rejeitadas
- **Remover sessões apenas por `expires_at`** (com ou sem retenção extra) **ou por prazo desde `revoked_at`**: deixam de detectar o reúso que derrubaria uma cadeia ainda viva, ou dependem de um número sem evidência.
- **Busca recursiva da cadeia (CTE)**: desnecessária com o `SET NULL`; pode ser reavaliada se a desmontagem linear se mostrar lenta.
- **Advisory lock global e dry-run**: o `DELETE` é interno, determinístico e seguro entre instâncias com `SKIP LOCKED`.
- **Limpar IP/user agent no `AuthService`**: exigiria alterar rotação, logout e revogação em massa e não cobriria sessões que expiram sem revogação.
- **Reter IP/user agent até a remoção da sessão ou por prazo próprio**: retém dados sem uso funcional.
