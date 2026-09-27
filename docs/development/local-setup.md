# GUIA DE CONFIGURAÇÃO E AMBIENTE LOCAL (docs/development/local-setup.md)

Este guia orienta a preparação do ambiente de desenvolvimento local na máquina de trabalho (Windows, macOS ou Linux).

---

## 1. Pré-Requisitos Recomendados

| Ferramenta | Versão Recomendada | Finalidade |
| :--- | :--- | :--- |
| **Java JDK** | 21 LTS (Temurin / Corretto) | Execução do Backend Spring Boot |
| **Maven** | 3.9+ (ou `./mvnw`) | Build e gerenciamento do Java |
| **Docker Desktop** | 26+ (com Compose v2) | Orquestração da infraestrutura de banco e cache |
| **Node.js** | 20 LTS (LTS Iron) | Execução e build do Admin React |
| **Flutter SDK** | 3.24+ | Desenvolvimento do app mobile |
| **Git** | 2.40+ | Controle de versão |

---

## 2. Passo a Passo Inicial

### Passo 1: Configuração do Arquivo de Ambiente
Na raiz do repositório, duplique o modelo de exemplo para criar seu arquivo local:
```powershell
Copy-Item .env.example .env
```
*(No Linux/macOS: `cp .env.example .env`)*.

### Passo 2: Inicialização da Infraestrutura Local
Suba os containers essenciais (PostgreSQL + PostGIS, Redis e SeaweedFS):
```powershell
docker compose up -d
```
Verifique se os serviços estão com status saudável (*healthy*):
```powershell
docker compose ps
```

### Passo 3: Execução do Backend
Acesse a pasta do backend e execute a aplicação:
```powershell
cd backend
./mvnw spring-boot:run
```
O backend estará acessível em: `http://localhost:8080`.
Documentação Swagger/OpenAPI: `http://localhost:8080/swagger-ui.html`.

### Passo 4: Execução do Painel Admin
Em um novo terminal:
```powershell
cd apps/admin
npm install
npm run dev
```
O painel estará acessível em: `http://localhost:5173`.

### Passo 5: Execução do Aplicativo Mobile
Conecte um dispositivo Android/iOS físico ou inicie um emulador:
```powershell
cd apps/mobile
flutter pub get
flutter run
```

---

## 3. Tabela de Portas e Acessos Locais

| Serviço | Porta do Host | Credenciais Padrão (Ambiente Local) | Finalidade |
| :--- | :--- | :--- | :--- |
| **PostgreSQL + PostGIS** | `5432` | Definido no `.env` (Padrão: `rewit_user` / `rewit_db`) | Banco de Dados Primário |
| **Redis** | `6379` | Sem senha em desenvolvimento | Cache e Rate Limiting |
| **SeaweedFS S3 API** | `8333` | Configurado via `.env` (Bucket: `rewit-local`) | API S3 de Upload de Fotos e Mídias |
| **SeaweedFS Master UI** | `9333` | N/A | Painel de Status / Topologia |
| **SeaweedFS Filer UI** | `8888` | N/A | Navegador de Arquivos e Buckets |
| **Backend API** | `8080` | N/A | API REST Central |
| **Admin Web** | `5173` | N/A | Interface de Gestão React |

---

## 4. Uso do Cloudflare Tunnel para Testes Móveis

Para permitir que o aplicativo mobile rodando em um celular físico conectado na rede móvel 4G/5G acesse seu backend rodando no notebook:
1. Instale o executável `cloudflared` ou utilize o profile Docker preparado no `docker-compose.yml`:
   ```powershell
   docker compose --profile tunnel up -d cloudflared
   ```
2. Configure seu token no arquivo `.env` sob `CLOUDFLARE_TUNNEL_TOKEN`.
