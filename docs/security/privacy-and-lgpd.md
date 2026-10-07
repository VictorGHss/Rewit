# PRIVACIDADE, SEGURANÇA E CONFORMIDADE COM LGPD (docs/security/privacy-and-lgpd.md)

Este documento estabelece as diretrizes de proteção de dados, privacidade por design e arquitetura segura para o projeto **Rewit**, em conformidade com a Lei Geral de Proteção de Dados Pessoais (LGPD - Lei nº 13.709/2018).

---

## 1. Princípios de Privacidade por Design (Privacy by Design)

1. **Minimização de Dados (Data Minimization)**: Coletar apenas os dados estritamente indispensáveis para o funcionamento da plataforma. Não solicitar dados como CPF, telefone pessoal ou contatos da agenda a menos que vinculados a um fluxo legal obrigatório (ex: emissão de nota fiscal para contas comerciais).
2. **Finalidade e Transparência**: O usuário deve ter clareza total sobre o motivo da solicitação de cada permissão no aplicativo (câmera para escanear/fotografar produtos, localização sob demanda para verificar presença).

---

## 2. Política Estrita de Geolocalização

- **Proibição de Rastreamento Contínuo**: O Rewit **não** possui recursos de gravação de rotas, trilhas de GPS em segundo plano ou telemetria de tráfego contínua.
- **Localização Sob Demanda**:
  - As coordenadas geográficas do usuário só são lidas no instante da submissão de uma ação que requer geolocalização.
  - Uma vez calculada a distância em relação ao estabelecimento para fins de verificação do check-in, as coordenadas da residência ou do trânsito do usuário são descartadas da memória de trabalho.
  - Apenas as coordenadas do evento de check-in (atestando que o usuário esteve no estabelecimento comercial) são persistidas.

---

## 3. Higienização de Imagens (Remoção Obrigatória de Metadados EXIF)

Toda foto capturada por smartphones modernos embute metadados EXIF detalhados no arquivo binário JPEG/PNG, incluindo:
- Latitude e Longitude exatas do disparo da foto;
- Modelo do aparelho, número de série e versão de software;
- Data e hora com frações de segundo.

### Invariante de Segurança:
- O backend do Rewit executa um pipeline de processamento de imagem na chegada de qualquer arquivo de mídia (antes da gravação no MinIO/S3).
- **Ação**: O arquivo é decodificado e re-codificado utilizando biblioteca de manipulação gráfica (ex: TwelveMonkeys ImageIO / Thumbnailator), eliminando 100% dos blocos EXIF, IPTC e XMP.
- Imagens públicas servidas pelo CDN/MinIO nunca conterão coordenadas geográficas embutidas.

---

## 4. Avaliações Anônimas e Governança de Moderação

- **Experiência do Usuário**: O usuário pode selecionar a opção *"Publicar anonimamente"* ao escrever uma crítica.
- **Camada de Apresentação**:
  - O JSON de resposta da API oculta o `user_id`, `handle` e `avatar_url` do autor perante outros usuários, exibindo apenas um identificador anônimo genérico (ex: *"Membro da Comunidade"*).
- **Camada Interna e Governança Legal**:
  - Em conformidade com a legislação brasileira (Marco Civil da Internet - Art. 10 da Lei 12.965/2014), que veda o anonimato absoluto com fins ilícitos, a base de dados interna preserva o `user_id` original em campo restrito da tabela `reviews`.
  - Esse vínculo só pode ser acessado por auditores de moderação sob processo de denúncia formal ou mediante requisição judicial fundamentada, prevenindo calúnia, difamação e ataques comerciais orquestrados.

---

## 5. Proteção de Credenciais e Tratamento de Logs

1. **Hashing de Senhas**: Utilização obrigatória de algoritmo moderno de derivação de chaves: **BCrypt** com fator de trabalho (cost) 12 ou **Argon2id**.
2. **Tokens JWT de Sessão**:
   - Assinatura com algoritmo assimétrico ou chave secreta forte (mínimo 256 bits via variável `JWT_SECRET`).
   - Tempo de expiração curto para access tokens (ex: 15 a 60 minutos) com refresh token opaco, rotacionado a cada uso e persistido no PostgreSQL apenas como hash SHA-256 (tabela `auth_sessions`), com detecção de reúso. Não há estado de sessão em Redis.
3. **Prevenção de Vazamento em Logs**:
   - É terminantemente proibido registrar em logs de aplicação (SLF4J/Logback):
     - Senhas ou hashes;
     - Cabeçalhos `Authorization`;
     - Coordenadas geográficas residenciais;
     - E-mails não mascarados.
   - A política completa de logs, traces e métricas está na §7.

---

## 6. Retenção de Dados de Sessão (`auth_sessions`)

Registro técnico do que o código faz; não é parecer jurídico.

- **Dados de segurança** (`user_id`, `token_hash`, timestamps de emissão, expiração e revogação, `replaced_by_session_id`): mantidos enquanto sustentam o refresh e a detecção de reúso. Uma sessão só é removida quando está expirada e não tem sucessora de rotação (ADR-011). Em cadeias de rotação de usuários ativos, esse histórico permanece enquanto a cadeia estiver viva.
- **Dados técnicos pessoais** (`ip_address`, `user_agent`): capturados no registro, login e refresh e **não lidos por nenhum código**. O job de cleanup os apaga (`NULL`) assim que a sessão deixa de estar ativa (revogada ou expirada), sem esperar a remoção da linha. Assim, a retenção de segurança da cadeia não implica reter IP e user agent.
- **Pendente (produto/jurídico)**: a coleta e a retenção de `ip_address` e `user_agent` **enquanto a sessão está ativa** (até o `expires_at`, 30 dias após o último refresh com o TTL padrão). Nenhum uso funcional desses dados existe hoje.

---

## 6.1 Conta Excluída (`DELETED`) e Purge (C2.3)

Registro técnico do que o código faz; não é parecer jurídico.

- **Leitura pública (C2.2)**: a identidade de uma conta excluída não aparece em nenhuma projeção pública; perfil, follows e reputação respondem como um identificador inexistente.
- **Purge (`PurgeDeletedAccountUseCase`)**, idempotente e restrito a contas `DELETED`:
  - **Removido**: sessões, follows (nos dois sentidos), itens salvos, interesses, atividades, notificações da conta e snapshot de reputação.
  - **Minimizado**: e-mail (valor reservado, sem o endereço; ver `architecture/persistence.md`), senha e id do provedor externo; perfil (handle reservado, nome "Usuário excluído", sem bio, avatar e reputação); coordenadas informadas nas avaliações (`reviews.user_coordinates`, `location_accuracy_meters`); o ator excluído nas notificações de outros usuários e o autor de presenças de produto.
  - **Preservado**: a linha de `users` (com `account_status = DELETED` e `deleted_at`), avaliações, notas, helpful, comentários, mídia, denúncias e auditoria de moderação.
- **Pendente (produto/jurídico)**:
  - `check_ins.coordinates` é `NOT NULL` e o check-in sustenta `is_verified_on_site` da avaliação: removê-lo altera o histórico verificado, e anular a coordenada exige mudança de schema. Os check-ins permanecem.
  - `business_accounts` (razão social e documento fiscal) não tem fluxo de transferência nem política de encerramento; permanece.
  - Objetos de mídia continuam no storage enquanto as avaliações existirem; a remoção física depende de política de retenção (ADR-010).
  - Backups (PITR) mantêm os dados anteriores ao purge pelo período de retenção, sem procedimento de reaplicação após restore.

## 7. Observabilidade: Logs, Traces e Métricas (ADR-012)

Registro técnico do que o código faz; não é parecer jurídico. Arquitetura em [observability.md](../architecture/observability.md).

- **Logs estruturados**: JSON no stdout (formato `logstash` do Spring Boot), em texto no perfil `local`. O stack trace sai sem as mensagens das exceções, que podem carregar SQL, URLs ou chaves de objeto.
- **Nunca aparecem em logs, spans ou labels de métricas**: `userId`, `sessionId`, access token, refresh token, hash de token, cabeçalho `Authorization`, IP, User-Agent, e-mail, query string (que pode conter coordenadas), corpo de requisição, SQL e valores de parâmetros, mensagens de exceção de terceiros.
- **Sem `userId`**: nem em logs de negócio nem em eventos de segurança. Com reviews anônimas (§4), `userId` ao lado de `reviewId` em um log desanonimizaria o autor para quem tem acesso aos logs. A correlação de uma requisição é feita pelo `traceId`.
- **Permitido com restrição**: `objectKey` de mídia (`reviews/{reviewId}/{mediaId}/...`) somente nos logs de falha de upload e remoção que já existiam; ids técnicos do Outbox (`messageId`, workerId).
- **Tracing**: só requisições HTTP recebidas e chamadas ao Google Places. Atributos: método, rota normalizada, status, resultado, classe da exceção e o path da requisição (`http.url`, sem query string, podendo conter UUIDs). Sem tracing de banco de dados. A exportação só ocorre para um endpoint OTLP configurado explicitamente no deploy; o acesso ao backend de traces deve ser restrito.
- **Métricas**: agregadas, sem identificadores, servidas só na porta de management interna; usuários da API não têm acesso.

---

## 8. Rate Limiting (ADR-013)

Registro técnico do que o código faz; não é parecer jurídico.

- **Sem IP e sem User-Agent**: os limites usam e-mail normalizado (login), id de usuário (refresh, denúncias, comentários, uploads) ou um contador global (cadastro).
- **Pseudonimização**: o Redis guarda só o HMAC-SHA256 do sujeito, com um segredo de configuração (`RATE_LIMIT_KEY_SECRET`), e os instantes das tentativas da janela. A chave expira sozinha uma janela após a última tentativa (no máximo 15 minutos com os padrões).
- **Nunca em logs, métricas ou chaves em claro**: e-mail, id de usuário, chave derivada ou o hash. As métricas têm apenas ação, resultado e origem da decisão.
