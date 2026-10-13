package com.rewit.application.usecase;

import com.rewit.application.dto.business.BusinessResponseDtos.AdminRemovedBusinessResponseView;
import com.rewit.application.dto.business.BusinessResponseDtos.RemovedBusinessResponseFilter;
import com.rewit.application.dto.business.BusinessResponseDtos.RemovedBusinessResponseRecord;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.BusinessResponseRepository;
import com.rewit.application.service.ModeratorRolePolicy;
import com.rewit.common.exception.BusinessException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Consulta administrativa das respostas oficiais removidas (C11), para a moderação localizar respostas e decidir quais
 * restaurar. Somente leitura.
 *
 * <p>Cargo e conta do ator revalidados no banco antes de qualquer leitura ({@link ModeratorRolePolicy}: ator ausente
 * {@code 401 UNAUTHORIZED}, inoperante {@code 401 ACCOUNT_DISABLED}, sem MODERATOR/ADMIN {@code 403 FORBIDDEN}).
 *
 * <p>Cada item diz se a restauração passaria agora ({@code restorable}): só a remoção feita pela moderação é
 * restaurável (regra do domínio) e só enquanto a empresa não tiver outra resposta ativa no mesmo alvo. É uma
 * indicação: {@link ModerateBusinessResponseUseCase} revalida tudo com a resposta travada.
 *
 * <p>Ordem: removidas mais recentemente primeiro, desempate por id (removed_at DESC, id DESC). Sem o autor pessoal da
 * resposta nem o documento da empresa.
 */
@Service
public class QueryAdminRemovedBusinessResponsesUseCase {

    public static final int MAX_PAGE_SIZE = 50;

    private final BusinessResponseRepository businessResponseRepository;
    private final ModeratorRolePolicy moderatorRolePolicy;

    public QueryAdminRemovedBusinessResponsesUseCase(BusinessResponseRepository businessResponseRepository,
                                                     ModeratorRolePolicy moderatorRolePolicy) {
        this.businessResponseRepository = Objects.requireNonNull(businessResponseRepository,
                "BusinessResponseRepository must not be null");
        this.moderatorRolePolicy = Objects.requireNonNull(moderatorRolePolicy, "ModeratorRolePolicy must not be null");
    }

    @Transactional(readOnly = true)
    public PageResult<AdminRemovedBusinessResponseView> execute(UUID actorUserId, RemovedBusinessResponseFilter filter,
                                                                int page, int size) {
        if (page < 0) {
            throw new BusinessException("O número da página não pode ser negativo", HttpStatus.BAD_REQUEST, "INVALID_PAGE");
        }
        if (size < 1 || size > MAX_PAGE_SIZE) {
            throw new BusinessException("O tamanho da página deve ser entre 1 e 50", HttpStatus.BAD_REQUEST,
                    "INVALID_PAGE_SIZE");
        }
        moderatorRolePolicy.requireCurrentModerator(actorUserId);

        PageResult<RemovedBusinessResponseRecord> records = businessResponseRepository.findRemovedForModeration(
                filter != null ? filter : RemovedBusinessResponseFilter.none(), page, size);
        List<AdminRemovedBusinessResponseView> views = records.content().stream()
                .map(record -> AdminRemovedBusinessResponseView.of(record))
                .toList();
        return PageResult.of(views, records.pageNumber(), records.pageSize(), records.totalElements());
    }
}
