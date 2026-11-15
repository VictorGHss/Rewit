# REWIT - REGRAS GLOBAIS DO AGENTE

Você está trabalhando no repositório Rewit.

Seu trabalho deve seguir estas regras em TODA tarefa.

Referências do projeto que complementam estas regras:

* `PROJECT_RULES.md`;
* `ARCHITECTURE.md`;
* `docs/decisions/` (ADRs);
* `SECURITY.md`.

## 1. OBJETIVO E ESCOPO

Implementar apenas o escopo solicitado, preservando a arquitetura, contratos, segurança, consistência do banco e comportamento existente.

Nunca ampliar o escopo por iniciativa própria.

Não fazer refactors "aproveitando a oportunidade" fora do escopo.

Quando encontrar outro problema fora do escopo:

* registre no relatório;
* não corrija automaticamente;
* continue somente com a tarefa autorizada.

### 1.1 Contexto Antes da Execução (Obrigatório)

Todo agente deve seguir este protocolo antes de alterar qualquer arquivo:

1. **Ler as instruções aplicáveis**: ler `AGENTS.md` e o arquivo específico do seu agente (`GEMINI.md` / `CLAUDE.md`);
2. **Inspecionar a arquitetura existente**: consultar `ARCHITECTURE.md` e ADRs pertinentes;
3. **Identificar o estado atual do Git**: executar inspeção de baseline (`git status -sb`, branch, SHA do HEAD e worktrees);
4. **Ler os documentos relacionados à tarefa**: mapear previamente os documentos técnicos afetados pelo escopo;
5. **Identificar dependências e consumidores**: analisar consumidores móveis, web e contratos HTTP antes de qualquer alteração de contrato ou schema;
6. **Nunca presumir que uma funcionalidade existe**: validar diretamente no código do HEAD real em vez de confiar apenas em planejamentos ou roadmaps.

## 2. GIT E WORKTREES

Antes de iniciar qualquer modificação:

* executar `git status -sb`;
* confirmar branch atual e worktree em uso;
* confirmar o SHA do commit HEAD real;
* inspecionar remotes e histórico recente;
* verificar se a working tree está limpa e identificar eventuais arquivos ignorados ou alterações preservadas.

Nunca presuma que o estado do repositório é igual ao relatado anteriormente. Inspecione o estado atual.

Regras mandatórias de Git:

* **Trabalho exclusivo na worktree indicada**: trabalhar estritamente no diretório e na worktree atribuída pelo usuário;
* **Confirmação de branch e SHA**: confirmar que a branch e o SHA correspondem ao baseline esperado antes de fazer qualquer modificação;
* **Proteção estrita da `main`**: a branch `main` e a worktree raiz são protegidas; nenhum agente comita, altera, faz rebase ou faz merge diretamente na `main`;
* **Proibição de operações destrutivas**: nunca executar `git reset --hard`, `git clean -fd`, `git rebase` ou force push sem autorização expressa e explícita do usuário;
* **Não misturar escopos**: não misturar tarefas independentes na mesma branch;
* **Preservação de outros agentes e lanes**: não alterar, apagar, mesclar ou fazer rebase de branches e worktrees de outros agentes ou de outros lanes;
* **Preservação de arquivos locais**: preservar alterações não commitadas e arquivos locais de configuração de outros trabalhos;
* **Commits locais disciplinados**: criar commit local apenas ao concluir uma unidade lógica ou quando solicitado pelo usuário; não publicar commits automaticamente;
* **Push estritamente autorizado**: nunca executar `git push` sem autorização explícita do usuário; ao publicar, usar push normal (sem force) e conferir que o remoto é ancestral do HEAD local;
* **Deixar a working tree limpa** ao final da tarefa.

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

## 4. ENGENHARIA E ARQUITETURA

Respeitar a Clean Architecture existente e as fronteiras modulares.

Regras mandatórias:

* **Reutilizar abstrações existentes**: antes de criar novas classes, serviços ou componentes, inspecione as fundações já estabelecidas;
* **Evitar duplicação**: não criar código paralelo, estruturas redundantes ou padrões concorrentes;
* **Preservar contratos**: não quebrar contratos HTTP RESTful, DTOs compartilhados ou interfaces de domínio;
* **Separar responsabilidades**:
  * domínio não depende de infraestrutura nem de apresentação;
  * casos de uso pertencem à camada de aplicação;
  * infraestrutura implementa portas e adaptadores;
  * apresentação expõe os contratos HTTP ou interfaces gráficas;
  * não introduzir acesso direto ao JPA/SQL dentro do domínio;
  * não colocar lógica de negócio em controllers ou widgets de UI;
* **Proibição de telas falsas e mocks enganosos**: não criar telas falsas, atalhos cosméticos ou mocks apresentados como funcionalidades reais; quando uma funcionalidade depender de backend ainda não implementado, apresentar estado honesto de indisponibilidade (como feito na aba Coleção);
* **Não alterar backend sem necessidade comprovada**: alterações na API ou no banco exigem justificativa explícita e alinhamento com o lane Core;
* **Não adicionar dependências sem justificativa**: avaliar impacto, compatibilidade e alternativas nativas antes de sugerir bibliotecas;
* **Compatibilidade entre Mobile e Backend**: garantir que clientes móveis e web consumam rigorosamente os mesmos contratos e tipos expostos pelo backend.

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

## 6. SEGURANÇA, PRIVACIDADE E SEGREDOS

Regras de segurança e segredos:

* **Não registrar tokens, senhas ou chaves**: nunca imprimir em logs Authorization, JWT, refresh tokens, token hash, senhas, chaves criptográficas ou segredos;
* **Não exibir conteúdo sensível de `.env`**: arquivos `.env` locais contêm credenciais reais; nunca imprimi-los na íntegra no terminal, no chat ou em relatórios;
* **Não versionar credenciais**: nunca commitar arquivos `.env`, chaves privadas, certificados ou tokens em git;
* **Preservar arquivos locais de configuração**: respeitar `local.properties`, keystores locais, tokens de túnel e volumes de banco existentes;
* **Não alterar autenticação ou autorização sem análise de impacto**: modelos de sessão, verificação de papéis (`ModeratorRolePolicy`) e JWT só podem ser modificados mediante plano arquitetural formal;
* **Não utilizar contas pessoais do usuário**: nunca utilizar dados cadastrais reais, logins pessoais ou e-mails privados em testes ou fixtures;
* **Privacidade de dados pessoais e localização**:
  * não registrar e-mail, CPF, endereço, coordenadas exatas, IP, User-Agent ou dados pessoais desnecessários em logs de aplicação;
  * não adicionar `userId`, `sessionId` ou UUIDs como labels de métricas;
  * não introduzir rastreamento contínuo de localização em segundo plano;
  * preservar anonimato público quando previsto pelo domínio.

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

## 9. TESTES E VALIDAÇÃO

Toda funcionalidade nova ou comportamento alterado deve possuir cobertura de testes automatizados apropriada.

Regras mandatórias:

* **Executar testes direcionados**: rodar testes das unidades e componentes alterados durante o ciclo de desenvolvimento;
* **Executar suíte completa**: rodar a suíte completa de testes relevante para o escopo antes da entrega (`flutter test`, `./gradlew :app:testDebugUnitTest`, `mvn clean test`, etc.);
* **Executar análise estática**:
  * Mobile: `dart analyze` (0 issues);
  * Backend: compilação limpa, zero diagnostics Java/JDT;
* **Executar verificação de diff**: `git diff --check` deve retornar limpo (sem whitespace errors ou marcadores de merge);
* **Não mascarar falhas**: nunca silenciar asserções, testes quebrados ou exceções para simular aprovação;
* **Não remover testes**: nunca excluir ou desativar testes existentes apenas para fazer a suíte passar;
* **Não declarar testes aprovados sem executar**: nunca relatar resultado de testes que não foram efetivamente rodados no ambiente;
* **Diferenciar testes automatizados de testes manuais**: explicitar no relatório a distinção entre validação automatizada (CI/test runners) e homologação manual em dispositivo físico ou emulador.

Objetivo obrigatório:
* 0 falhas;
* 0 erros;
* 0 testes ignorados inesperadamente;
* 0 diagnostics / warnings novos de compilação.

## 10. DIAGNÓSTICOS

Não utilizar `@SuppressWarnings` para esconder problemas encontrados durante a implementação.

Corrigir a causa real sempre que possível.

Dar preferência a implementações simples e explícitas quando o compilador/JDT demonstrar comportamento problemático.

## 11. DOCUMENTAÇÃO OBRIGATÓRIA

> **REGRA FUNDAMENTAL E INEGOCIÁVEL**:
> **Toda tarefa que modificar código deve revisar e atualizar a documentação técnica correspondente.**

### Procedimento Obrigatório em 7 Passos:

1. **Antes de alterar código**: identificar os documentos relacionados (README, ARCHITECTURE, ADRs, contratos de API em `docs/api/`, guias de desenvolvimento);
2. **Durante a implementação**: manter os documentos afetados sincronizados com a evolução do código;
3. **Sincronizar detalhes técnicos**: atualizar diagramas de arquitetura, contratos de rotas, DTOs, configurações e comandos conforme a mudança implementada;
4. **Atualizar exemplos**: revisar e corrigir exemplos práticos ou trechos de código que tenham ficado incompatíveis;
5. **Conferir referências cruzadas**: validar links relativos, referências de arquivos e consistência entre documentos;
6. **Revisar o diff documental**: verificar o `git diff` da documentação antes de criar qualquer commit;
7. **Informar no relatório**: listar explicitamente no relatório final todos os documentos atualizados e o motivo.

### Diretrizes Adicionais de Documentação:

* **Justificativa explícita de ausência**: se uma tarefa com alteração de código não demandar alteração documental, o agente deve justificar formalmente no relatório;
* **Sem redundância**: não criar novos arquivos Markdown se o assunto puder ser acomodado em documentos canônicos existentes;
* **Sem funcionalidade falsa**: nunca documentar como concluídas funcionalidades que são apenas experimentais, parciais ou planejadas no roadmap;
* **Auditorias exclusivamente de leitura**: tarefas designadas como auditorias de leitura não devem modificar arquivos de documentação técnica.

Documentos canônicos de referência:
* `README.md`;
* `ARCHITECTURE.md`;
* `CHANGELOG.md`;
* `docs/decisions/` (ADRs);
* `docs/api/` (contratos REST);
* `docs/development/` (setup, versões, padrões);
* `docs/security/` (privacidade e segurança).

## 12. DEPENDÊNCIAS

Não adicionar biblioteca nova sem justificar:

* necessidade;
* compatibilidade;
* impacto;
* alternativa considerada.

Preferir dependências já gerenciadas pelo ecossistema (`pubspec.yaml`, Spring Boot/BOM Maven, Gradle).

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

1. entender o código existente e inspecionar o HEAD real;
2. localizar o ponto exato da mudança;
3. verificar testes existentes;
4. identificar documentos e ADRs relevantes;
5. verificar ausência de conflitos com outros agentes.

Durante:

* alterar somente o necessário para o escopo autorizado;
* manter padrões estilísticos e arquiteturais existentes;
* adicionar testes automatizados junto da implementação;
* atualizar a documentação técnica afetada.

Depois:

* executar validações completas (`dart analyze`, `flutter test`, gradle/mvn);
* executar `git diff --check`;
* revisar o diff completo (código e documentação);
* criar commit local atômico quando solicitado;
* deixar a working tree limpa.

## 15. RELATÓRIO FINAL OBRIGATÓRIO

Ao concluir qualquer tarefa de implementação, responda obrigatoriamente com:

### Git e Baseline
* SHA inicial e final;
* branch e worktree utilizadas;
* estado final da working tree;
* commits criados (mensagem e hash).

### Implementação
* o que foi alterado e funcionalidades entregues;
* arquivos modificados;
* decisões técnicas e invariantes preservadas.

### Documentação Técnica
* documentos técnicos atualizados;
* documentos revisados sem alteração (com justificativa);
* resumo do diff documental.

### Testes e Validação
* quantidade total de testes executados;
* falhas, erros e testes ignorados;
* resultado da análise estática (`dart analyze`, compilação);
* resultado do `git diff --check`;
* distinção entre testes automatizados e testes manuais em hardware/emulador.

### Riscos, Limitações e Pendências
* limitações da implementação atual;
* riscos operacionais identificados;
* pendências fora do escopo (com severidade e evidências no caso de auditorias).

Nunca faça push sem autorização explícita do usuário.
