# ADR-003: Estratégia de Isolamento e Integração com o Google

## Status
Aprovado

## Contexto
O ecossistema Google oferece serviços de classe mundial em mapas, locais e identidade. No entanto, depender do Google como banco de dados principal criaria um grave aprisionamento tecnológico (*vendor lock-in*), custos proibitivos com APIs à medida que o volume de acessos crescesse e vulnerabilidade regulatória ou contratual. Além disso, as políticas de uso do Google Maps Platform vedam a cópia ou espelhamento indiscriminado de sua base de dados (*caching/scraping terms*).

## Decisão
1. **Google NÃO é o banco do Rewit**: Nenhuma entidade primária depende exclusivamente de identificadores do Google para existir. Nossos registros possuem chaves primárias UUID próprias geradas internamente.
2. **Definição de Papéis Distintos para Serviços Google**:
   - **Google Identity**: Apenas como provedor de autenticação e validação de token OAuth2/OpenID Connect para conveniência no cadastro/login.
   - **Google Places**: Utilizado estritamente na fase de *bootstrap* (descoberta inicial de locais próximos quando a base local estiver vazia) e para enriquecimento inicial com consentimento do usuário.
   - **Google Maps**: Renderização de mapa e exibição visual de rotas no cliente mobile.
   - **Google Reviews Integration**: Nosso sistema gerencia suas próprias avaliações. O Rewit não tentará publicar avaliações fraudulentas ou automáticas na conta do usuário no Google. Quando aplicável, forneceremos um link/intenção nativa para que o usuário abra a interface oficial do Google caso queira replicar sua opinião lá.
3. **Camada Anti-Corrupção (ACL)**:
   - Todo código de comunicação com o Google reside estritamente em `com.rewit.integrations.google.*`.
   - Nenhuma classe de domínio ou caso de uso pode importar SDKs do Google.
   - Todos os dados retornados pela API externa são mapeados para modelos de domínio neutros da aplicação.
4. **Respeito aos Termos de Serviço**:
   - Apenas o `place_id` do Google e dados essenciais permitidos para cache temporário de rota são armazenados, respeitando os limites estipulados pelas políticas da Google Maps Platform.

## Consequências
### Positivas:
- Liberdade arquitetural total: o provedor de mapas/locais pode ser substituído por OpenStreetMap, Mapbox ou base governamental sem quebrar o core do Rewit.
- Conformidade legal e contratual com os termos da Google Cloud.
- O sistema acumula patrimônio de dados proprietário à medida que os usuários usam a plataforma.
### Negativas:
- Exige criação de adapters de tradução e DTOs intermediários na camada de integração.
