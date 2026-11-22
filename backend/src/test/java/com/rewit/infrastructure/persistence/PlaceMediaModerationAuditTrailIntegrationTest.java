package com.rewit.infrastructure.persistence;

import com.rewit.application.port.PlaceMediaModerationAuditLogRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.domain.enums.PlaceMediaModerationAction;
import com.rewit.domain.enums.RemovalSource;
import com.rewit.domain.enums.ReviewMediaType;
import com.rewit.domain.enums.Role;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceMedia;
import com.rewit.domain.model.PlaceMediaModerationAuditLog;
import com.rewit.domain.model.User;
import com.rewit.infrastructure.persistence.adapter.PlaceMediaRepositoryAdapter;
import com.rewit.config.PrivilegedTestDatabase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes Reais de Persistência e Integridade Append-Only da Trilha de Moderação de Mídias (V33 / M-1)")
class PlaceMediaModerationAuditTrailIntegrationTest {

    @Autowired
    private PlaceMediaModerationAuditLogRepository auditLogRepository;

    @Autowired
    private com.rewit.application.port.MediaLifecycleTransactionPort mediaLifecycleTransactionPort;

    @Autowired
    private PlaceMediaRepositoryAdapter placeMediaRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @jakarta.persistence.PersistenceContext
    private jakarta.persistence.EntityManager entityManager;

    private User createModerator() {
        // Papel é atribuição administrativa: moderador criado e confirmado fora do runtime (visível nos testes transacionais)
        return userRepository.findById(PrivilegedTestDatabase.createUser("mod", Role.MODERATOR.name())).orElseThrow();
    }

    private Place createPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        return placeRepository.save(new Place(
                null, "Local V33 " + suffix, "local-v33-" + suffix,
                "RESTAURANTE", "Descrição", "Rua Teste, 100", "100", "Bairro",
                "Cidade", "UF", "BR", -23.55, -46.63, 50, "USER", false, null, "ACTIVE"
        ));
    }

    private PlaceMedia createMedia(Place place, User user) {
        String key = "places/" + place.getId() + "/" + UUID.randomUUID() + "/image.jpg";
        PlaceMedia media = new PlaceMedia(
                UUID.randomUUID(), place.getId(), user.getId(), key,
                ReviewMediaType.IMAGE, "image/jpeg", 1024L, 800, 600
        );
        return placeMediaRepository.save(media);
    }

    @Test
    @DisplayName("Deve persistir auditoria de AUTHOR_REMOVE com sucesso")
    void shouldPersistAuthorRemoveAuditLog() {
        User user = createModerator();
        Place place = createPlace();
        PlaceMedia media = createMedia(place, user);
        media.markRemovedByAuthor();
        String mac = "aud1$v1$" + "c".repeat(64);
        PlaceMediaModerationAuditLog log = new PlaceMediaModerationAuditLog(
                UUID.randomUUID(),
                media.getId(),
                place.getId(),
                user.getId(),
                PlaceMediaModerationAuditLog.ActorType.USER,
                PlaceMediaModerationAction.AUTHOR_REMOVE,
                "Remoção pelo próprio autor",
                "Exclusão voluntária",
                null,
                Instant.now(),
                mac
        );

        PlaceMediaModerationAuditLog saved = mediaLifecycleTransactionPort.execute(() -> {
            placeMediaRepository.save(media);
            placeMediaRepository.flush();
            return auditLogRepository.save(log);
        });
        assertNotNull(saved);
        assertEquals(PlaceMediaModerationAction.AUTHOR_REMOVE, saved.getAction());

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM place_media_moderation_audit_logs WHERE id = ? AND action = 'AUTHOR_REMOVE'",
                Integer.class,
                saved.getId()
        );
        assertEquals(1, count);
    }

    @Test
    @DisplayName("Trigger no banco deve rejeitar UPDATE em place_media_moderation_audit_logs (append-only)")
    void shouldRejectUpdateOnAuditLog() {
        User user = createModerator();
        Place place = createPlace();
        PlaceMedia media = createMedia(place, user);

        UUID logId = UUID.randomUUID();
        com.rewit.config.PrivilegedTestDatabase.jdbc().update(
                "INSERT INTO place_media_moderation_audit_logs (id, place_media_id, place_id, actor_user_id, actor_type, action, reason, notes, created_at, authenticity_mac) "
                        + "VALUES (?, ?, ?, ?, 'USER', 'REMOVE', 'Conteúdo impróprio', 'Notas', NOW(), ?)",
                logId, media.getId(), place.getId(), user.getId(), "aud1$v1$" + "c".repeat(64));

        org.springframework.dao.DataAccessException ex = assertThrows(org.springframework.dao.DataAccessException.class, () ->
                jdbcTemplate.update(
                        "UPDATE place_media_moderation_audit_logs SET reason = 'Alterado' WHERE id = ?",
                        logId
                )
        );
        String err = (ex.getMessage() + " " + ex.getMostSpecificCause().getMessage()).toLowerCase();
        assertTrue(err.contains("append-only")
                || err.contains("não permitido")
                || err.contains("permission")
                || err.contains("permiss")
                || err.contains("42501"), "Erro inesperado no UPDATE: " + err);
    }

    @Test
    @DisplayName("Trigger no banco deve rejeitar DELETE em place_media_moderation_audit_logs (append-only)")
    void shouldRejectDeleteOnAuditLog() {
        User user = createModerator();
        Place place = createPlace();
        PlaceMedia media = createMedia(place, user);

        UUID logId = UUID.randomUUID();
        com.rewit.config.PrivilegedTestDatabase.jdbc().update(
                "INSERT INTO place_media_moderation_audit_logs (id, place_media_id, place_id, actor_user_id, actor_type, action, notes, created_at, authenticity_mac) "
                        + "VALUES (?, ?, ?, ?, 'USER', 'RESTORE', 'Restaurada', NOW(), ?)",
                logId, media.getId(), place.getId(), user.getId(), "aud1$v1$" + "c".repeat(64));

        org.springframework.dao.DataAccessException ex = assertThrows(org.springframework.dao.DataAccessException.class, () ->
                jdbcTemplate.update(
                        "DELETE FROM place_media_moderation_audit_logs WHERE id = ?",
                        logId
                )
        );
        String err = (ex.getMessage() + " " + ex.getMostSpecificCause().getMessage()).toLowerCase();
        assertTrue(err.contains("append-only")
                || err.contains("não permitido")
                || err.contains("permission")
                || err.contains("permiss")
                || err.contains("42501"), "Erro inesperado no DELETE: " + err);
    }

    @Test
    @DisplayName("CHECK constraint deve rejeitar ação inválida na auditoria")
    void shouldRejectInvalidAuditAction() {
        User user = createModerator();
        Place place = createPlace();
        PlaceMedia media = createMedia(place, user);

        UUID logId = UUID.randomUUID();
        assertThrows(DataIntegrityViolationException.class, () ->
                com.rewit.config.PrivilegedTestDatabase.jdbc().update(
                        "INSERT INTO place_media_moderation_audit_logs (id, place_media_id, place_id, actor_user_id, action, created_at) " +
                                "VALUES (?, ?, ?, ?, 'INVALID_ACTION', NOW())",
                        logId, media.getId(), place.getId(), user.getId()
                )
        );
    }

    @Test
    @DisplayName("CHECK constraint em place_media deve aceitar AUTHOR, MODERATOR e NULL, rejeitando inválido")
    void shouldValidateRemovalSourceConstraint() {
        User user = createModerator();
        Place place = createPlace();
        PlaceMedia media = createMedia(place, user);

        // Deve aceitar MODERATOR, AUTHOR, BUSINESS_OWNER e NULL (verificado no schema via owner)
        for (String src : java.util.List.of("MODERATOR", "AUTHOR", "BUSINESS_OWNER")) {
            com.rewit.config.PrivilegedTestDatabase.jdbc().update(
                    "UPDATE place_media SET status = 'REMOVED', removal_source = ?, removed_at = NOW() WHERE id = ?",
                    src, media.getId());
            assertEquals(RemovalSource.valueOf(src), placeMediaRepository.findById(media.getId()).orElseThrow().getRemovalSource());
        }
        com.rewit.config.PrivilegedTestDatabase.jdbc().update(
                "UPDATE place_media SET status = 'ACTIVE', removal_source = NULL, removed_at = NULL WHERE id = ?",
                media.getId());
        assertNull(placeMediaRepository.findById(media.getId()).orElseThrow().getRemovalSource());

        // Deve rejeitar valor inválido via CHECK constraint do PostgreSQL
        assertThrows(DataIntegrityViolationException.class, () ->
                com.rewit.config.PrivilegedTestDatabase.jdbc().update(
                        "UPDATE place_media SET removal_source = 'INVALID_SOURCE' WHERE id = ?",
                        media.getId()
                )
        );
    }

    @Test
    @DisplayName("Trigger no banco deve rejeitar TRUNCATE em place_media_moderation_audit_logs (append-only V34)")
    void shouldRejectTruncateOnAuditLog() {
        org.springframework.dao.DataAccessException ex = assertThrows(org.springframework.dao.DataAccessException.class, () ->
                jdbcTemplate.execute("TRUNCATE TABLE place_media_moderation_audit_logs")
        );
        String err = (ex.getMessage() + " " + ex.getMostSpecificCause().getMessage()).toLowerCase();
        assertTrue(err.contains("append-only")
                || err.contains("não permitido")
                || err.contains("permission")
                || err.contains("permiss")
                || err.contains("42501"), "Erro inesperado no TRUNCATE: " + err);
    }

    @Test
    @DisplayName("Deve persistir auditoria de AUTO_QUARANTINE e BUSINESS_OWNER_REMOVE com sucesso")
    void shouldPersistAutoQuarantineAndBusinessOwnerRemoveAuditLogs() {
        User user = createModerator();
        Place place = createPlace();
        PlaceMedia media = createMedia(place, user);

        // 3 denúncias pendentes + estado UNDER_REVIEW para AUTO_QUARANTINE legítimo sob rewit_media
        String mac = "aud1$v1$" + "d".repeat(64);
        PlaceMediaModerationAuditLog autoQuarantineLog = new PlaceMediaModerationAuditLog(
                UUID.randomUUID(),
                media.getId(),
                place.getId(),
                null,
                PlaceMediaModerationAuditLog.ActorType.SYSTEM,
                PlaceMediaModerationAction.AUTO_QUARANTINE,
                "Quarentena automática por excesso de denúncias",
                "Limiar atingido",
                null,
                Instant.now(),
                mac
        );
        mediaLifecycleTransactionPort.run(() -> {
            for (int i = 0; i < 3; i++) {
                UUID repUser = com.rewit.config.PrivilegedTestDatabase.createUser("pm_rep_" + i, "USER");
                jdbcTemplate.update("INSERT INTO place_media_reports (id, place_media_id, reporter_user_id, reason) VALUES (?, ?, ?, 'SPAM')",
                        UUID.randomUUID(), media.getId(), repUser);
            }
            media.markUnderReview();
            placeMediaRepository.save(media);
            placeMediaRepository.flush();
            auditLogRepository.save(autoQuarantineLog);
        });

        // Conta comercial aprovada vinculada ao local para BUSINESS_OWNER_REMOVE legítimo sob rewit_media
        UUID bizId = com.rewit.config.PrivilegedTestDatabase.jdbc().queryForObject(
                "INSERT INTO business_accounts (user_id, corporate_name, tax_id, verification_status) VALUES (?, 'Empresa PM', ?, 'APPROVED') RETURNING id",
                UUID.class, user.getId(), UUID.randomUUID().toString().replace("-", "").substring(0, 14));
        com.rewit.config.PrivilegedTestDatabase.setPlaceClaimedBy(place.getId(), bizId);

        PlaceMediaModerationAuditLog ownerRemoveLog = new PlaceMediaModerationAuditLog(
                UUID.randomUUID(),
                media.getId(),
                place.getId(),
                user.getId(),
                PlaceMediaModerationAuditLog.ActorType.USER,
                PlaceMediaModerationAction.BUSINESS_OWNER_REMOVE,
                "Remoção pelo proprietário do estabelecimento",
                "Foto desatualizada",
                null,
                Instant.now(),
                mac
        );
        mediaLifecycleTransactionPort.run(() -> {
            media.markRemovedByBusinessOwner();
            placeMediaRepository.save(media);
            placeMediaRepository.flush();
            auditLogRepository.save(ownerRemoveLog);
        });

        Integer countAuto = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM place_media_moderation_audit_logs WHERE id = ? AND action = 'AUTO_QUARANTINE'",
                Integer.class,
                autoQuarantineLog.getId()
        );
        assertEquals(1, countAuto);

        Integer countOwner = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM place_media_moderation_audit_logs WHERE id = ? AND action = 'BUSINESS_OWNER_REMOVE'",
                Integer.class,
                ownerRemoveLog.getId()
        );
        assertEquals(1, countOwner);
    }
}

