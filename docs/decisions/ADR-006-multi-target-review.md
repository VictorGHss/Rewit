# ADR-006: Modelo de Avaliação Multi-Alvo e Desacoplamento de Métricas

## Status
Aprovado

## Contexto
Na maioria dos sistemas legados de avaliação, uma publicação avalia exclusivamente uma única entidade (ou apenas o restaurante, ou apenas o produto no e-commerce). No mundo físico, a experiência é composta: em um único momento, um usuário almoça em um estabelecimento (Place), é atendido por uma equipe (Service) e consome um prato ou bebida específica (Product). Forçar o usuário a criar 3 publicações distintas gera fricção excessiva e fragmenta o feed. Ao mesmo tempo, atribuir uma única nota global distorce as métricas (ex: o hambúrguer pode ser nota 5, mas o atendimento foi nota 1).

## Decisão
1. **Modelagem de Publicação Única com Múltiplos Alvos**:
   - Criar uma entidade agregadora `Review` que representa a publicação social (autor, mídia, texto descritivo geral, visibilidade, anonimato).
   - Vincular uma coleção de alvos avaliados: `List<ReviewTarget>`.
2. **Definição de Tipos de Alvos (`TargetType`)**:
   - `PLACE`: Estabelecimento físico (endereço, ambiente, estrutura).
   - `SERVICE`: Atendimento, velocidade, suporte, entrega.
   - `PRODUCT`: Item específico consumido (prato, bebida, produto manufaturado).
   - `EVENT`: Evento temporal que aconteceu no local.
3. **Métricas Isoladas e Idempotentes**:
   - Cada `ReviewTarget` possui sua própria nota (`rating`: valor de 1.0 a 5.0).
   - O cálculo das médias gerais é estritamente segregado:
     - A nota atribuída ao `PRODUCT` atualiza a média do produto (`Product.average_rating`).
     - A nota atribuída ao `PLACE` atualiza a média física do local (`Place.average_rating`).
     - A nota do `SERVICE` atualiza a média de atendimento do local.
   - **Proibição de Duplicação Indevida**: A nota do local não pode ser inflacionada pelo fato de o usuário ter elogiado o produto na mesma publicação.
4. **Experiência como Dimensão Textual e Semântica**:
   - "Experiência" não é um alvo ou tabela separada, mas a dimensão qualitativa da `Review`, enriquecida com tags inferidas semânticas (atendimento, custo-benefício, limpeza, etc.).

## Consequências
### Positivas:
- Experiência de uso natural para o consumidor, que publica tudo em uma única postagem sem atrito.
- Métricas granulares de altíssimo valor de negócio (ex: o produto é ótimo em qualquer lugar, mas a filial X tem péssimo atendimento).
- Possibilidade de ranquear os melhores produtos da cidade independentemente do local.
### Negativas:
- Maior complexidade na tela de criação de avaliação no mobile e no recálculo assíncrono de agregados no banco.
