# ADR-016: Rankings Dimensionais Locais e Governança de Participação

## Status
Aceito

## Contexto
O Rewit nasceu com o princípio inviolável de proteger a confiança comunitária e repudiar mecanismos de gamificação tóxica, "pay-to-win", scores opacos e rankings competitivos globais (ADR-008 e `PROJECT_RULES.md`). Paralelamente, a visão de produto (`PRODUCT_VISION.md`) identificou o desejo legítimo da comunidade em descobrir e reconhecer os exploradores mais ativos da sua própria cidade ou estado em categorias específicas (ex.: quem mais contribuiu com check-ins verificados em cafeterias ou padarias locais).

A introdução de rankings competitivos globais ou scores sintéticos compostos permanece proibida porque criaria incentivos perversos à manipulação de reputação e violaria a privacidade pública de usuários que não desejam exposição competitiva.

Torna-se imperativo formalizar a distinção arquitetural e as regras estritas que viabilizam **Rankings Locais e Dimensionais V1**, conciliando descoberta comunitária, privacidade voluntária e consistência transacional.

---

## Decisão

### 1. Separação entre Rankings Globais (Proibidos) e Rankings Locais Dimensionais (Permitidos)
- **Rankings Globais Competitivos**: Permanecem estritamente proibidos. Não há lista geral de "melhores usuários do app" nem pontuação unificada comparando usuários em escala global.
- **Rankings Locais Dimensionais V1**: São agregados analíticos locais e segmentados, definidos estritamente por uma métrica factual por vez, dentro de um escopo geográfico (`state` obrigatório, `city` opcional) e temático (`category` opcional).

### 2. Três Dimensões Fatuais Independentes (Sem Score Composto)
São suportados exatamente três rankings autônomos, sem ponderação cruzada nem influência de `UserReputation` ou `reputation_score`:
1. **Check-ins Presenciais Verificados (`/api/v1/rankings/check-ins`)**:
   - Conta `COUNT(c.id)` onde `c.status = 'VERIFIED'`.
   - Apenas check-ins vinculados a avaliações públicas, ativas e não anônimas (`r.status = 'ACTIVE'`, `r.visibility = 'PUBLIC'`, `r.is_anonymous = FALSE`).
2. **Locais Distintos Explorados (`/api/v1/rankings/places-explored`)**:
   - Conta `COUNT(DISTINCT r.context_place_id)` de avaliações elegíveis no período e local.
   - Múltiplas avaliações publicadas para o mesmo estabelecimento contam como 1 único local explorado, valorizando a diversidade geográfica e inibindo repetição artificial.
3. **Votos Úteis Recebidos (`/api/v1/rankings/helpful-votes`)**:
   - Conta `COUNT(rx.id)` onde `rx.reaction_type = 'HELPFUL'`.
   - Baseado no timestamp do voto útil (`rx.created_at`).
   - Avaliação avaliada deve ser ativa, pública e não anônima.
   - Voto próprio (`rx.user_id = r.user_id`) é expressamente desconsiderado.
   - Reator e autor devem ser contas ativas (`account_status = 'ACTIVE'`).

### 3. Modelo de Participação Estritamente Voluntária (Opt-In)
- Por padrão, novas contas e contas legadas possuem `profiles.show_in_rankings = FALSE`.
- Nenhum usuário é listado em nenhum ranking até que manifeste consentimento explícito ativando a flag através do endpoint `PUT /api/v1/me/ranking-preferences`.
- A desativação a qualquer momento remove o participante imediatamente de todas as listagens.
- Contas excluídas ou em processo de purge são automaticamente removidas (`show_in_rankings = FALSE`).

### 4. Ciclos Temporais e Ativação Persistente
- **Períodos Admitidos**: Somente `WEEK` e `MONTH`. O período `ALL_TIME` é proibido na V1 para evitar privilégio histórico a contas antigas e custos computacionais descontrolados.
- **Fuso Horário Canônico**: Todos os ciclos são calculados em `America/Sao_Paulo`.
  - `WEEK`: Segunda-feira 00:00:00 BRT até próxima segunda-feira 00:00:00 BRT (início inclusivo, fim exclusivo).
  - `MONTH`: Primeiro dia do mês 00:00:00 BRT até primeiro dia do próximo mês 00:00:00 BRT.
- **Ativação Persistente (`rankingsActivatedAt`)**:
  - Armazenada em tabela singleton de configurações (`ranking_configurations`).
  - O início efetivo da consulta analítica é `max(periodStart, rankingsActivatedAt)`.
  - Quando a ativação ocorre durante o período corrente, os metadados sinalizam `isPartialPeriod = true`.
  - Se a data de ativação for posterior ao encerramento do período, a consulta retorna resultado vazio válido.

### 5. Escopo Geográfico e Catálogo de Categorias
- **Geografia**: `state` (UF brasileira de 2 letras) é obrigatório, validado contra `BrazilianState`. `city` é opcional e comparado sob a normalização canônica `rewit_search_normalize` (insensível a acentuação, maiúsculas/minúsculas e espaços excedentes). Cidades homônimas em estados diferentes são isoladas com segurança pela obrigatoriedade da UF. Filtro por bairro (`neighborhood`) não é suportado pelo schema na V1.
- **Categorias**: Filtro opcional via catálogo canônico de `InterestCategory` (onboarding V40), mapeado deterministicamente para as categorias de locais via `TargetCategoryMapper` e comparado no SQL com a mesma regra de normalização canônica (`rewit_search_normalize` e unificação de separadores). Não é criado nenhum catálogo paralelo e não ocorrem falsos positivos entre categorias distintas (ex.: `RESTAURANT` vs `FAST_FOOD`).

### 6. Empates Reais, Ordenação e Paginação
- **Empate Real (`DENSE_RANK()`)**: Usuários com a mesma pontuação recebem a mesma posição no ranking (ex.: dois líderes com 10 check-ins ocupam ambos a posição 1; o próximo com 9 check-ins ocupa a posição 2).
- **Ordenação Determinística**: A listagem é ordenada por `score DESC`, com desempate secundário estável por `user_id ASC`.
- **Teto e Paginação**: Limite máximo de 100 participantes (`TOP 100`) por ranking. Paginação padronizada (`page`, `size` padrão 20, teto 50).

### 7. Segurança, Autenticação e Governança de Contas
- **Autenticação Obrigatória nos Rankings**: Os três endpoints (`GET /api/v1/rankings/*`) exigem autenticação centralizada via Spring Security (`Bearer <JWT>`). Não são expostos nomes, handles, avatares, localização e horários de contribuição para clientes não autenticados.
- **Identificação do Usuário Logado**: A flag `isCurrentUser` nos DTOs de resposta é derivada estritamente da identidade do JWT (`authentication.getName()`), marcando `true` somente para o participante que coincide com o usuário logado.
- **Política de Estado de Conta (`AccountStatusPolicy`)**: Endpoints de leitura e gravação de preferências (`GET/PUT /api/v1/me/ranking-preferences`) exigem conta operacional. Contas com status `SUSPENDED`, `DEACTIVATED` ou `DELETED` têm acesso recusado com `401 ACCOUNT_DISABLED`.

### 8. Distinção entre Contadores Pessoais e Rankings Públicos
- **Contadores de Perfil e Reputação Pessoal (`verifiedReviewsCount`, `UserStatsView`, `UserReputation`)**: Apresentam um agregado factual acumulado de todo o histórico do ciclo de vida da conta do próprio usuário. São computados sobre todas as contribuições ativas e não anônimas do usuário, independentemente de adesão voluntária a rankings ou recortes de cidade/estado/categoria.
- **Rankings Públicos (`GET /api/v1/rankings/*`)**: Apresentam agregados analíticos locais e públicos, estritamente limitados à janela temporal solicitada (`WEEK`, `MONTH`), ao escopo geográfico (`state`, `city`) e de categoria, restritos exclusivamente a contas ativas operacionais que ativaram explicitamente a preferência de participação (`profiles.show_in_rankings = TRUE`).
- **Invariante**: O valor de `verifiedReviewsCount` de um usuário não reflete nem deve coincidir com sua pontuação no ranking de check-ins, visto que este último possui recorte temporal, geográfico e filtro de opt-in.

---

## Consequências

### Positivas:
- Atende à necessidade de reconhecimento local e comunitário sem violar a integridade anti-gamificação do Rewit.
- Privacidade e LGPD preservadas com opt-in explícito por padrão e revogação instantânea.
- Sem custo de agregação infinita de `ALL_TIME` e sem scores arbitrários opacos.
- Resolução limpa de empates com `DENSE_RANK()` e janelas determinísticas alinhadas ao calendário civil brasileiro.

### Negativas / Restrições:
- Usuários que esquecerem de habilitar o opt-in não aparecem nos rankings até ativarem nas configurações.
- Consultas analíticas exigem índices parciais no banco de dados e execução de agregações locais sob demanda.
