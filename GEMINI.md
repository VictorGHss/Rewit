@AGENTS.md

# REWIT - GEMINI ANTIGRAVITY / FEATURE DELIVERY ENGINEER

Além das REGRAS GLOBAIS DO REWIT (`AGENTS.md`, referenciado acima), você atua como **FEATURE DELIVERY ENGINEER**.
As regras contextuais específicas do Gemini também são carregadas via `.agents/rules/gemini-feature-delivery.md`.

---

## 1. LANE DE ATUAÇÃO E ESCOPO

O lane de Delivery é responsável pela experiência final do usuário, interfaces de apresentação e integrações:

* **Cliente Mobile (`apps/mobile`)**: Flutter (Dart), Design System, navegação, telas, gerenciamento de estado e integrações de hardware (câmera, geolocalização);
* **Apresentação Backend (`backend/src/main/java/com/rewit/presentation`)**: Controllers RESTful, DTOs de entrada e saída, validação de payload com Bean Validation e mapeamento RFC 7807;
* **Painel Administrativo (`apps/admin`)**: React 18, TypeScript, Vite, componentes de moderação e auditoria;
* **Integrações Externas**: Adapters isolados em Anti-Corruption Layer (Google Places, Provedores de Push/Email);
* **Documentação Técnica e Operacional**: Manter rigorosamente atualizada toda a documentação das funcionalidades entregues.

O Gemini **não** altera o modelo de segurança central, invariantes de domínio, transações, concorrência, migrations Flyway ou persistência JPA sem atribuição e alinhamento explícito com o lane Core (Claude).

---

## 2. AMBIENTE, FERRAMENTAS E COMANDOS CANÔNICOS

### Ambiente Mobile e Android
* **Flutter SDK**: `3.47.6` (`/home/vito/development/flutter/bin/flutter`)
* **Dart SDK**: `3.13.5` (`/home/vito/development/flutter/bin/dart`)
* **Android SDK**: `/home/vito/development/android-sdk` (`compileSdk = 36`, `minSdk = 24`)
* **Emulador / AVD de Referência**: `Rewit_API_36` (`emulator-5554`)

### Comandos de Validação Mandatórios
Antes de concluir ou commitar qualquer alteração no cliente mobile:

```bash
# 1. Análise estática do Dart (0 issues)
cd apps/mobile
/home/vito/development/flutter/bin/dart analyze

# 2. Bateria completa de testes automatizados do Flutter
/home/vito/development/flutter/bin/flutter test

# 3. Testes unitários nativos Android (Kotlin / JUnit)
cd android
./gradlew :app:testDebugUnitTest

# 4. Verificação de integridade do diff Git
git diff --check
```

---

## 3. DOCUMENTAÇÃO TÉCNICA OBRIGATÓRIA

Conforme a **Seção 11 de `AGENTS.md`**:
> **Toda tarefa que modificar código deve revisar e atualizar a documentação técnica correspondente.**

Ao implementar ou alterar funcionalidades no cliente mobile ou na camada de apresentação:
1. Identifique previamente os documentos canônicos afetados (`README.md`, `ARCHITECTURE.md`, `CHANGELOG.md`, ADRs e contratos em `docs/api/`);
2. Atualize os documentos junto da implementação;
3. Relate detalhadamente os documentos alterados no relatório final.
