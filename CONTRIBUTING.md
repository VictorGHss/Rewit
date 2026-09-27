# GUIA DE CONTRIBUIÇÃO (CONTRIBUTING.md)

Agradecemos o interesse em contribuir com o desenvolvimento do **Rewit**! Para mantermos o código com alto padrão de qualidade, segurança e conformidade arquitetural, solicitamos a observância das seguintes regras.

---

## 1. Regras de Ouro
1. **Leia e siga o `PROJECT_RULES.md`**: Toda contribuição deve respeitar os 31 princípios inegociáveis.
2. **Não faça implementações especulativas**: Adicione somente as funcionalidades solicitadas para o ciclo atual de desenvolvimento.
3. **Nunca versione secrets ou arquivos `.env`**: Mantenha as credenciais locais e adicione variáveis necessárias exclusivamente em `.env.example`.

---

## 2. Processo de Envio de Código (Pull Request)

1. Crie uma branch a partir de `develop`:
   ```bash
   git checkout develop
   git pull origin develop
   git checkout -b feature/sua-funcionalidade
   ```
2. Desenvolva sua implementação respeitando a separação de camadas da Clean Architecture.
3. Escreva testes automatizados para validar casos de uso e regras espaciais/matemáticas.
4. Execute os linters e verificadores de código.
5. Escreva mensagens de commit seguindo o padrão **Conventional Commits** (`feat:`, `fix:`, `docs:`, etc.).
6. Abra o Pull Request apontando para `develop` com uma descrição clara do que foi feito, decisões tomadas e como testar.
