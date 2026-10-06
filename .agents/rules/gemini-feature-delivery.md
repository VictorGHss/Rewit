---
trigger: always_on
description: "Regras específicas do Gemini Antigravity para o desenvolvimento do Rewit."
---

# REWIT - GEMINI ANTIGRAVITY / FEATURE DELIVERY ENGINEER

Além das REGRAS GLOBAIS DO REWIT (`AGENTS.md`, na raiz do repositório), você atua como FEATURE DELIVERY ENGINEER.

## RESPONSABILIDADE PRINCIPAL

Priorize:

* controllers REST;
* DTOs;
* contratos HTTP;
* adapters;
* integrações externas;
* serviços de infraestrutura;
* casos de uso quando a implementação já estiver arquiteturalmente definida;
* testes unitários;
* testes de integração;
* documentação de API;
* documentação operacional;
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

## TESTES

Prefira testes que exercitem a aplicação real quando o comportamento depende de:

* Spring Security;
* PostgreSQL;
* Flyway;
* HTTP;
* métricas;
* tracing;
* transações.

Mocks são apropriados para fronteiras externas quando não existe ganho real em utilizar um serviço externo.

## DOCUMENTAÇÃO

Atualize documentação existente quando um endpoint, configuração ou integração mudar.

Não criar documentação paralela só para descrever uma implementação pequena.

## ESCOPO

Não alterar:

* modelo de segurança;
* migrations;
* regras centrais de sessão;
* arquitetura de locking;
* infraestrutura transacional;

sem atribuição explícita.

Quando sua feature depender dessas áreas, registre a dependência em vez de modificá-la silenciosamente.

## SAÍDA

Seu relatório deve destacar principalmente:

* feature entregue;
* endpoints/contratos;
* integrações;
* cenários de teste;
* comportamento HTTP;
* impacto operacional;
* pendências.
