# ADR-002: PostgreSQL e PostGIS como Fonte da Verdade e Motor Espacial

## Status
Aprovado

## Contexto
O Rewit necessita calcular distâncias com alta precisão, verificar se um usuário se encontra fisicamente nas dependências de um estabelecimento comercial (check-in) e consultar avaliações e eventos em raios geográficos variáveis (ex: 500m, 5km, 50km). Bancos relacionais puros ou soluções baseadas em fórmulas trigonométricas simples em código de aplicação geram gargalos de performance, imprecisão geométrica devido à curvatura da Terra e incapacidade de indexação eficiente.

## Decisão
1. O **PostgreSQL** é a única fonte primária canônica da verdade do produto Rewit.
2. A extensão **PostGIS** será ativada e utilizada para todas as colunas que representam geolocalização.
3. Adotaremos o tipo de dado `GEOGRAPHY(Point, 4326)` em vez de `GEOMETRY` plano cartesiano:
   - O tipo `geography` calcula automaticamente as distâncias na superfície esferoidal do planeta (WGS 84), expressando distâncias em metros por padrão.
4. Toda coluna geográfica terá indexação espacial obrigatória do tipo **GiST (Generalized Search Tree)**:
   ```sql
   CREATE INDEX idx_places_coordinates ON places USING GIST (coordinates);
   ```
5. Operações de verificação de proximidade utilizarão a função indexada `ST_DWithin`, garantindo tempo de resposta constante e aproveitamento dos índices mesmo sob milhões de registros.

## Consequências
### Positivas:
- Alta precisão matemática e respeito aos limites da curvatura terrestre.
- Capacidade de suportar tanto pontos (`Point`) quanto polígonos complexos de estabelecimentos e bairros (`Polygon`, `MultiPolygon`) no futuro sem alterar o motor do banco.
- Consultas de raio e geofencing executadas diretamente no banco de dados com máxima otimização via GiST.
### Negativas:
- Exige imagens de banco com suporte ao PostGIS (`postgis/postgis:16-3.4`).
- Hibernate exige a dependência `hibernate-spatial` configurada adequadamente no Spring Boot.
