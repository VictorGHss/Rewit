# ADR-013: Rate Limiting Distribuído

## Status
Proposto

## Contexto
Login, refresh e cadastro eram públicos e sem nenhum limite. Cada login verifica um Argon2id (~19 MiB de memória por verificação), então uma rajada de tentativas é, além de força bruta, custo de CPU e memória no servidor. O login com e-mail inexistente respondia antes de calcular qualquer hash, e o tempo de resposta revelava se o e-mail estava cadastrado.

Denúncias, comentários e uploads tinham limitadores em memória por instância (`ReportRateLimiter`, `DiscussionRateLimiter`, `MediaRateLimiter`), importados direto pela camada de aplicação. Com N instâncias da API, cada uma contava separadamente.

O Redis já fazia parte da infraestrutura (`docker-compose`, `RedisConfig`), mas não era usado. O uso de IP está pendente de decisão de produto/jurídico (ADR-011).

## Decisão
1. **Porta `RateLimiter`** na aplicação (`tryAcquire`, `release`, `acquireOrThrow`), com ações fechadas (`RateLimitedAction`). Limite e janela pertencem à configuração da ação (`rewit.rate-limit.*`), não ao chamador. O 429 usa o `BusinessException` e o código `RATE_LIMIT_EXCEEDED` já existentes.
2. **Janela deslizante no Redis**: um sorted set por sujeito e ação. Um script Lua faz limpeza, contagem, registro e `PEXPIRE` em uma única operação atômica, com o instante do relógio do Redis (`TIME`). A chave nunca existe sem TTL e expira uma janela após a última tentativa concedida. A semântica é a mesma dos limitadores em memória substituídos, cujos limites foram mantidos (10, 15 e 10 por usuário em 60 s).
3. **Chaves por HMAC-SHA256** com `rewit.rate-limit.key-secret` (obrigatório com o rate limiting ligado, mínimo de 32 bytes, igual em todas as instâncias). E-mail e id de usuário nunca vão em claro para o Redis, logs ou métricas.
4. **Sem limite por IP** nesta versão. Os sujeitos são: e-mail normalizado no login, usuário no refresh e nas ações de conteúdo, e um contador global no cadastro.
5. **Login conta apenas tentativas sem sucesso**: a tentativa ocupa a janela antes da verificação (bloqueando rajadas concorrentes) e é devolvida em caso de sucesso. E-mail inexistente consome a mesma janela e executa uma verificação Argon2 contra um hash descartável (`PasswordHasher.simulateVerification`).
6. **Refresh limitado por usuário, depois da detecção de reúso**, que nunca é bloqueada pelo limite. O 429 faz rollback sem rotacionar nem consumir o token.
7. **Redis indisponível**: o padrão é `LOCAL_FALLBACK`, que aplica os mesmos limites em memória, por instância. `DENY` e `ALLOW` são configuráveis. Após uma falha, o Redis não é consultado por `retry-interval` (10 s), e depois uma única requisição o testa de novo. A transição gera `WARN` (só com a classe do erro), cada falha incrementa `rewit.rate_limit.backend_errors` e o gauge `rewit.rate_limit.backend_available` vai a 0.

## Consequências
### Positivas:
- Os limites valem entre instâncias, e a fonte de estado deixa de ser a memória de cada processo.
- Força bruta por conta e o custo de Argon2 por identidade ficam limitados, e o tempo de resposta não distingue e-mail inexistente de senha errada.
- Uma falha do Redis não desliga a proteção nem derruba login, refresh ou cadastro.
- A aplicação deixa de depender de classes de infraestrutura para rate limiting.

### Negativas:
- O Redis passa a estar no caminho das requisições limitadas. Em uma queda, as primeiras chamadas esperam o timeout do cliente (3 s no perfil `local`) antes do modo degradado, e a checagem acontece dentro da transação do serviço.
- Em modo degradado, o limite efetivo fica em até N vezes o configurado.
- Sem IP, o limite de login por conta permite que terceiros bloqueiem temporariamente o login de uma conta conhecida (até 15 minutos com o padrão). O limite global de cadastro pode ser esgotado por um único cliente.
- Novo segredo obrigatório em todos os ambientes (`RATE_LIMIT_KEY_SECRET`).

## Alternativas Rejeitadas
- **`INCR` + `EXPIRE` em comandos separados**: deixa janela para uma chave sem TTL, e a janela fixa permite rajadas de até 2× o limite na virada.
- **Fail-open (`ALLOW`) como padrão**: desliga silenciosamente toda a proteção durante qualquer queda do Redis.
- **Fail-closed (`DENY`) como padrão**: transforma a queda do Redis em indisponibilidade de login, refresh e cadastro.
- **Bucket4j ou outra biblioteca**: dependência nova para um script de poucas linhas sobre o Spring Data Redis já presente.
- **Limite por IP**: depende da decisão pendente sobre IP (ADR-011).
- **Contar todos os logins, inclusive os bem-sucedidos**: penaliza uso legítimo sem ganho contra força bruta.
