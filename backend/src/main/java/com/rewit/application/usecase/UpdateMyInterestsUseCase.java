package com.rewit.application.usecase;

import com.rewit.application.dto.user.UserInterestDtos.UpdateMyInterestsCommand;
import com.rewit.application.dto.user.UserInterestDtos.UserInterestProfileView;
import com.rewit.application.port.UserInterestProfileRepository;
import com.rewit.application.service.AccountStatusPolicy;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.InterestCategory;
import com.rewit.domain.model.UserInterestProfile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

/**
 * Grava as respostas do questionário inicial ou a edição posterior dos interesses do usuário autenticado.
 *
 * <p>Substituição integral e idempotente: o mesmo corpo reenviado produz o mesmo estado, e a data de conclusão é a
 * da primeira confirmação. A ordem das verificações é:
 * <ul>
 *   <li>lista validada inteira antes de qualquer leitura ou escrita ({@code 400}: ausente, código fora do catálogo,
 *       elemento nulo ou repetido);</li>
 *   <li>conta operacional ({@code 401 ACCOUNT_DISABLED}), lida sem lock como nas demais mutações
 *       ({@link AccountStatusPolicy});</li>
 *   <li>perfil travado (criado na primeira gravação), o que serializa gravações concorrentes do mesmo usuário;</li>
 *   <li>mínimo de interesses para concluir, ou depois de concluído ({@code 422 INTERESTS_BELOW_MINIMUM}).</li>
 * </ul>
 * Qualquer falha desfaz a transação inteira, inclusive a criação do perfil na primeira gravação.
 */
@Service
public class UpdateMyInterestsUseCase {

    private final AccountStatusPolicy accountStatusPolicy;
    private final UserInterestProfileRepository userInterestProfileRepository;

    public UpdateMyInterestsUseCase(AccountStatusPolicy accountStatusPolicy,
                                    UserInterestProfileRepository userInterestProfileRepository) {
        this.accountStatusPolicy = Objects.requireNonNull(accountStatusPolicy, "AccountStatusPolicy must not be null");
        this.userInterestProfileRepository = Objects.requireNonNull(userInterestProfileRepository,
                "UserInterestProfileRepository must not be null");
    }

    @Transactional
    public UserInterestProfileView execute(UpdateMyInterestsCommand command) {
        Objects.requireNonNull(command, "UpdateMyInterestsCommand must not be null");
        if (command.actorUserId() == null) {
            throw new BusinessException("Usuário não autenticado", HttpStatus.UNAUTHORIZED, "UNAUTHORIZED");
        }
        List<InterestCategory> interests = UserInterestProfile.fromCodes(command.interests());

        accountStatusPolicy.requireOperational(command.actorUserId());

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UserInterestProfile current = userInterestProfileRepository.findOrCreateForUpdate(command.actorUserId(), now);
        UserInterestProfile updated = current.replaceInterests(interests,
                Boolean.TRUE.equals(command.completeOnboarding()), now);
        return UserInterestProfileView.fromDomain(userInterestProfileRepository.save(updated));
    }
}
