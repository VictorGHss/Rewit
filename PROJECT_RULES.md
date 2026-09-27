# REWIT - REGRAS E DIRETRIZES DO PROJETO (PROJECT_RULES.md)

Este documento estabelece as regras mandatórias de engenharia, arquitetura, segurança e desenvolvimento para a rede social geográfica **Rewit**. Todas as decisões técnicas, código escrito e PRs devem estar em estrita conformidade com estas regras.

---

## 1. Princípios Fundamentais de Arquitetura e Dados

1. **PostgreSQL 18 como Fonte da Verdade**: O PostgreSQL 18 é o banco de dados principal e a única fonte canônica de verdade de todo o ecossistema Rewit. Nenhum dado de negócio reside unicamente em serviços de terceiros ou caches transitórios.
2. **PostGIS 3.6 para Operações Espaciais**: O PostGIS 3.6 é a tecnologia padrão para armazenamento de dados geográficos (tipos `GEOGRAPHY(Point, 4326)`, polígonos) e execução de consultas de proximidade (`ST_DWithin`), cálculo de distâncias (`ST_Distance`) e contenção espacial (`ST_Contains`).
3. **Google NÃO é o Banco Principal**: O Google e suas APIs (Places, Maps, Identity) nunca devem ser utilizados como persistência primária de locais, perfis ou avaliações.
4. **Google como Provedor Externo Especializado**: As integrações com o Google são exclusivamente utilitárias:
   - **Identity**: Autenticação OAuth2 / OpenID Connect;
   - **Places**: Descoberta inicial de locais e enriquecimento de dados em áreas com base interna vazia;
   - **Maps**: Renderização visual de mapas e rotas no cliente;
   - **Reviews**: Redirecionamento assistido para o fluxo oficial de avaliações do Google (sem automação não autorizada).
5. **Independência de Fornecedores Externos**: O domínio do Rewit deve permanecer funcional mesmo que um fornecedor externo (Google, Firebase, provedor de e-mail) seja descontinuado ou substituído por alternativas (OpenStreetMap, Apple Maps, Mapbox, Auth0).
6. **Isolamento de Integrações (Portas e Adaptadores / ACL)**: Toda e qualquer integração externa deve ser mantida isolada através de interfaces na camada de aplicação (`application/port/`) e adaptadores em `infrastructure/integration/`.
7. **Proibição de Poluição do Domínio**: Nenhuma entidade, DTO de domínio, evento ou caso de uso pode importar bibliotecas, SDKs ou classes proprietárias do Google ou de outros provedores externos.
8. **Proibição de Espelhamento Indiscriminado**: É expressamente proibido fazer scraping, download massivo ou espelhamento cego da base de dados do Google Places. O armazenamento local de referências externas deve ser feito através da entidade `PlaceExternalReference`, respeitando os termos de serviço das APIs.
9. **Conformidade com Políticas de APIs Externas**: Respeitar rate limits, termos de serviço, atribuições de direitos autorais e políticas de cache permitidas por cada fornecedor.
10. **Aprendizado e Crescimento Gradual da Base**:
    - **Fase 1 (Bootstrap)**: Utilização de provedores externos (Google Places) para descoberta e auxílio inicial em áreas com poucos cadastros.
    - **Fase 2 (Acumulação)**: Crescimento da base própria a partir de avaliações reais, check-ins verificados e cadastros de usuários.
    - **Fase 3 (Inteligência Própria)**: Utilização de histórico proprietário para rankings, feeds contextuais e modelos de recomendação.
10.1. **Migrações Obrigatórias com Flyway**: Toda criação ou alteração de schema deve ser realizada através de migrações SQL versionadas no Flyway (`classpath:db/migration`). É terminantemente proibido utilizar `ddl-auto: create` ou `ddl-auto: update` no Hibernate.
10.2. **Integridade de Alvos Avaliáveis (ADR-009)**: Entidades avaliáveis herdam a identidade relacional de `rateable_targets`, garantindo integridade referencial com chave estrangeira estrita em `review_targets`.
10.3. **Médias e Estatísticas Derivadas**: As médias de notas não são colunas mutáveis soltas em `places` ou `products`, sendo derivadas de avaliações legítimas através de agregação e da tabela `rateable_target_stats`.

---

## 2. Privacidade, Localização e Check-in

11. **Privacidade por Design e Minimização de Dados**: Coletar e armazenar estritamente o volume de dados necessário para cumprir a função do sistema, em conformidade com a LGPD e regulamentações internacionais.
12. **Proibição de Histórico Contínuo de Localização**: O sistema não armazena trilhas de GPS (breadcrumbs) nem histórico cronológico de localização passiva do usuário.
13. **Localização Precisa Apenas sob Demanda**: Coordenadas geográficas exatas só são solicitadas e processadas no instante em que o usuário executa uma ação que exija validação espacial (ex: criar avaliação no local, check-in, buscar "perto de mim").
14. **Check-in Exige Presença Física Verificável**: Não é permitido criar check-in artificial ou desvinculado da validação espacial no local.
15. **Avaliação Pode Existir Sem Localização**: Usuários têm o direito de avaliar estabelecimentos, produtos ou serviços remotamente (ex: entrega, visita passada, compra online), sem fornecer localização em tempo real.
16. **Selo de Verificação Exclusivo**: Uma avaliação só recebe o selo *"Avaliado no local"* se a presença física no local for verificada no momento da submissão.
17. **Check-in Indissociável da Avaliação**: Não existe check-in avulso. Todo check-in é gerado a partir de uma avaliação.
18. **Avaliação Mínima para Check-in**: Para que o check-in seja computado, o usuário deve atribuir no mínimo a nota por estrelas ao local ou serviço, mesmo que opte por não redigir texto livre.
19. **Proibição de Rastreamento em Segundo Plano**: O aplicativo mobile não deve executar serviços contínuos em segundo plano para espionar o deslocamento do usuário.
20. **Notificações por Geofencing Eficiente**: Notificações baseadas em contexto ou proximidade devem utilizar mecanismos nativos de geofencing do sistema operacional (Android/iOS) disparados por eventos de fronteira geográfica, sem polling de GPS.
21. **Higienização de Mídias (Remoção de EXIF)**: Todo upload de foto ou vídeo deve passar por processo de sanitização no backend, eliminando metadados EXIF (incluindo tags de GPS originais da câmera) antes da publicação e armazenamento público.
22. **Proteção a Avaliações Anônimas**: O usuário pode optar por publicar avaliações anônimas perante a comunidade. O identificador real é mantido encriptado/isolado no banco apenas para fins de auditoria interna, moderação legal e combate a abusos. Avaliações anônimas não somam pontos em rankings públicos de reputação.

---

## 3. Segurança, Configuração e Secrets

23. **Proibição Absoluta de Hardcode**: É estritamente proibido incluir senhas, tokens de API, chaves privadas, secrets de túnel ou strings de conexão diretamente no código-fonte.
24. **Externalização de Variáveis**: Toda configuração sensível ou dependente de ambiente deve ser lida a partir de variáveis de ambiente do sistema (`System.getenv` ou injeção `${VAR:default}`).
25. **Segurança de Repositório (.env)**:
    - O arquivo `.env` com valores reais **nunca** deve ser versionado no Git.
    - O repositório deve conter um `.env.example` exaustivo, documentando todas as variáveis, seus tipos, descrições e exemplos fictícios seguros.
26. **Logs Estruturados Sem Vazamento de PII**:
    - Nunca emitir logs contendo senhas, tokens JWT, números de documento, e-mails não mascarados ou coordenadas geográficas brutas de residências.

---

## 4. Engenharia de Software e Padrões de Código

27. **Separação de Responsabilidades e Baixo Acoplamento**: A arquitetura do backend segue os princípios de Clean Architecture / Portas e Adaptadores:
    - `domain`: Regras de negócio puras, entidades e contratos de repositório/serviço. Sem dependências de framework web ou bibliotecas de terceiros.
    - `application`: Casos de uso, orquestração e DTOs.
    - `infrastructure`: Implementações de persistência (Spring Data JPA, Hibernate Spatial, Redis, MinIO).
    - `presentation`: Controllers REST, validação de payload e tratamento de respostas HTTP.
    - `integrations`: Adaptadores e clientes HTTP para provedores externos (Google, Notificações).
28. **Comentários Significativos e Auto-explicativos**:
    - O código deve ser auto-descritivo por meio de nomes limpos de variáveis, métodos e classes.
    - Comentários são obrigatórios apenas em trechos com decisões de negócio não óbvias, cálculos matemáticos/geográficos específicos ou contornos de limitações técnicas conhecidas.
    - Proibido adicionar comentários redundantes (ex: `// construtor da classe`, `// get do id`).
29. **Documentação de Mudanças Arquiteturais (ADRs)**: Qualquer alteração de biblioteca essencial, modelo de dados central ou estratégia de comunicação deve ser registrada formalmente em um arquivo de Architecture Decision Record (`docs/decisions/ADR-XXX.md`).
30. **Desenvolvimento Incremental e Sem Escopo Supérfluo**: Construir somente as funcionalidades especificadas no prompt da fase vigente. Proibido introduzir complexidade acidental, overengineering ou implementar módulos futuros sem demanda explícita.
31. **Abstração de Notificações e Comunicação**:
    - Serviços de push notifications e envio de e-mails devem ser acessados via interfaces agnósticas (`PushNotificationService`, `EmailNotificationService`).
    - É proibido acoplar o domínio diretamente ao Firebase Cloud Messaging, SendGrid ou AWS SES.
    - O Firebase não será utilizado como banco de dados principal, serviço de autenticação primário ou backend da aplicação.

---

## 5. Estratégia de Branches e Commits

- **Convenção de Commits**: Padrão [Conventional Commits](https://www.conventionalcommits.org/):
  - `feat: <descrição>` para novas funcionalidades;
  - `fix: <descrição>` para correção de bugs;
  - `docs: <descrição>` para documentação;
  - `chore: <descrição>` para tarefas de manutenção, dependências e build;
  - `refactor: <descrição>` para refatorações que não alteram comportamento;
  - `test: <descrição>` para adição ou ajuste de testes.
- **Ramos**:
  - `main`: Código pronto para produção e releases estáveis.
  - `develop`: Ramo de integração dos desenvolvimentos correntes.
  - `feature/<nome-da-funcionalidade>`: Branches de curta duração para desenvolvimento incremental.
  - `hotfix/<nome-do-bug>`: Correções urgentes derivadas diretamente da `main`.

---

## 6. Padronização de APIs REST

- **Versionamento de URL**: Obrigatório prefixar todas as rotas com `/api/v1/`.
- **Formato de Erro**: Adoção do padrão [RFC 7807 (Problem Details for HTTP APIs)](https://tools.ietf.org/html/rfc7807) para todas as respostas de erro (`type`, `title`, `status`, `detail`, `instance`, `errors`, `timestamp`).
- **Idempotência**: Requisições de mutação sensíveis (ex: criação de avaliação, processamento de pagamento futuro) devem aceitar o cabeçalho `Idempotency-Key`.
