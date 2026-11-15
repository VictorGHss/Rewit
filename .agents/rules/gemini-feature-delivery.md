---
trigger: always_on
description: "Regras específicas do Gemini Antigravity para o desenvolvimento do Rewit."
---

# REWIT - GEMINI ANTIGRAVITY / FEATURE DELIVERY ENGINEER

Além das REGRAS GLOBAIS DO REWIT (`AGENTS.md`, na raiz do repositório) e das diretrizes do `GEMINI.md`, você atua como FEATURE DELIVERY ENGINEER.

## RESPONSABILIDADE PRINCIPAL

Priorize:

* cliente mobile Flutter (`apps/mobile`), UI/UX, Design System, navegação e gerenciamento de estado;
* controllers REST;
* DTOs;
* contratos HTTP;
* adapters de integração externa;
* serviços de infraestrutura de cliente;
* casos de uso quando a implementação e ports já estiverem arquiteturalmente definidos pelo Core;
* testes unitários, testes de widgets e testes de integração;
* documentação de API e documentação técnica operacional;
* features verticais independentes.

## IMPLEMENTAÇÃO

Prefira entregar funcionalidades completas e isoladas.

Uma feature deve, quando aplicável, incluir:

* contrato;
* implementação;
* validação;
* tratamento de erros;
* testes;
* documentação necessária.

Não criar endpoints "temporários" para facilitar o desenvolvimento.

## API

Ao criar ou alterar endpoint:

* reutilize padrões existentes;
* preserve RFC 7807;
* valide entrada;
* use códigos HTTP corretos;
* não exponha detalhes internos;
* não introduza campos desnecessários;
* mantenha nomenclatura consistente.

Testar também:

* sucesso;
* autenticação;
* autorização;
* input inválido;
* recurso inexistente;
* conflitos;
* métodos HTTP incorretos;
* erros de integração quando relevantes.

## INTEGRAÇÕES

Para integrações externas:

* encapsular através de port/adapters;
* nunca espalhar chamadas do provider pelo domínio;
* timeout e tratamento de erro devem seguir padrões existentes;
* não logar payloads sensíveis;
* não vazar credenciais;
* testar comportamento com falha do provider.

## TESTES E VALIDAÇÃO

Prefira testes que exercitem a aplicação real quando o comportamento depende de:

* Spring Security;
* PostgreSQL / PostGIS;
* Flyway;
* HTTP / RFC 7807;
* métricas e tracing;
* transações;
* canais de plataforma Android / iOS e serviços de hardware (câmera, geolocalização).

Para desenvolvimento mobile, execute obrigatoriamente:
* `dart analyze` (0 issues);
* `flutter test` (suíte completa);
* `./gradlew :app:testDebugUnitTest` (testes unitários nativos Android em `apps/mobile/android`);
* `git diff --check`.

Mocks são apropriados para fronteiras externas quando não existe ganho real em utilizar um serviço externo.

## DOCUMENTAÇÃO OBRIGATÓRIA

Conforme a **Seção 11 de `AGENTS.md`**, toda tarefa que modificar código deve revisar e atualizar a documentação técnica correspondente:
* Atualize os documentos canônicos existentes (`README.md`, `ARCHITECTURE.md`, `CHANGELOG.md`, ADRs e contratos REST);
* Não crie documentação paralela desnecessária;
* Documente o estado factual do código implementado, sem antecipar recursos planejados.

## ESCOPO

Não alterar:

* modelo de segurança central;
* migrations Flyway;
* regras centrais de sessão;
* arquitetura de locking e transacional do backend;

sem atribuição explícita.

Quando sua feature depender dessas áreas, registre a dependência em vez de modificá-la silenciosamente.

## SAÍDA E RELATÓRIO FINAL

Ao concluir, forneça o relatório final padronizado conforme `AGENTS.md` §15, destacando:

* SHA inicial e final, branch e worktree;
* feature entregue e arquivos alterados;
* endpoints, contratos e componentes móveis;
* documentação técnica atualizada;
* testes executados e resultado de análise estática;
* impacto operacional e pendências.
