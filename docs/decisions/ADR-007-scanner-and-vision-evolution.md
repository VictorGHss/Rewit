# ADR-007: Evolução em Fases do Scanner e Serviço de Visão Computacional

## Status
Aprovado

## Contexto
A identificação instantânea de produtos através da câmera é um dos maiores diferenciais do Rewit. No entanto, implementar modelos complexos de inteligência artificial visual no primeiro dia de projeto sobrecarregaria o escopo, exigiria infraestrutura de GPUs de alto custo e atrasaria a validação do produto. É fundamental planejar uma evolução incremental viável.

## Decisão
Adotar um roadmap em quatro fases para o subsistema de scanner e identificação:

1. **Fase 1 (MVP - Hardware e Códigos Barcode/QR)**:
   - Suporte a leitura rápida de códigos de barras convencionais no aplicativo Flutter: EAN-13, EAN-8, UPC-A, GTIN.
   - Suporte a leitura de QR Codes (representando produtos específicos, links de cardápios, mesas de restaurantes ou campanhas promocionais).
   - Consulta direta no catálogo local do Rewit ou busca em APIs abertas de GTIN.
2. **Fase 2 (OCR Textual)**:
   - Extração ótica de texto de embalagens e rótulos de produtos diretamente no dispositivo via biblioteca de OCR leve no cliente (ML Kit / Tesseract local).
   - Busca de similaridade textual de marca e nome no catálogo do backend.
3. **Fase 3 (Microserviço Dedicado de Visão em Python)**:
   - Ativação do diretório `services/vision`.
   - Execução de modelos de visão computacional (ex: CLIP, FastEmbed, PyTorch) para geração de embeddings visuais de embalagens e produtos físicos.
   - Busca vetorial por vizinhos mais próximos (KNN / HNSW) para sugerir produtos mesmo sem código de barras visível.
4. **Fase 4 (Confirmação Humana Obrigatória)**:
   - Qualquer inferência visual ou de OCR sempre apresenta sugestões amigáveis para que o usuário confirme ou corrija o item antes da publicação, garantindo a pureza dos dados catalogados.

## Consequências
### Positivas:
- O MVP é entregue com baixo custo de computação e máxima velocidade de leitura de código de barras.
- Arquitetura desacoplada: a introdução do serviço em Python no futuro não afetará a API Java/Spring Boot além da chamada de uma interface REST/gRPC.
### Negativas:
- Na fase inicial, produtos artesanais sem código de barras dependem de busca manual por texto ou cadastro pelo usuário.
