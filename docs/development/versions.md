# MATRIZ DE VERSÕES DO PROJETO (docs/development/versions.md)

Este documento registra formalmente as versões estáveis homologadas e suportadas para a fundação e desenvolvimento contínuo da plataforma **Rewit**. Nenhuma versão beta, alpha, snapshot ou release candidate (RC) é admitida em ambiente de produção.

---

## 1. Tabela Consolidada de Tecnologias e Versões

| Componente | Linha / Versão | Status Oficial | Data de Lançamento / Suporte | Finalidade no Projeto |
| :--- | :--- | :--- | :--- | :--- |
| **Java** | **Java 25 LTS** | Versão LTS Estável | 16 de Setembro de 2025 (Suporte estendido até 2033+) | Linguagem principal do Backend central |
| **Spring Boot** | **4.1.1** (Linha 4.1.x) | Versão Estável Atual | 20 de Agosto de 2026 | Framework central de microsserviços e injeção de dependências |
| **Spring Framework** | **7.1.x** | Versão Estável Atual | Base do Spring Boot 4.1.x | Suporte a Virtual Threads e Java 25 |
| **PostgreSQL** | **PostgreSQL 18** | Versão Estável | Versão principal de banco relacional | SGBD primário e fonte canônica da verdade |
| **PostGIS** | **3.6.x** | Versão Estável | Integrada à imagem oficial | Extensão geoespacial (tipos `GEOGRAPHY`, índices `GiST`) |
| **Imagem PostGIS Docker** | `postgis/postgis:18-3.6` | Imagem Oficial | Compatível com PG 18 | Execução conteinerizada local |
| **Flyway** | **11.x** | Versão Estável | Suporte a PostgreSQL 18 e Java 25 | Gerenciamento e versionamento de migrations |
| **Redis** | **7.4-alpine** / **8.0-alpine** | Versão Estável | Cache em memória de baixa latência | Cache de sessões, rate limiting e feeds |
| **MinIO** | `minio/minio:latest` | Versão Estável | Compatível com API AWS S3 | Armazenamento de objetos (fotos e mídias) |
| **Flutter SDK** | **3.24+** (Canal Stable) | Versão Estável Oficial | Suporte a Dart 3.5+ | Cliente mobile multiplataforma (Android & iOS) |
| **React** | **18.3.1** | Versão Estável Oficial | Suporte a hooks concorrentes | Biblioteca de interface do Painel Admin |
| **TypeScript** | **5.5.4** | Versão Estável Oficial | Tipagem estática rigorosa | Linguagem do Painel Administrativo |
| **Vite** | **5.4.2** | Versão Estável Oficial | Bundler ultrarrápido ESM | Ferramenta de build e dev server do Admin |
| **Python** | **3.12+** | Versão Estável Oficial | Suporte para IA e Embeddings | Ambiente reservado para `services/vision` |

---

## 2. Notas Arquiteturais Importantes sobre as Versões

### PostgreSQL 18 - Caminho do Volume de Dados
A partir do PostgreSQL 18, o caminho padrão do diretório de dados (`PGDATA`) no container oficial foi padronizado para:
```
/var/lib/postgresql
```
*(Diferente do `/var/lib/postgresql/data` utilizado nas versões 17 e anteriores)*. O arquivo `docker-compose.yml` foi devidamente atualizado para refletir esta alteração estrutural.

### Spring Boot 4.1.x e Java 25 LTS
O Spring Boot 4.1.x oferece suporte nativo e otimizado para os recursos do Java 25 LTS, incluindo:
- Concorrência de alto rendimento baseada em **Virtual Threads (Project Loom)** ativadas por padrão (`spring.threads.virtual.enabled=true`);
- Suporte aprimorado a tipos espaciais via Hibernate Spatial 7.x;
- Integração nativa com Flyway 11.x para execução de scripts SQL complexos em inicialização.
