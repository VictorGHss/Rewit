# ADR-008: Separação Estrita entre Reputação Social e Sistema de Recompensas Cosméticas

## Status
Aprovado

## Contexto
Muitas plataformas sociais sofrem com a degradação de credibilidade quando mecanismos de gamificação e pontos permitem que usuários "comprem" relevância, comprem selos de verificação ou forjem avaliações para acumular vantagens competitivas. No Rewit, a confiança da comunidade sobre a opinião emitida é o ativo mais valioso da empresa.

## Decisão
1. **Separação Completa de Domínios**:
   - O subsistema de **Reputação** é um modelo matemático de mérito, qualidade e confiabilidade.
   - O subsistema de **Recompensas Cosméticas** é um mecanismo lúdico de engajamento comunitário.
2. **Critérios de Reputação**:
   - Baseado unicamente em:
     - Volume de avaliações verificadas presencialmente (com check-in válido);
     - Contagem de marcações de *"Útil"* atribuídas por outros membros da comunidade;
     - Histórico de precisão e moderação sem infrações;
     - Especialização temática e regional (ex: especialista em culinária japonesa na Zona Sul).
3. **Avaliações Anônimas**:
   - Podem ser publicadas para resguardo da privacidade pessoal do autor.
   - Não conferem pontos de reputação e possuem peso reduzido ou nulo em rankings públicos.
   - O identificador real é registrado criptograficamente no backend para propósitos estritos de moderação legal.
4. **Isolamento de Itens Cosméticos**:
   - Pontos de engajamento ou compras dentro do app só podem ser trocados por bens cosméticos visuais:
     - Molduras de avatar;
     - Emotes temáticos para comentários;
     - Badges comemorativos puramente estéticos.
   - É terminantemente proibido que cosméticos alterem a visibilidade do feed, o peso do voto, o cálculo de médias de locais ou a posição em rankings de reputação.

## Consequências
### Positivas:
- Imunidade a esquemas de "pay-to-win" ou manipulação de notas de estabelecimentos.
- Construção de uma comunidade respeitada e de alta credibilidade orgânica.
- Proteção contra fraudes e avaliações encomendadas.
### Negativas:
- Exige modelagem de tabelas e serviços de pontuação inteiramente separados no banco de dados.
