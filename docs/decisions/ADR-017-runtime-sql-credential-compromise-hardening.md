# ADR-017: Hardening de Autenticação e Sessões contra Comprometimento da Role SQL de Runtime (S3-A13-F1)

## Status
Implementado na branch `agent/carbon/auth-f1-hardening` (não integrada). Status do achado: **mitigado somente após a regressão completa e os testes de ataque aprovados** (resultado registrado no roadmap §13.8). Revisado após a auditoria independente (F1-AUD-01 a F1-AUD-09).

## Contexto
O achado `S3-A13-F1` mostrou que quem possuísse apenas a credencial SQL de runtime (`rewit_app`) conseguia assumir contas, inclusive `ADMIN`:

1. gravar um hash Argon2id de senha conhecida em `users.password_hash`;
2. copiar o hash de senha de uma conta para outra;
3. inserir em `auth_sessions` uma sessão com `token_hash = SHA-256(token escolhido)` e obter JWT em `/auth/refresh`;
4. transplantar uma sessão para outra conta (`UPDATE` de `user_id`, ou `DELETE` + `INSERT`);
5. alterar `auth_provider` / `provider_user_id` para assumir uma conta federada;
6. reativar uma sessão revogada ou expirada.

A auditoria independente da primeira implementação (REJECTED) encontrou, entre outros: `DELETE + INSERT` ainda possível (ressurreição de sessões), decodificação inconsistente de `JWT_SECRET`, rotação de pepper quebrada com `keyId` vazio, selamento legado sem transação efetiva e sem base de confiança, e regressões de teste.

## Decisão

### 1. Password pepper vinculado ao UUID (`pw2`)
`preHash = Base64URL(HMAC-SHA256(K_password, "rewit-password-v1:" || userId(16 bytes) || senha))`, entrada do Argon2id existente (`m=19456, t=2, p=1`). Formato: `pw2$v1$<keyId>$argon2id$v=19$m=19456,t=2,p=1$<salt>$<hash>` (exemplo com o padrão: `pw2$v1$v1$argon2id$...`; o PHC é gravado sem o `$` inicial). `keyId` obrigatório, não vazio, padrão `v1`, formato `[A-Za-z0-9_-]{1,32}`. Rotação: `PASSWORD_PEPPER_SECRET_PREVIOUS` + `PASSWORD_PEPPER_PREVIOUS_KEY_ID` (diferente do ativo); o `keyId` do hash seleciona a chave (ativa, anterior ou rejeição sem Argon2), com no máximo uma verificação Argon2 por tentativa; após sucesso com a chave anterior, o login regrava o hash com a ativa. `simulateVerification` usa um UUID fixo com o mesmo HMAC + Argon2id.

### 2. Hashes legados: sem selamento (decisão definitiva)
`LegacyPasswordSealer` e o formato `legacy-sealed` foram **removidos**. Motivo: com a credencial de runtime comprometida, qualquer hash existente no banco pode ter sido adulterado; selá-lo com um MAC o legitimaria. O Rewit não tem produção ativa. Hashes sem `pw2` são rejeitados; ambientes de desenvolvimento/teste devem recriar contas ou redefinir senhas por operador privilegiado. Uma eventual preservação futura exigiria procedimento operacional separado (snapshot confiável anterior à exposição, backup verificado, manutenção, execução privilegiada com `TransactionTemplate`, dry-run), nunca um endpoint ou startup automático.

### 3. Arquitetura de sessão escolhida
Combinação de três camadas, nenhuma coluna nova:

| Camada | Mecanismo |
|---|---|
| A. Privilégio | `rewit_app` **perde `DELETE`** em `auth_sessions` (V44). O trigger `BEFORE UPDATE` não cobre `DELETE`+`INSERT`; sem o privilégio, uma sessão não pode ser apagada e recriada. |
| B. Remoção legítima | Duas funções `SECURITY DEFINER` restritas (owner da migração, `search_path` fixo, `EXECUTE` só para `rewit_app`, sem SQL dinâmico): `rewit_purge_expired_auth_sessions(p_now, p_limit)` (lotes `FOR UPDATE SKIP LOCKED`, corte `LEAST(p_now, clock_timestamp())`, só expiradas sem sucessora) e `rewit_purge_deleted_user_auth_sessions(p_user_id)` (só conta `DELETED` com `deleted_at`). |
| C. Vínculo da expiração | Refresh token de 56 bytes = UUID (16) + `expires_at` em epoch seconds (8) + aleatório (32), Base64URL sem padding, 75 caracteres; `token_hash = HMAC-SHA256(REFRESH_TOKEN_SECRET, token)`. O mesmo valor de expiração é gravado em `auth_sessions.expires_at`. No refresh e no logout compara-se UUID e expiração do token com a sessão; token expirado nunca renova. |

Alternativa considerada e rejeitada: coluna de MAC sobre `(id, user_id, expires_at, revoked_at)`. Exigiria tratar replay de estados antigos (um MAC válido de um estado anterior, por exemplo "não revogada", poderia ser restaurado por quem controla a linha) e versionamento, para um ganho que as camadas A–C já entregam. Clientes mobile e admin continuam tratando o refresh token como string opaca.

### 4. Refresh token, segredos e formato
`REFRESH_TOKEN_SECRET` (+ `REFRESH_TOKEN_SECRET_PREVIOUS` na rotação, mantida por pelo menos o TTL máximo do refresh token). Um único decodificador (`SecuritySecretValidator.decodeSecret`) serve ao emissor JWT, ao `JwtDecoder` e aos demais componentes: Base64/Base64URL válidos decodificam para bytes e exigem ≥ 32 bytes (sem fallback para UTF-8 se curtos); texto fora desses alfabetos vale como UTF-8 com ≥ 32 bytes; ativos e anteriores não compartilham material. Geração por CSPRNG; mensagens citam só o nome da variável.

### 5. Guards SQL (`V44`)
`trg_guard_user_role_assignment` (identidade em `users`, exceção: `provider_user_id = NULL` em conta `DELETED` para o purge LGPD) e `trg_guard_auth_session_integrity` (`id`, `user_id`, `token_hash`, `issued_at`, `expires_at`, `created_at` imutáveis; sem desrevogar). `rewit_assert_runtime_privileges()` agora também exige: sem `DELETE` em `auth_sessions`, as duas funções com owner/`SECURITY DEFINER`/`search_path`/grants corretos, nenhuma outra função `SECURITY DEFINER` e nenhuma membership indevida, preservando as asserções da V37.

### 6. Migration
A V44 existe apenas nesta branch não integrada e nenhum banco persistente a executou (os testes usam bancos descartáveis); por isso foi corrigida **no próprio arquivo V44** em vez de criar V45. Se a V44 chegar a ser aplicada em qualquer ambiente persistente antes da integração, as mudanças desta revisão passam a exigir nova migration.

## Consequências
- Os seis vetores acima são cobertos por testes de ataque executados com a conexão de runtime (`RuntimeSqlCredentialCompromiseIntegrationTest`).
- Novos segredos obrigatórios (`PASSWORD_PEPPER_SECRET`, `REFRESH_TOKEN_SECRET`), fail-closed no startup.
- Tokens emitidos antes desta mudança e hashes sem `pw2` deixam de valer: não há produção ativa, e os ambientes de desenvolvimento/teste recriam contas.
- Após o purge, um token expirado recebe `INVALID_REFRESH_TOKEN` (já era assim para sessões removidas; ADR-011).
- Comparações: UUID e expiração do token são comparados por igualdade com a sessão persistida (não são segredos); a comparação sensível é o HMAC, resolvido por busca indexada do hash.

## Riscos residuais
- Quem possui o refresh token válido pode renová-lo pela API (esperado).
- O runtime ainda pode revogar sessões de terceiros (`UPDATE revoked_at`) e inserir sessões inertes: negação de serviço, fora do escopo.
- Segredos externos comprometidos junto com SQL anulam a proteção; mantê-los fora do banco, em cofre próprio, e rotacioná-los.
- **S3-A13-F2 (moderação) continua aberto**: este ADR não o trata.

## Alternativas Rejeitadas
- **MAC em colunas de sessão**: ver §3.
- **Assinatura assimétrica do refresh token**: custo e gestão de chaves sem ganho sobre HMAC com segredo externo.
- **Selamento de hashes legados**: ver §2.
- **Apenas retirar `DELETE`**: não fecha a reconstrução após o purge legítimo; por isso a expiração vai no token.
