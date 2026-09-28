# Requisitos de Termos de Uso e Política de Privacidade (Google Maps Platform Compliance)

## 1. Visão Geral e Finalidade

Este documento técnico formaliza os **requisitos mandatórios de publicação e conformidade legal** exigidos pela Google Maps Platform (especificamente para consumo da **Google Places API New**) no ecossistema Rewit.

> [!IMPORTANT]
> **Status Atual:** Documento de especificação técnica e governança. O conteúdo jurídico formal e vinculante (Terms of Use e Privacy Policy para usuários finais) **deverá ser redigido, revisado e validado por assessoria jurídica especializada** antes da publicação do Rewit em ambientes de produção e lojas de aplicativos (App Store / Google Play).

---

## 2. Requisitos Mandatórios da Google Maps Platform

De acordo com os Termos de Serviço da Google Maps Platform (Seção 3.2.2 e Políticas de Atribuição), qualquer aplicação ou serviço que integre dados ou mapas da Google deve cumprir os seguintes requisitos:

1. **Disponibilização Pública de Termos de Uso (Terms of Use):**
   * A aplicação deve disponibilizar Termos de Uso publicamente acessíveis e de fácil localização por qualquer usuário antes ou durante o uso do serviço.
   * Os Termos de Uso do Rewit devem notificar explicitamente aos usuários que, ao utilizar os recursos de mapa e descoberta de locais, eles estão vinculados aos [Termos de Serviço Adicionais do Google Maps/Google Earth](https://maps.google.com/help/terms_maps.html).

2. **Disponibilização Pública de Política de Privacidade (Privacy Policy):**
   * A aplicação deve manter uma Política de Privacidade publicamente acessível.
   * A política deve declarar de forma inequívoca que a aplicação utiliza serviços da Google Maps Platform e incorporar por referência a [Política de Privacidade do Google](https://www.google.com/policies/privacy/).

3. **Exibição de Atribuições Legais (Attributions & Copyright):**
   * Quando dados de estabelecimentos fornecidos pela Places API incluírem informações de provedores terceiros ou licenciantes, a aplicação cliente (Web / Mobile) deve renderizar as atribuições de autoria correspondentes fornecidas no campo `attributions` da API.
   * É vedada a remoção, ocultação ou alteração de avisos de direitos autorais ou links de atribuição do Google ou de seus provedores.

---

## 3. Arquitetura Técnica de Conformidade no Rewit

Para viabilizar o cumprimento integral desses requisitos sem acoplar o núcleo da aplicação:

1. **Camada Backend (API REST):**
   * O endpoint `/api/v1/places/discovery/**` extrai os metadados de atribuição upstream da Places API (New) e os repassa de forma agnóstica através da lista `attributions` (`PlaceAttributionResponse`).
   * A política de **Zero-Store** garante que nenhum dado protegido da Google é espelhado ou gravado permanentemente sem autorização.

2. **Camadas Clientes (Web e Mobile - Step Futuro):**
   * Telas de cadastro e rodapés de aplicações deverão conter links diretos para os Termos de Uso e Política de Privacidade do Rewit.
   * Ao exibir resultados de descoberta externa, as aplicações clientes devem iterar sobre a lista `attributions` retornada pela API e exibir os links e nomes dos provedores.

---

## 4. Plano de Ação Pré-Produção

Antes do lançamento comercial da plataforma Rewit:

- [ ] Elaborar a minuta jurídica formal dos **Termos de Uso do Rewit** com cláusula expressa incorporando os Termos Adicionais da Google Maps.
- [ ] Elaborar a minuta jurídica formal da **Política de Privacidade do Rewit** integrando as diretrizes da LGPD (Lei 13.709/2018) e a referência à Política de Privacidade da Google.
- [ ] Publicar ambos os documentos em endpoints/páginas web públicas e permanentes (ex: `https://rewit.app/terms` e `https://rewit.app/privacy`).
- [ ] Incluir no aplicativo móvel (Flutter) e na aplicação Web links visíveis para tais documentos nas seções de *Sobre*, *Cadastro* e *Configurações*.
