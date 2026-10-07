package com.rewit.application.port;

import java.util.UUID;

/**
 * Remoção e minimização dos dados relacionais de uma conta excluída (purge, C2.3). Cada operação é idempotente: numa
 * segunda execução não há mais o que remover nem anonimizar, e os contadores voltam zerados.
 *
 * <p>Não toca o que a plataforma preserva: avaliações, notas, helpful, comentários, mídia, denúncias, auditoria de
 * moderação, check-ins e contas empresariais.
 */
public interface AccountPurgeRepository {

    /**
     * @param sessions              sessões de autenticação removidas (nenhuma reutilizável)
     * @param follows               vínculos de follow removidos, nos dois sentidos
     * @param savedItems            itens salvos removidos
     * @param interests             interesses removidos
     * @param activities            atividades removidas
     * @param ownNotifications      notificações da própria conta removidas
     * @param othersNotifications   notificações de outros usuários em que a conta deixou de ser referenciada
     * @param reputationSnapshots   snapshot de reputação removido (derivado e recalculável)
     * @param productPresences      presenças de produto que deixaram de atribuir o relato à conta
     * @param reviewLocations       avaliações sem as coordenadas informadas pelo usuário
     */
    record PurgeCounts(
            int sessions,
            int follows,
            int savedItems,
            int interests,
            int activities,
            int ownNotifications,
            int othersNotifications,
            int reputationSnapshots,
            int productPresences,
            int reviewLocations
    ) {
        public static PurgeCounts none() {
            return new PurgeCounts(0, 0, 0, 0, 0, 0, 0, 0, 0, 0);
        }

        public int total() {
            return sessions + follows + savedItems + interests + activities + ownNotifications + othersNotifications
                    + reputationSnapshots + productPresences + reviewLocations;
        }
    }

    PurgeCounts purgePersonalRelations(UUID userId);
}
