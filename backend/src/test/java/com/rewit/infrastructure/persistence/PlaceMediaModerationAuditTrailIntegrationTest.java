package com.rewit.infrastructure.persistence;

import com.rewit.application.port.PlaceMediaModerationAuditLogRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.PlaceMediaModerationAction;
import com.rewit.domain.enums.RemovalSource;
import com.rewit.domain.enums.ReviewMediaType;
import com.rewit.domain.enums.Role;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceMedia;
import com.rewit.domain.model.PlaceMediaModerationAuditLog;
import com.rewit.domain.model.User;
import com.rewit.infrastructure.persistence.adapter.PlaceMediaRepositoryAdapter;
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
        String email = "mod." + UUID.randomUUID() + "@rewit.com";
        User mod = new User(null, email, "hash_mod", AuthProvider.LOCAL, null);
        mod.changeRole(Role.MODERATOR);
        return userRepository.save(mod);
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
    @Transactional
    void shouldPersistAuthorRemoveAuditLog() {
        User user = createModerator();
        Place place = createPlace();
        PlaceMedia media = createMedia(place, user);

        PlaceMediaModerationAuditLog log = new PlaceMediaModerationAuditLog(
                UUID.randomUUID(),
                media.getId(),
                place.getId(),
                user.getId(),
                PlaceMediaModerationAction.AUTHOR_REMOVE,
                "Remoção pelo próprio autor",
                "Exclusão voluntária",
                Instant.now()
        );

        PlaceMediaModerationAuditLog saved = auditLogRepository.save(log);
        assertNotNull(saved);
        assertEquals(PlaceMediaModerationAction.AUTHOR_REMOVE, saved.getAction());
        entityManager.flush();

        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM place_media_moderation_audit_logs WHERE id = ? AND action = 'AUTHOR_REMOVE'",
                Integer.class,
                saved.getId()
        );
        assertEquals(1, count);
    }

    @Test
    @DisplayName("Trigger no banco deve rejeitar UPDATE em place_media_moderation_audit_logs (append-only)")
    @Transactional
    void shouldRejectUpdateOnAuditLog() {
        User user = createModerator();
        Place place = createPlace();
        PlaceMedia media = createMedia(place, user);

        PlaceMediaModerationAuditLog log = new PlaceMediaModerationAuditLog(
                UUID.randomUUID(),
                media.getId(),
                place.getId(),
                user.getId(),
                PlaceMediaModerationAction.REMOVE,
                "Conteúdo impróprio",
                "Notas",
                Instant.now()
        );
        auditLogRepository.save(log);
        entityManager.flush();

        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () ->
                jdbcTemplate.update(
                        "UPDATE place_media_moderation_audit_logs SET reason = 'Alterado' WHERE id = ?",
                        log.getId()
                )
        );
        assertTrue(ex.getMessage().contains("append-only") || ex.getMessage().contains("UPDATE não permitido"));
    }

    @Test
    @DisplayName("Trigger no banco deve rejeitar DELETE em place_media_moderation_audit_logs (append-only)")
    @Transactional
    void shouldRejectDeleteOnAuditLog() {
        User user = createModerator();
        Place place = createPlace();
        PlaceMedia media = createMedia(place, user);

        PlaceMediaModerationAuditLog log = new PlaceMediaModerationAuditLog(
                UUID.randomUUID(),
                media.getId(),
                place.getId(),
                user.getId(),
                PlaceMediaModerationAction.RESTORE,
                null,
                "Restaurada",
                Instant.now()
        );
        auditLogRepository.save(log);
        entityManager.flush();

        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, () ->
                jdbcTemplate.update(
                        "DELETE FROM place_media_moderation_audit_logs WHERE id = ?",
                        log.getId()
                )
        );
        assertTrue(ex.getMessage().contains("append-only") || ex.getMessage().contains("DELETE não"));
    }

    @Test
    @DisplayName("CHECK constraint deve rejeitar ação inválida na auditoria")
    @Transactional
    void shouldRejectInvalidAuditAction() {
        User user = createModerator();
        Place place = createPlace();
        PlaceMedia media = createMedia(place, user);

        UUID logId = UUID.randomUUID();
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbcTemplate.update(
                        "INSERT INTO place_media_moderation_audit_logs (id, place_media_id, place_id, actor_user_id, action, created_at) " +
                                "VALUES (?, ?, ?, ?, 'INVALID_ACTION', NOW())",
                        logId, media.getId(), place.getId(), user.getId()
                )
        );
    }

    @Test
    @DisplayName("CHECK constraint em place_media deve aceitar AUTHOR, MODERATOR e NULL, rejeitando inválido")
    @Transactional
    void shouldValidateRemovalSourceConstraint() {
        User user = createModerator();
        Place place = createPlace();
        PlaceMedia media = createMedia(place, user);

        // Deve aceitar MODERATOR
        media.markRemovedByModerator();
        PlaceMedia savedMod = placeMediaRepository.save(media);
        assertEquals(RemovalSource.MODERATOR, savedMod.getRemovalSource());

        // Deve aceitar AUTHOR
        media.markRemovedByAuthor();
        PlaceMedia savedAuth = placeMediaRepository.save(media);
        assertEquals(RemovalSource.AUTHOR, savedAuth.getRemovalSource());

        // Deve aceitar NULL (quando restaurado/ativo)
        media.markActive();
        PlaceMedia savedActive = placeMediaRepository.save(media);
        assertNull(savedActive.getRemovalSource());
        entityManager.flush();

        // Deve rejeitar valor inválido via SQL
        assertThrows(DataIntegrityViolationException.class, () ->
                jdbcTemplate.update(
                        "UPDATE place_media SET removal_source = 'INVALID_SOURCE' WHERE id = ?",
                        media.getId()
                )
        );
    }
}

