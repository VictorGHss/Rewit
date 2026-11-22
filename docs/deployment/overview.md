# ESTRATÉGIA DE DEPLOYMENT E AMBIENTES (docs/deployment/overview.md)

Este documento descreve a topologia de ambientes e o ciclo de implantação da plataforma **Rewit**.

---

## 1. Topologia de Ambientes

```
[ Ambiente Local (Notebook) ] ──► [ Ambiente de Staging (Homologação) ] ──► [ Produção (Cloud) ]
  • Docker Compose                  • Docker / Kubernetes                    • Kubernetes / Managed Services
  • MinIO Local                      • MinIO / S3 Teste                       • AWS S3 / Cloudflare R2
  • Cloudflare Tunnel                • Domínio de Staging                     • Domínio Oficial / CDN Global
```

### Ambientes:
1. **Local (Desenvolvimento)**:
   - Execução local via Docker Compose com PostgreSQL+PostGIS, Redis e MinIO.
   - Variáveis lidas do arquivo `.env`.
   - Baixo consumo de hardware; testado em notebooks convencionais.
2. **Staging (Homologação)**:
   - Ambiente espelho de produção para validação de builds e testes ponta-a-ponta com o app mobile antes de publicar nas lojas.
3. **Produção**:
   - PostgreSQL gerenciado com réplicas de leitura e backups contínuos com retenção (PITR).
   - Redis Cluster para alta disponibilidade de cache e rate limiting (planejado: hoje o rate limiting é em memória e o Redis não é usado por nenhum fluxo).
   - Armazenamento em nuvem com CDN global (Cloudflare + S3/R2).

---

## 2. Gestão de Segredos e Variáveis

- **Desenvolvimento Local**: Gerenciado via arquivo `.env` (ignorado no Git).
- **Produção e CI/CD**:
  - Proibido salvar arquivos `.env` em servidores ou imagens Docker.
  - Injeção através de gerenciadores de segredos seguros (AWS Secrets Manager, HashiCorp Vault ou GitHub Actions Encrypted Secrets).
- **Separação de Credenciais de Banco e Segredos Criptográficos (ADR-017 / ADR-018 / F2-DEPLOY-02 / F2-DEPLOY-02-R1)**:
  - O PostgreSQL utiliza quatro credenciais independentes: role de migração/owner (exclusiva do Flyway), `rewit_app` (`APP_DB_PASSWORD`, tráfego público comum), `rewit_media` (`MEDIA_DB_PASSWORD`, transações de ciclo de vida público/automático de mídias `@MediaLifecycleTransactional`, SEC-F2-03) e `rewit_mod` (`MOD_DB_PASSWORD`, transações administrativas `@ModerationTransactional`).
  - **Requisito obrigatório de startup (fail-closed)**: a aplicação exige `APP_DB_USER=rewit_app`, `MEDIA_DB_USER=rewit_media` e `MOD_DB_USER=rewit_mod` com senhas independentes entre si e diferentes da credencial migrator/owner (`FLYWAY_DB_*` / `POSTGRES_*`), sem fallback de `POSTGRES_USER` nem parâmetros `user`/`password` embutidos na URL JDBC. A validação de propriedades executa antes do Flyway; em seguida, `PostgresRuntimeRoleIdentityValidator` comprova nas conexões reais de cada pool que `session_user` e `current_user` coincidem com a role esperada, que a role mantém `NOSUPERUSER NOCREATEROLE NOCREATEDB NOREPLICATION NOBYPASSRLS NOINHERIT` e zero memberships, e que não é proprietária do banco atual (`pg_database.datdba`) nem de qualquer schema (`pg_namespace.nspowner`). Qualquer divergência ou falha na consulta aborta a inicialização antes de o backend aceitar tráfego. Ownership individual de objetos (tabelas/funções) e auditoria contínua de privilégios continuam sendo validados por `provision-runtime-role.sql` e `rewit_assert_runtime_privileges()`.
  - `MODERATION_AUDIT_SECRET` (>= 32 bytes CSPRNG) deve ser mantido fora do banco de dados, independente de `JWT_SECRET`, `PASSWORD_PEPPER_SECRET`, `REFRESH_TOKEN_SECRET`, `APP_DB_PASSWORD`, `MEDIA_DB_PASSWORD` e `MOD_DB_PASSWORD`. A rotação é controlada via `MODERATION_AUDIT_KEY_ID` + par opcional `MODERATION_AUDIT_SECRET_PREVIOUS` / `MODERATION_AUDIT_PREVIOUS_KEY_ID`.
  - **Evolução operacional opcional (multi-JVM)**: enquanto a API pública, o ciclo de vida de mídias e os módulos administrativos executam no mesmo monólito JVM, RCE na JVM pública ainda teria acesso em memória às credenciais configuradas. Em ambientes de maior criticidade, a arquitetura de roteamento e privilégios já permite separar o deploy em containers JVM distintos (`rewit-api-public` apenas com `rewit_app`; `rewit-api-admin` isolado em rede interna/VPN com `rewit_mod` e `MODERATION_AUDIT_SECRET`).

---

## 3. Observabilidade V1 (ADR-012)

Detalhes em [observability.md](../architecture/observability.md).

- **Portas**: a API fica em `SERVER_PORT` (padrão `8080`); o Actuator fica só em `MANAGEMENT_SERVER_PORT` (padrão `8081`), com `health`, `info` e `prometheus`.
- **Rede**: a porta de management não deve ser publicada externamente; a proteção é configuração de deploy. No `docker-compose.yml` só a `8080` é publicada, e um Prometheus na rede `rewit-network` acessa `rewit-backend:8081`. Probes de saúde usam `/actuator/health` na porta de management.
- **Métricas**: scrape Prometheus em `/actuator/prometheus`, sem credencial e sem fornecedor obrigatório.
- **Traces**: OTLP/HTTP, desligado até `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` ser definido; sampling por `TRACING_SAMPLING_PROBABILITY` (padrão `0.1`; `1.0` no perfil `local`).
- **Logs**: stdout, em JSON por padrão (`LOG_STRUCTURED_FORMAT=logstash`); o perfil `local` usa texto. O compose sobe o backend com o perfil `local`.
