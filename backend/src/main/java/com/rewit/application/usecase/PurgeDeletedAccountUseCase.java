package com.rewit.application.usecase;

import com.rewit.application.port.AccountPurgeRepository;
import com.rewit.application.port.AccountPurgeRepository.PurgeCounts;
import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.User;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Purge de uma conta excluída (C2.3): remove ou minimiza os dados pessoais que deixaram de ter uso, sem apagar a
 * linha de {@code users}, que avaliações, comentários, denúncias e auditoria de moderação referenciam (a auditoria
 * com {@code ON DELETE RESTRICT}).
 *
 * <ul>
 *   <li><b>Minimizado</b>: e-mail (vira o valor reservado), credencial, identificador do provedor externo; perfil
 *       (handle reservado, nome neutro, sem bio, avatar nem reputação); coordenadas informadas nas avaliações; a
 *       referência à conta em notificações de outros usuários e em presenças de produto;</li>
 *   <li><b>Removido</b>: sessões, follows, itens salvos, interesses, atividades, notificações próprias e o snapshot
 *       de reputação;</li>
 *   <li><b>Preservado</b>: avaliações, notas, helpful, comentários, mídia, denúncias, auditoria, check-ins e contas
 *       empresariais (estes dois dependem de decisão de produto).</li>
 * </ul>
 *
 * <p>Idempotente: uma segunda execução encontra tudo já minimizado e não muda nada. Concorrência: a linha da conta é
 * travada e o estado relido; só {@code DELETED} é aceito, e nenhuma transição sai de {@code DELETED}, então duas
 * execuções simultâneas são serializadas e a segunda vira no-op. Sem disparo automático nesta etapa.
 */
@Service
public class PurgeDeletedAccountUseCase {

    /**
     * @param identityMinimized e-mail, credencial ou perfil foram alterados nesta execução
     */
    public record PurgeResult(boolean identityMinimized, PurgeCounts relations) {

        public boolean changedAnything() {
            return identityMinimized || relations.total() > 0;
        }
    }

    private final UserRepository userRepository;
    private final ProfileRepository profileRepository;
    private final AccountPurgeRepository accountPurgeRepository;

    public PurgeDeletedAccountUseCase(UserRepository userRepository,
                                      ProfileRepository profileRepository,
                                      AccountPurgeRepository accountPurgeRepository) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository must not be null");
        this.profileRepository = Objects.requireNonNull(profileRepository, "profileRepository must not be null");
        this.accountPurgeRepository = Objects.requireNonNull(accountPurgeRepository, "accountPurgeRepository must not be null");
    }

    @Transactional
    public PurgeResult execute(UUID userId) {
        if (userId == null) {
            throw new BusinessException("Identificador de usuário obrigatório", HttpStatus.BAD_REQUEST, "MISSING_USER_ID");
        }

        User user = userRepository.findByIdForUpdate(userId)
                .orElseThrow(() -> new BusinessException("Usuário não encontrado", HttpStatus.NOT_FOUND, "USER_NOT_FOUND"));

        // Lança 409 ACCOUNT_NOT_DELETED para qualquer outro estado
        boolean userChanged = user.purgePersonalData();
        if (userChanged) {
            userRepository.save(user);
        }

        boolean profileChanged = false;
        Optional<Profile> profile = profileRepository.findByUserId(userId);
        if (profile.isPresent() && profile.get().anonymizeForDeletedAccount()) {
            profileRepository.save(profile.get());
            profileChanged = true;
        }

        PurgeCounts relations = accountPurgeRepository.purgePersonalRelations(userId);
        return new PurgeResult(userChanged || profileChanged, relations);
    }
}
