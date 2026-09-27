# ADR-009: Modelagem Relacional e Integridade de Alvos Avaliáveis (Review Targets)

## Status
Aprovado

## Contexto
O conceito central do Rewit permite que uma única publicação (`Review`) avalie simultaneamente múltiplos alvos (`ReviewTarget`), tais como o estabelecimento físico (`Place`), o atendimento (`Service`), produtos consumidos (`Product`) ou eventos temporais (`Event`).

Modelagens ingênuas comuns utilizam campos soltos como `target_type VARCHAR` e `target_id UUID` sem qualquer chave estrangeira no banco de dados. Essa abordagem é frágil porque:
1. Permite órfãos e IDs inexistentes no banco (perda total de integridade referencial);
2. Dificulta `JOINs` relacionais e validações automáticas;
3. Exige triggers procedurais complexos para garantir que o ID pertença à tabela indicada por `target_type`.

Por outro lado, criar colunas separadas opcionais em `review_targets` (`place_id`, `product_id`, `service_id`, `event_id`, etc.) torna a tabela esparsa, exige múltiplos índices com muitos nulos e quebra o princípio de extensibilidade (adicionar um novo tipo avaliável exigiria `ALTER TABLE` e migrações estruturais pesadas).

## Decisão
Adotamos o padrão **Raiz Polimórfica Relacional (Rateable Target Root Pattern / Class Table Inheritance)**:

1. **Criação da Tabela Base `rateable_targets`**:
   - Atua como a identidade canônica e ancestral de qualquer entidade que possa receber avaliações:
     ```sql
     CREATE TABLE rateable_targets (
         id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
         target_type VARCHAR(32) NOT NULL, -- 'PLACE', 'PRODUCT', 'SERVICE', 'EVENT'
         created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
     );
     ```
2. **Vinculação das Entidades Concretas**:
   - Toda entidade avaliável (`places`, `products`, `services`, `events`) possui sua chave primária como chave estrangeira estrita referenciando `rateable_targets(id)`:
     ```sql
     CREATE TABLE places (
         id UUID PRIMARY KEY REFERENCES rateable_targets(id) ON DELETE CASCADE,
         name VARCHAR(255) NOT NULL,
         ...
     );
     ```
   - Ao criar um `Place`, o sistema primeiro insere um registro em `rateable_targets(target_type='PLACE')` e utiliza o mesmo UUID como chave primária do local.
3. **Integridade Referencial Estrita em `review_targets`**:
   - A tabela `review_targets` referencia diretamente a raiz:
     ```sql
     CREATE TABLE review_targets (
         id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
         review_id UUID NOT NULL REFERENCES reviews(id) ON DELETE CASCADE,
         target_id UUID NOT NULL REFERENCES rateable_targets(id) ON DELETE RESTRICT,
         rating NUMERIC(2, 1) NOT NULL CHECK (rating >= 1.0 AND rating <= 5.0),
         specific_comment TEXT,
         created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
         CONSTRAINT uq_review_target UNIQUE (review_id, target_id)
     );
     ```
4. **Isolamento de Médias e Estatísticas (`rateable_target_stats`)**:
   - As médias não são colunas mutáveis soltas em cada entidade. Em vez disso, a tabela `rateable_target_stats` (`target_id REFERENCES rateable_targets(id)`, `average_rating`, `reviews_count`, `last_updated_at`) centraliza o agregado derivado de forma assíncrona/consistente.

## Consequências
### Positivas:
- **100% de Integridade Referencial**: Nenhuma avaliação pode apontar para um alvo inexistente no banco.
- **Extensibilidade Ilimitada**: Novos alvos avaliáveis (ex: pratos de cardápio, rotas turísticas, prestadores autônomos) podem ser adicionados no futuro apenas criando uma nova tabela que aponte para `rateable_targets(id)`, sem alterar uma única linha de código em `review_targets`.
- **Prevenção de Voto Duplo**: A constraint `UNIQUE (review_id, target_id)` impede que uma mesma publicação pontue o mesmo alvo duas vezes.
- **Consultas Unificadas de Médias**: Permite calcular rankings e agregados sobre `rateable_targets` com queries relacionais limpas e indexadas.
### Negativas / Mitigações:
- Exige uma inserção prévia na tabela pai `rateable_targets` ao criar um novo local, produto, serviço ou evento. *Mitigação*: Encapsulado de forma transparente nos repositórios e serviços de criação de entidade.
