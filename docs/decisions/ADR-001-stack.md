# ADR-001: Seleção da Stack Tecnológica Principal

## Status
Aprovado

## Contexto
O projeto **Rewit** é uma rede social geográfica de avaliações sobre o mundo físico (locais, produtos e serviços), demandando alta confiabilidade transacional, precisão em consultas geoespaciais, desempenho em processamento de feeds dinâmicos e suporte a múltiplos clientes (mobile e web administrativo). O projeto iniciará seu ciclo de vida em ambiente local (notebook de desenvolvimento) com recursos de computação limitados, exigindo baixo custo operacional inicial, porém sem comprometer a escalabilidade futura.

## Decisão
Adotamos a seguinte composição de stack tecnológica:
1. **Backend**: Java 25 LTS com Spring Boot 4.1.x (estável).
   - *Justificativa*: Suporte oficial a Virtual Threads (Project Loom) nativas, melhorias de performance da JVM, maturidade corporativa, suporte a tipos geoespaciais via Hibernate Spatial e ecossistema de microsserviços estável e suportado a longo prazo.
2. **Banco de Dados Principal**: PostgreSQL 18 com extensão PostGIS 3.6.x.
   - *Justificativa*: PostgreSQL 18 é a versão mais atual e eficiente do banco relacional de código aberto, com suporte nativo de alta performance a PostGIS 3.6 para indexação e consultas esferoidais via índices GiST.
3. **Cache e Sessões**: Redis 7.
   - *Justificativa*: Estruturas em memória ultrarrápidas para ranking, rate limiting, cache de feeds quentes e armazenamento efêmero de desafios de validação.
4. **Armazenamento de Objetos**: MinIO (local) compatível com API AWS S3.
   - *Justificativa*: Permite desenvolver e testar uploads de fotos e mídias sanitizadas localmente de forma idêntica ao que será utilizado na nuvem futuramente (AWS S3 ou Cloudflare R2), sem incorrer em custos de hospedagem na fase inicial.
5. **Cliente Mobile**: Flutter (Dart).
   - *Justificativa*: Código único para Android e iOS, desempenho quase nativo com compilação AOT (Ahead-of-Time), excelente renderização de interfaces gráficas fluidas e acesso facilitado a recursos de câmera e localização do dispositivo.
6. **Painel Administrativo**: React 18+ com TypeScript e Vite.
   - *Justificativa*: Ecossistema consagrado para criação de interfaces ricas de moderação, auditoria e gestão com alta produtividade e tipagem segura.
7. **Serviços de IA e Visão (Futuro)**: Python (FastAPI / PyTorch).
   - *Justificativa*: Python é o ecossistema dominante para extração de embeddings visuais e modelos de visão computacional, reservado como microserviço especializado para etapas futuras.

## Consequências
### Positivas:
- Total controle sobre os dados e custos de infraestrutura no início do projeto.
- Ausência de dependência de fornecedores caros ou proprietários para funções essenciais.
- Todo o ambiente de infraestrutura roda de forma padronizada via Docker Compose em notebooks convencionais.
### Negativas / Mitigações:
- O ecossistema Spring Boot possui consumo inicial de memória JVM superior a runtimes leves como Go ou Node.js. *Mitigação*: Configuração de parâmetros de memória da JVM (`-Xmx512m`) adequada ao desenvolvimento local.
