# PRIVACIDADE, SEGURANÇA E CONFORMIDADE COM LGPD (docs/security/privacy-and-lgpd.md)

Este documento estabelece as diretrizes de proteção de dados, privacidade por design e arquitetura segura para o projeto **Rewit**, em conformidade com a Lei Geral de Proteção de Dados Pessoais (LGPD - Lei nº 13.709/2018).

---

## 1. Princípios de Privacidade por Design (Privacy by Design)

1. **Minimização de Dados (Data Minimization)**: Coletar apenas os dados estritamente indispensáveis para o funcionamento da plataforma. Não solicitar dados como CPF, telefone pessoal ou contatos da agenda a menos que vinculados a um fluxo legal obrigatório (ex: emissão de nota fiscal para contas comerciais).
2. **Finalidade e Transparência**: O usuário deve ter clareza total sobre o motivo da solicitação de cada permissão no aplicativo (câmera para escanear/fotografar produtos, localização sob demanda para verificar presença).

---

## 2. Política Estrita de Geolocalização

- **Proibição de Rastreamento Contínuo**: O Rewit **não** possui recursos de gravação de rotas, trilhas de GPS em segundo plano ou telemetria de tráfego contínua.
- **Localização Sob Demanda**:
  - As coordenadas geográficas do usuário só são lidas no instante da submissão de uma ação que requer geolocalização.
  - Uma vez calculada a distância em relação ao estabelecimento para fins de verificação do check-in, as coordenadas da residência ou do trânsito do usuário são descartadas da memória de trabalho.
  - Apenas as coordenadas do evento de check-in (atestando que o usuário esteve no estabelecimento comercial) são persistidas.

---

## 3. Higienização de Imagens (Remoção Obrigatória de Metadados EXIF)

Toda foto capturada por smartphones modernos embute metadados EXIF detalhados no arquivo binário JPEG/PNG, incluindo:
- Latitude e Longitude exatas do disparo da foto;
- Modelo do aparelho, número de série e versão de software;
- Data e hora com frações de segundo.

### Invariante de Segurança:
- O backend do Rewit executa um pipeline de processamento de imagem na chegada de qualquer arquivo de mídia (antes da gravação no MinIO/S3).
- **Ação**: O arquivo é decodificado e re-codificado utilizando biblioteca de manipulação gráfica (ex: TwelveMonkeys ImageIO / Thumbnailator), eliminando 100% dos blocos EXIF, IPTC e XMP.
- Imagens públicas servidas pelo CDN/MinIO nunca conterão coordenadas geográficas embutidas.

---

## 4. Avaliações Anônimas e Governança de Moderação

- **Experiência do Usuário**: O usuário pode selecionar a opção *"Publicar anonimamente"* ao escrever uma crítica.
- **Camada de Apresentação**:
  - O JSON de resposta da API oculta o `user_id`, `handle` e `avatar_url` do autor perante outros usuários, exibindo apenas um identificador anônimo genérico (ex: *"Membro da Comunidade"*).
- **Camada Interna e Governança Legal**:
  - Em conformidade com a legislação brasileira (Marco Civil da Internet - Art. 10 da Lei 12.965/2014), que veda o anonimato absoluto com fins ilícitos, a base de dados interna preserva o `user_id` original em campo restrito da tabela `reviews`.
  - Esse vínculo só pode ser acessado por auditores de moderação sob processo de denúncia formal ou mediante requisição judicial fundamentada, prevenindo calúnia, difamação e ataques comerciais orquestrados.

---

## 5. Proteção de Credenciais e Tratamento de Logs

1. **Hashing de Senhas**: Utilização obrigatória de algoritmo moderno de derivação de chaves: **BCrypt** com fator de trabalho (cost) 12 ou **Argon2id**.
2. **Tokens JWT de Sessão**:
   - Assinatura com algoritmo assimétrico ou chave secreta forte (mínimo 256 bits via variável `JWT_SECRET`).
   - Tempo de expiração curto para access tokens (ex: 15 a 60 minutos) com mecanismo de refresh token seguro via Redis.
3. **Prevenção de Vazamento em Logs**:
   - É terminantemente proibido registrar em logs de aplicação (SLF4J/Logback):
     - Senhas ou hashes;
     - Cabeçalhos `Authorization`;
     - Coordenadas geográficas residenciais;
     - E-mails não mascarados.
