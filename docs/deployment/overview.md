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

---

## 3. Observabilidade V1 (ADR-012)

Detalhes em [observability.md](../architecture/observability.md).

- **Portas**: a API fica em `SERVER_PORT` (padrão `8080`); o Actuator fica só em `MANAGEMENT_SERVER_PORT` (padrão `8081`), com `health`, `info` e `prometheus`.
- **Rede**: a porta de management não deve ser publicada externamente; a proteção é configuração de deploy. No `docker-compose.yml` só a `8080` é publicada, e um Prometheus na rede `rewit-network` acessa `rewit-backend:8081`. Probes de saúde usam `/actuator/health` na porta de management.
- **Métricas**: scrape Prometheus em `/actuator/prometheus`, sem credencial e sem fornecedor obrigatório.
- **Traces**: OTLP/HTTP, desligado até `MANAGEMENT_OPENTELEMETRY_TRACING_EXPORT_OTLP_ENDPOINT` ser definido; sampling por `TRACING_SAMPLING_PROBABILITY` (padrão `0.1`; `1.0` no perfil `local`).
- **Logs**: stdout, em JSON por padrão (`LOG_STRUCTURED_FORMAT=logstash`); o perfil `local` usa texto. O compose sobe o backend com o perfil `local`.
