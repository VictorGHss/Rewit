# RELATÓRIO DE AUDITORIA DA FUNDAÇÃO (docs/development/foundation-audit.md)

Este documento apresenta a auditoria técnica rigorosa realizada sobre a fundação do repositório **Rewit** criada no Prompt 01, confrontando o código existente com os princípios do `PROJECT_RULES.md`, `PRODUCT_VISION.md`, `ARCHITECTURE.md` e ADRs.

---

## A. O que está correto

1. **Topologia de Monorepo Modular**: Estrutura de diretórios bem particionada (`apps/mobile`, `apps/admin`, `backend`, `services/vision`, `infrastructure`, `docs`, `scripts`), prevenindo acoplamentos precoces entre clientes e backend.
2. **Documentação Arquitetural e Governança**:
   - `PROJECT_RULES.md` contendo os 31 princípios obrigatórios.
   - `PRODUCT_VISION.md` conceituando avaliação multi-alvo, dimensões de experiência, scanner e separação de reputação e cosméticos.
   - `ARCHITECTURE.md` estabelecendo Clean Architecture e PostGIS como fonte da verdade.
   - ADRs de 001 a 008 cobrindo decisões de stack, PostGIS, isolamento do Google e modelo espacial.
3. **Externalização de Configurações**:
   - Matriz completa em `.env.example` sem valores de segredos reais.
   - `scripts/verify-env.ps1` validando a ausência de chaves privadas e credenciais versionadas.
4. **Padronização de Respostas e Erros**:
   - `ApiResponse.java` estabelecendo envelope padrão de saída.
   - `GlobalExceptionHandler.java` aderente ao padrão internacional [RFC 7807 (Problem Details)](https://tools.ietf.org/html/rfc7807).
5. **Scaffold dos Clientes**:
   - Flutter (`apps/mobile`) estruturado com Clean Architecture (`core`, `domain`, `data`, `presentation`, `integrations`).
   - Admin React (`apps/admin`) com TypeScript e Vite configurados.

---

## B. O que precisa ser corrigido

1. **Gestão do Esquema de Banco (Violação de Boas Práticas)**:
   - O `application-local.yml` utilizava `spring.jpa.hibernate.ddl-auto: update`. Isso contradiz a necessidade de schema versionado, determinístico e auditável. Deve ser substituído por migrações via **Flyway** (`db/migration/V1__initial_schema.sql`).
2. **Defasagem de Versões do Stack**:
   - O Prompt 01 inicializou com Java 21 LTS, Spring Boot 3.3.3, PostgreSQL 16 e PostGIS 3.4. O projeto deve ser atualizado para a linha moderna e estável recomendada: **Java 25 LTS**, **Spring Boot 4.1.x**, **PostgreSQL 18** e **PostGIS 3.6**.
   - No Docker Compose, a imagem `postgis/postgis:18-3.6` requer a atualização do volume de dados para `/var/lib/postgresql` (mudança oficial do PostgreSQL 18).
3. **Modelagem Frágil de ReviewTarget**:
   - A entidade `ReviewTarget` utilizava colunas soltas `target_type` e `target_id` sem uma estratégia explícita de integridade referencial ou suporte desacoplado a alvos avaliáveis (`RateableTarget`).
4. **Métricas de Avaliação Acopladas**:
   - As classes `Place` e `Product` continham campos diretos `average_rating` e `reviews_count` atualizados arbitrariamente, violando o princípio de que as médias devem ser derivadas de avaliações válidas e não campos independentes sujeitos a inconsistência transacional.
5. **Entidades Incompletas no Backend**:
   - Apenas `Review`, `ReviewTarget` e `Place` haviam sido esboçados no Java, deixando de fora as outras 15 entidades do domínio consolidado (`Profile`, `Product`, `ProductIdentifier`, `ProductPresence`, `Service`, `Event`, `CheckIn`, `UserInterest`, `UserActivity`, `UserFollow`, `SavedItem`, `Notification`, `BusinessAccount`, `Promotion`, `Tag`, `PlaceExternalReference`).

---

## C. O que foi implementado cedo demais

1. **Endpoint e Serviço Funcional de Review**:
   - `ReviewService.java`: implementava um método `createReview(...)` que criava um mock de resposta funcional com cálculo ternário de presença.
   - `ReviewController.java`: expunha o endpoint `POST /api/v1/reviews` de negócio, antes mesmo da consolidação do modelo de banco e autenticação.
   - `ReviewDto.java`: declarava classes de entrada (`CreateReviewRequest`, `TargetInput`) específicas para fluxo de escrita que pertencem aos próximos prompts.
2. **Implementações Simuladas em Adaptadores**:
   - `GoogleIdentityAdapter.java`: continha mock retornando dados fictícios de usuário autenticado.

---

## D. O que pode ser reaproveitado

1. A separação de pacotes concêntricos (`common`, `config`, `domain`, `application`, `infrastructure`, `presentation`).
2. Configurações de infraestrutura: `OpenApiConfig`, `RedisConfig`, `MinioConfig`.
3. Script SQL de inicialização `01-init-postgis.sql` e Dockerfiles multi-stage.
4. Scripts PowerShell de automação (`start-local.ps1`, `verify-env.ps1`, `stop-local.ps1`).
5. A documentação viva em `docs/`.

---

## E. O que deve ser refatorado

1. **Adoção de Portas e Adaptadores (Hexagonal Architecture)**:
   - Criar interfaces em `com.rewit.application.port.*`:
     - `IdentityProvider`
     - `PlaceProvider`
     - `MapProvider`
     - `ReviewPublisher`
     - `NotificationProvider`
     - `EmailProvider`
   - Migrar implementações concretas para `com.rewit.infrastructure.integration.*`, garantindo que o domínio e a aplicação nunca importem dependências externas.
2. **Refatoração de Review e Place**:
   - Remover comportamento de escrita prematuro de `ReviewService` e `ReviewController`.
   - Limpar campos diretos de média móvel não derivada nas entidades.
   - Estabelecer a herança/associação com `RateableTarget` para `ReviewTarget`.
3. **Criação das Migrações Flyway**:
   - Adicionar dependências `flyway-core` e `flyway-database-postgresql`.
   - Criar `V1__initial_schema.sql` ativando PostGIS e gerando o DDL completo com todas as constraints, chaves estrangeiras, UUIDs e índices espaciais GiST.
4. **Criação do Modelo de Domínio Completo**:
   - Consolidar as 18 entidades e objetos de valor em `com.rewit.domain`.

---

## F. O que deliberadamente NÃO será implementado nesta etapa

Em estrito alinhamento com o escopo de consolidação de fundação:
- Não implementar endpoints de login, OAuth2 Google ou emissão de JWT.
- Não implementar feed dinâmico ou algoritmos de recomendação.
- Não implementar scanner de código de barras ou OCR no mobile.
- Não implementar chamadas HTTP reais a serviços Google externos.
- Não implementar algoritmo de validação em tempo de execução de check-in.
- Não implementar envio real de notificações push ou e-mails.
- Não implementar rankings, pontuações de gamificação ou itens cosméticos.
- Não implementar assinaturas ou fluxo de pagamentos de contas empresariais.
- Não implementar microserviço Python de visão computacional.
