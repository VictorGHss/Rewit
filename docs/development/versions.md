# MATRIZ DE VERSÕES DO PROJETO (docs/development/versions.md)

Este documento registra formalmente as versões estáveis homologadas e suportadas para a fundação e desenvolvimento contínuo da plataforma **Rewit**. Nenhuma versão beta, alpha, snapshot ou release candidate (RC) é admitida em ambiente de produção.

---

## 1. Tabela Consolidada de Tecnologias e Versões

| Componente | Linha / Versão | Status Oficial | Data de Lançamento / Suporte | Finalidade no Projeto |
| :--- | :--- | :--- | :--- | :--- |
| **Java** | **Java 25 LTS** | Versão LTS Estável | Suporte oficial ativo | Linguagem principal do Backend central |
| **Spring Boot** | **4.1.1** (Linha 4.1.x) | Versão Estável Atual | Versão estável suportada | Framework central de microsserviços e DI |
| **Spring Framework** | **7.1.x** | Versão Estável Atual | Base do Spring Boot 4.1.x | Concorrência Virtual Threads e Java 25 |
| **PostgreSQL** | **PostgreSQL 18.x** | Versão Estável | Versão estável atual | SGBD primário e fonte canônica da verdade |
| **PostGIS** | **3.6.x** | Versão Estável | Integrada à imagem oficial | Extensão geoespacial (`GEOGRAPHY`, índices `GiST`) |
| **Imagem PostGIS Docker** | `postgis/postgis:18-3.6` | Imagem Oficial | Compatível com PG 18 | Execução conteinerizada local |
| **Flyway** | **11.x** | Versão Estável | Compatível PG 18 e Java 25 | Gerenciamento e versionamento de migrations |
| **Redis** | **Linha 8.x estável** (`8-alpine`) | Versão Estável | Versão estável compatível | Cache de sessões, rate limiting e feeds |
| **SeaweedFS** | `chrislusf/seaweedfs:4.47` | Versão Fixada | Compatível com API AWS S3 | Armazenamento de objetos (fotos e mídias sanitizadas) |
| **Node.js** | **24 LTS** | Versão LTS Estável | Suporte ativo de longo prazo | Runtime JavaScript dos clientes Web e ferramentas |
| **Flutter SDK** | **3.47 stable** | Versão Estável Oficial | Canal Stable homologado | Cliente mobile multiplataforma (Android & iOS) |
| **React** | **19.3** | Versão Estável Oficial | Suporte a Server/Client Components | Biblioteca de interface do Painel Admin |
| **TypeScript** | **5.8.x** | Versão Estável Oficial | Tipagem estática rigorosa | Linguagem do Painel Administrativo |
| **Vite** | **8.x** | Versão Estável Oficial | Bundler ultrarrápido ESM | Ferramenta de build e dev server do Admin |
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

### SeaweedFS 4.47 (Object Storage Local S3-Compatible)
Para desenvolvimento local e persistência de fotos e mídias, o projeto adota o **SeaweedFS 4.47** em modo single-node (`weed mini`).
- Endpoint S3 local: `http://localhost:8333`
- Endpoint Master/Admin: `http://localhost:9333` (com `/cluster/status` para healthcheck)
- Bucket padrão de desenvolvimento: `${OBJECT_STORAGE_BUCKET:-rewit-local}`
- O backend interage através de uma abstração de object storage S3-compatible desacoplada do provedor subjacente, permitindo transição transparente para AWS S3 ou Cloudflare R2 em produção.
