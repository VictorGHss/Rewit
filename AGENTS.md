# REWIT - REGRAS GLOBAIS DO AGENTE

Você está trabalhando no repositório Rewit.

Seu trabalho deve seguir estas regras em TODA tarefa.

Referências do projeto que complementam estas regras:

* `PROJECT_RULES.md`;
* `ARCHITECTURE.md`;
* `docs/decisions/` (ADRs);
* `SECURITY.md`.

## 1. OBJETIVO

Implementar apenas o escopo solicitado, preservando a arquitetura, contratos, segurança, consistência do banco e comportamento existente.

Nunca ampliar o escopo por iniciativa própria.

Não fazer refactors "aproveitando a oportunidade" fora do escopo.

Quando encontrar outro problema fora do escopo:

* registre no relatório;
* não corrija automaticamente;
* continue somente com a tarefa autorizada.

## 2. GIT

Antes de começar:

* execute `git status`;
* confirme branch atual;
* confirme o commit HEAD real;
* verifique se a working tree está limpa.

Nunca presuma que o estado do repositório é igual ao relatado anteriormente. Inspecione o estado atual.

Regras obrigatórias:

* não fazer `git push`;
* não usar `git reset --hard` sem autorização explícita;
* não fazer `git clean -fd` sem autorização explícita;
* não alterar ou apagar trabalho de outro agente;
* não fazer merge da branch de outro agente;
* não fazer rebase da branch de outro agente;
* não trocar para outra branch durante a tarefa;
* criar commit local ao concluir uma unidade lógica;
* deixar a working tree limpa.

Formato recomendado da mensagem de commit:
`tipo: descrição curta`

## 3. ISOLAMENTO ENTRE AGENTES

Claude Code e Gemini Antigravity trabalham em branches/worktrees separados:

| Agente | Lane | Branch | Worktree |
|---|---|---|---|
| Claude Code | Core Engineer | `agent/claude/*` | `../Rewit-claude` |
| Gemini Antigravity | Feature Delivery Engineer | `agent/gemini/*` | `../Rewit-gemini` |

Trabalhe somente na sua worktree e na sua branch. A worktree principal (`Rewit`, branch `main`) é reservada para o mantenedor humano; nenhum agente faz commit em `main`. A integração das branches de agente é feita pelo mantenedor.

As branches `agent/*` são o equivalente, para agentes, às branches `feature/*` descritas em `PROJECT_RULES.md`.

Nunca assuma que outro agente não está modificando o mesmo repositório.

Ownership padrão por área (vale quando a tarefa não atribuir explicitamente outra coisa):

* Core (Claude Code): `domain`, regras de negócio novas ou alteradas em use cases, ports, persistência/JPA, migrations Flyway, segurança/autenticação/autorização/sessão, transações, concorrência e locking;
* Delivery (Gemini Antigravity): `presentation` (controllers, DTOs HTTP), adapters de integrações externas, frontend (`apps/`), documentação de API e operacional;
* use cases: Delivery só implementa quando a regra de negócio e os ports já estiverem definidos; caso contrário, pertence ao Core;
* testes: cada agente escreve os testes da própria mudança;
* arquivos compartilhados (`pom.xml`, `application*.yml`, `docker-compose.yml`, handler global de erros): alterar somente quando a tarefa exigir, e registrar no relatório.

Não edite arquivos fora do escopo atribuído ao seu lane.

Se uma alteração necessária depender de arquivo pertencente ao outro lane:

* não force a alteração;
* registre a dependência;
* informe exatamente qual contrato ou arquivo é necessário.

## 4. ARQUITETURA

Respeitar a Clean Architecture existente.

Regras:

* domínio não depende de infraestrutura;
* casos de uso pertencem à camada de aplicação;
* infraestrutura implementa portas;
* presentation expõe os contratos HTTP;
* não introduzir acesso direto ao JPA/SQL dentro do domínio;
* não colocar lógica de negócio em controllers;
* não criar atalhos que quebrem as fronteiras arquiteturais.

Respeitar ADRs existentes e documentação arquitetural antes de introduzir novos padrões.

## 5. BANCO DE DADOS

PostgreSQL é a fonte de verdade.

Regras:

* mudanças de schema somente via Flyway;
* Hibernate permanece como `ddl-auto=validate`;
* nunca usar Hibernate para criar ou alterar schema;
* migrations forward-only;
* nomes e constraints devem seguir o padrão existente;
* considerar concorrência, índices e integridade referencial;
* não alterar migration histórica já aplicada.

PostGIS continua sendo a fonte das operações espaciais.

## 6. SEGURANÇA E PRIVACIDADE

Nunca registrar em logs:

* Authorization;
* JWT;
* refresh token;
* token hash;
* senha;
* e-mail;
* CPF;
* endereço;
* coordenadas exatas;
* IP;
* User-Agent;
* dados pessoais desnecessários;
* payloads sensíveis.

Não adicionar `userId`, `sessionId` ou UUIDs como labels de métricas.

Não introduzir rastreamento contínuo de localização.

Preservar anonimato público quando previsto pelo domínio.

## 7. API

Preservar o contrato REST existente.

Erros HTTP devem usar o padrão RFC 7807 já adotado.

Não retornar detalhes internos de exceções para o cliente.

Exceções 4xx são erros esperados de cliente e não devem gerar log ERROR, salvo comportamento explicitamente definido.

Exceções 5xx inesperadas devem continuar sendo observáveis sem expor dados sensíveis.

Nunca transformar silenciosamente um comportamento existente sem teste que prove a nova regra.

## 8. OBSERVABILIDADE

Respeitar a implementação existente de:

* métricas Micrometer;
* Prometheus;
* logs JSON;
* tracing OpenTelemetry.

Tags devem possuir baixa cardinalidade.

Nunca usar como tag:

* usuário;
* UUID;
* e-mail;
* token;
* IP;
* coordenada;
* ID de sessão;
* qualquer valor potencialmente ilimitado.

Não introduzir logs redundantes em caminhos normais de execução.

## 9. TESTES

Toda funcionalidade nova ou comportamento alterado deve ter testes apropriados.

Antes do commit executar, quando aplicável:

`mvn clean test`

Também verificar:

* warnings de compilação;
* diagnostics Java;
* `git diff --check`.

Objetivo obrigatório:

* 0 falhas;
* 0 erros;
* 0 testes ignorados inesperadamente;
* 0 diagnostics;
* nenhuma warning nova de compilação.

Não diminuir cobertura removendo testes apenas para fazer a suíte passar.

## 10. DIAGNÓSTICOS

Não utilizar `@SuppressWarnings` para esconder problemas encontrados durante a implementação.

Corrigir a causa real sempre que possível.

Dar preferência a implementações simples e explícitas quando o compilador/JDT demonstrar comportamento problemático.

## 11. DOCUMENTAÇÃO

Documentação técnica existente está em português.

Atualize documentação somente quando a mudança realmente alterar comportamento, arquitetura, configuração, segurança, schema ou operação.

Não criar arquivos de documentação novos sem necessidade.

Respeitar especialmente:

* README;
* roadmap;
* arquitetura;
* ADRs;
* documentação de API;
* segurança/privacidade;
* deployment.

## 12. DEPENDÊNCIAS

Não adicionar biblioteca nova sem justificar:

* necessidade;
* compatibilidade;
* impacto;
* alternativa considerada.

Preferir dependências já gerenciadas pelo Spring Boot/BOM quando disponíveis.

Não introduzir vendor lock-in desnecessário.

## 13. CONFIGURAÇÃO

Não alterar defaults de produção sem necessidade.

Não colocar secrets no repositório.

Configurações novas devem possuir:

* default seguro;
* nomes consistentes;
* documentação quando operacionalmente relevante;
* configuração de teste apropriada.

## 14. PROCESSO DA TAREFA

Antes de editar:

1. entender o código existente;
2. localizar o ponto exato da mudança;
3. verificar testes existentes;
4. verificar documentação/ADR relevante;
5. verificar conflitos com o escopo de outros agentes.

Durante:

* alterar somente o necessário;
* manter estilo existente;
* adicionar testes junto da implementação.

Depois:

* executar validações;
* revisar o diff;
* confirmar que nenhuma alteração acidental entrou;
* criar commit local;
* deixar working tree limpa.

## 15. RELATÓRIO FINAL

Ao concluir, responda obrigatoriamente com:

### Baseline

* branch;
* HEAD inicial;
* estado inicial da working tree.

### Implementação

* o que foi alterado;
* arquivos principais;
* decisões importantes.

### Testes

* quantidade total;
* falhas;
* erros;
* ignorados;
* warnings;
* diagnostics;
* `git diff --check`.

### Git

* commit criado;
* hash;
* working tree final.

### Riscos / Pendências

Liste somente problemas reais encontrados durante a tarefa.

### Fora do escopo

Liste descobertas relevantes que não foram corrigidas.

Nunca faça push.
