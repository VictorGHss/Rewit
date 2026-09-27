# ADR-004: Arquitetura do Aplicativo Mobile com Flutter

## Status
Aprovado

## Contexto
O aplicativo mobile é o ponto focal da experiência de consumo e geração de conteúdo do Rewit. Ele precisa operar fluidamente em dispositivos Android e iOS, acessar hardware específico (câmera de alta resolução, leitor de código de barras/QR, GPS com precisão fina) e renderizar listas de feeds com alto volume de mídias e micro-interações sem engasgos de interface (*jank*).

## Decisão
1. Adotar **Flutter** como tecnologia multiplataforma do cliente mobile.
2. Adotar a estrutura de **Clean Architecture** no código Dart:
   - `core/`: Utilitários compartilhados, tema visual, cliente HTTP configurado e tratamento de erros de rede.
   - `domain/`: Entidades imutáveis do cliente, regras de validação local e contratos abstratos de repositório.
   - `data/`: Implementações de repositório, modelos com serialização JSON e fontes de dados remotas e locais.
   - `presentation/`: Gerenciamento de estado reativo, telas divididas por features (`home`, `feed`, `review`, `place`, `profile`, `scanner`) e componentes reutilizáveis.
   - `integrations/`: Adapters nativos para hardware de câmera, geolocalização e serviços de terceiros.
3. Gerenciamento de estado: Arquitetura desacoplada e previsível (StateNotifier / BLoC / Riverpod / ChangeNotifier limpo).
4. Otimização de Imagens: Compressão e sanitização de mídias localmente no dispositivo antes do upload ao backend.

## Consequências
### Positivas:
- Base de código única para Android e iOS, reduzindo o custo de manutenção e acelerando o ciclo de desenvolvimento.
- Performance consistente de 60/120 fps graças à renderização via engine gráfica direta do Flutter (Impeller/Skia).
- Facilidade de mock e testes unitários de casos de uso na camada `domain`.
### Negativas:
- Integrações avançadas de visão computacional local podem demandar plugins FFI em C++/Rust no futuro caso não sejam delegadas ao backend.
