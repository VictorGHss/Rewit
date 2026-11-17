# ADR-005: Modelo de Localização, Validação Espacial e Check-in

## Status
Aprovado

## Contexto
Diferente de redes sociais tradicionais de check-in que transformaram a funcionalidade em um "jogo de badges" falso onde qualquer pessoa pode afirmar estar em Tóquio estando em São Paulo, o Rewit exige confiabilidade em suas métricas de presença. Ao mesmo tempo, o sistema deve respeitar o direito à privacidade (LGPD), minimização de dados e não degradar a bateria do usuário com rastreamento GPS indiscriminado.

## Decisão
1. **Localização Sob Demanda**:
   - O aplicativo nunca solicita nem armazena trilhas contínuas de coordenadas geográficas em segundo plano.
   - O GPS só é consultado quando o usuário explicitamente executa uma ação de geolocalização (pesquisa por proximidade ou criação de avaliação).
2. **Avaliações com e sem Localização**:
   - É permitido ao usuário avaliar um local sem conceder localização (ex: avaliação em casa sobre uma experiência de almoço).
   - Avaliações criadas sem presença comprovada são gravadas com status `is_verified_on_site = false` e **não recebem** o selo de verificação local.
3. **Check-in Vinculado à Avaliação**:
   - Não existe check-in sem avaliação. O check-in é o atestado de presença acoplado à opinião do usuário.
   - É obrigatório que o usuário atribua no mínimo as estrelas de avaliação para que o check-in seja efetivado.
4. **Verificação Espacial no Backend**:
   - Ao receber as coordenadas do usuário e o `place_id`, o backend executa uma consulta PostGIS utilizando `ST_DWithin`:
     ```sql
     ST_DWithin(place.coordinates, user_coordinates, place.validation_radius_meters)
     ```
   - O raio de tolerância (`validation_radius_meters`) é **configurável por local/categoria** e nunca fixo no código. Locais amplos (shoppings, parques) possuem raios maiores; quiosques ou pequenas lojas possuem raios mais restritos (ex: 30 a 80 metros), respeitando o teto de 250 metros garantido pelo banco (V41).
5. **Políticas Explícitas de Captura no Cliente (`LocationCapturePolicy`)**:
   - **Descoberta (`discovery`)**: Utilizada na busca de locais próximos em raio de até 10 km. Prioriza velocidade, aceita cache de até 5 minutos, precisão média e timeout máximo de 5 segundos.
   - **Verificação de Presença (`presenceVerification`)**: Utilizada no momento da publicação da avaliação e na aba de check-in. Rejeita cache antigo, exige leitura fresca (<= 1 minuto), alta precisão GPS, timeout de 10 a 15 segundos para aquecimento de satélites e retry único de até 5 segundos caso a acurácia inicial seja aproximada (> 100 metros).
6. **Retomada Controlada Após Configurações do Sistema (`LocationService.checkReadiness`)**:
   - Quando `ExternalPlaceResultsSection`, `CheckInScreen` ou `ReviewCreateScreen` iniciam explicitamente `openAppSettings()` ou `openLocationSettings()`, cada tela guarda o retorno ao ciclo `AppLifecycleState.resumed` via `WidgetsBindingObserver` com trava de origem (ignorando retornos sem navegação prévia e eventos repetidos).
   - Antes de capturar posição novamente, `LocationService.checkReadiness(purpose: ...)` revalida serviço e permissão sem disparar prompt nativo (`requestPermission`), preservando em `GeolocatorLocationService` a última recusa permanente conhecida (`deniedForever`) mesmo quando `Geolocator.checkPermission()` no Android retorna `denied` (limpando-a ao detectar permissão concedida ou recusa temporária), retomando a captura automaticamente apenas quando o bloqueio foi resolvido (ou quando a permissão aproximada em verificação de presença foi alterada para exata) e preservando o controle contra concorrência e descarte de widget.
7. **Localização Simulada em QA e Autoridade do Backend**:
   - O cliente mobile não bloqueia coordenadas simuladas/mock no dispositivo, assegurando a viabilidade de testes automatizados, CI e testes em emuladores de desenvolvimento.
   - A verificação de presença é estritamente autoritativa no backend (`PresenceVerificationPolicy`): distância geodésica via PostGIS, acurácia horizontal reportada <= 100m, trava transacional `pg_advisory_xact_lock` e teto de no máximo um check-in verificado por usuário e local em janela móvel de 24 horas.
8. **Evolução Futura**:
   - A modelagem prevê suporte a polígonos delimitadores exatos (`GEOGRAPHY(Polygon, 4326)`) em fases posteriores, substituindo o raio esférico simplificado para locais complexos via `ST_Contains`.

## Consequências
### Positivas:
- Prevenção contundente de check-ins falsos e avaliações "astroturfing".
- Baixo consumo de bateria e conformidade absoluta com princípios de privacidade.
- Flexibilidade para calibrar a precisão de acordo com a realidade física de cada tipo de estabelecimento.
### Negativas:
- Usuários em ambientes com bloqueio de sinal GPS (ex: subsolos de shoppings) podem ocasionalmente falhar na validação no local. *Mitigação*: Permitir pequena janela de tolerância temporal e suporte a Wi-Fi/CellID quando suportado.
