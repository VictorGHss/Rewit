# ADR-014: Invalidação de Dados Comerciais na Mudança de Titularidade

## Status
Aceito

## Contexto
Horários de funcionamento (F2.1, V26) e comodidades (F2.2, V27) são gravados pela conta comercial titular do local. Cada local tem uma configuração mestre (`place_opening_hours_schedules`, `place_amenity_configurations`, chave `place_id`) com a conta que gravou (`business_account_id`) e dependentes (`place_opening_hours_intervals`, `place_amenity_selections`) removidos em cascata.

Até aqui, a mudança de titular (`places.claimed_by_business_id`) só ocultava esses dados: a leitura avalia a elegibilidade da conta que gravou, e uma conta que não representa mais o local deixa de aparecer. Os registros continuavam no banco. Com isso, quando a mesma conta voltava a ser titular (A → B → A, ou A → sem titular → A, mesmo muito tempo depois), os horários e comodidades antigos reapareciam sem revisão. Horários desatualizados levam usuários ao local em hora errada.

Hoje a aplicação só muda o titular na decisão de reivindicação (de nenhum titular para uma conta). Não há fluxo de transferência nem de desvinculação, mas a regra precisa valer para os que vierem.

As ofertas comerciais (F2.3) já têm decisão própria (T2, V28): a troca de titular as apaga. Essa decisão não se estende automaticamente a horários e comodidades; esta é uma decisão separada, aprovada pelo responsável pelo produto.

## Decisão
1. **Mudança real de titularidade invalida definitivamente** horários e comodidades do local. Vale para A → B, A → sem titular e sem titular → A: qualquer transição em que `claimed_by_business_id` mude de valor.
2. **Exclusão física da configuração mestre**: `place_opening_hours_schedules` e `place_amenity_configurations` do local são apagadas; intervalos e seleções saem pelas FKs `ON DELETE CASCADE` existentes. Não há filtro por conta anterior nem pela que entra.
3. **No banco, na mesma transação**: a V29 cria `fn_invalidate_place_commercial_data_on_owner_change` e o trigger `trg_places_owner_change_invalidates_commercial_data` (`AFTER UPDATE OF claimed_by_business_id ON places FOR EACH ROW WHEN (OLD.claimed_by_business_id IS DISTINCT FROM NEW.claimed_by_business_id)`). Vale para qualquer caminho que altere o vínculo, inclusive fluxos futuros e o `ON DELETE SET NULL` da conta comercial. Se a transação da troca for desfeita, os dados voltam junto.
4. **Inelegibilidade temporária não invalida**: conta pendente ou rejeitada, administrador suspenso ou desativado e mudança de status do local não alteram `claimed_by_business_id`; continuam apenas ocultando os dados na leitura, que voltam quando a representação fica elegível de novo. Regravar o mesmo titular também não apaga nada.
5. **Mecanismo independente do T2**: função e trigger próprios. A V28 e o trigger `trg_places_owner_change_invalidates_offers` não mudam; cada política pode evoluir sem afetar a outra.
6. **Sem mudança de contrato**: nenhuma rota, DTO, estado de API, mensagem no aplicativo ou mecanismo de revisão novo. A leitura continua respondendo `configured = false` para local sem configuração, como antes respondia para a configuração oculta.

## Consequências
### Positivas:
- Dados de uma titularidade nunca reaparecem em outra, nem no retorno da mesma conta.
- Banco e leitura passam a concordar: o que existe é sempre declaração do titular atual (ou de titular hoje temporariamente inelegível).
- Regra única no banco, atômica com a troca e independente de qual fluxo a dispare.
- Nenhum impacto em contratos HTTP, no aplicativo mobile ou no painel.

### Negativas / Riscos:
- Perda definitiva: não há recuperação nem histórico dos horários e comodidades apagados, nem da autoria (`updated_by_user_id`). Uma desvinculação feita por engano obriga a empresa a recadastrar.
- Comodidades físicas que não mudam com o titular (acessibilidade, estacionamento) também precisam ser redeclaradas.
- O local fica sem horários e comodidades até o novo titular configurá-los.
- O trigger acopla `places` às tabelas comerciais; novas tabelas que dependam do titular precisam de decisão própria.

## Alternativas Rejeitadas
- **Manter a ocultação reversível**: deixa os dados antigos reaparecerem quando a mesma conta volta a ser titular.
- **Herdar pelo novo titular**: exibe como atuais horários e comodidades da gestão anterior.
- **Reter com revisão pelo novo titular**: exige estado novo, rotas de leitura e confirmação e telas no aplicativo, para um recadastro pequeno (até 7 dias e 8 comodidades).
- **Invalidação lógica (marcar em vez de apagar)**: preservaria histórico, mas não há requisito de auditoria desses dados, e a leitura e as escritas precisariam tratar o novo estado.
- **Estender o trigger T2 das ofertas**: misturaria duas decisões de produto num só mecanismo.
