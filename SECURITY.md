# POLÍTICA DE SEGURANÇA (SECURITY.md)

A segurança dos dados dos nossos usuários e a integridade de nossa plataforma de avaliações são prioridades máximas do **Rewit**.

---

## 1. Relato de Vulnerabilidades

Caso identifique uma falha ou vulnerabilidade de segurança:
1. **NÃO crie uma Issue pública no GitHub**.
2. Encaminhe um e-mail para o time de segurança: `security@rewit.app` (ou abra um Security Advisory privado no GitHub).
3. Inclua na mensagem:
   - Descrição detalhada da vulnerabilidade;
   - Passos reprodutíveis ou script de prova de conceito (PoC);
   - Possível impacto em confidencialidade, integridade ou disponibilidade.

---

## 2. Padrões de Segurança do Repositório

- **Varredura Contínua**: O repositório monitora automaticamente dependências vulneráveis via ferramentas de SCA (ex: Dependabot / Trivy).
- **Sem Segredos em Código**: O commit de arquivos `.env`, chaves privadas, senhas ou tokens de API é bloqueado. Qualquer secret exposto acidentalmente é imediatamente revogado e invalidado.
- **Higienização de Dados**: Todas as fotos de upload têm seus metadados EXIF e dados geográficos originais removidos antes do armazenamento público.
