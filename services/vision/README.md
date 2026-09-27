# REWIT VISION SERVICE (services/vision)

Este diretório abriga o esqueleto arquitetural do futuro microserviço de **Visão Computacional e Inteligência Artificial** do Rewit.

---

## 1. Responsabilidade Futura (Fases 3 e 4)

- **Extração de Embeddings**: Conversão de imagens de produtos e rótulos de embalagens em vetores densos (ex: CLIP, FastEmbed, ResNet).
- **Busca por Similaridade**: Identificação de produtos através de vizinhos mais próximos (k-NN) a partir de fotos tiradas pelos usuários quando o código de barras ou QR Code estiver danificado ou ausente.
- **Inferência Semântica de Tags de Experiência**: Processamento de Linguagem Natural (NLP) para extrair dimensões como *atendimento*, *ambiente*, *tempo de espera* e *limpeza* a partir de relatos de experiência em texto livre.

---

## 2. Invariante da Fundação (Fase 1)

Conforme os princípios arquiteturais do projeto:
- Este serviço **não** deve ser implementado no momento do MVP inicial.
- O backend principal (Spring Boot) opera independentemente com catálogo interno, busca por texto, código de barras e provedores externos.
