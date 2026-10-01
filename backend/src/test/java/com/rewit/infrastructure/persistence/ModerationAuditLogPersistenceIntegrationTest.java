package com.rewit.infrastructure.persistence;

import com.rewit.application.port.ModerationAuditLogRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.application.port.ReviewRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.enums.ModerationAction;
import com.rewit.domain.enums.ModerationDecision;
import com.rewit.domain.enums.ReviewStatus;
import com.rewit.domain.enums.Role;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.ModerationAuditLog;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.RateableTarget;
import com.rewit.domain.model.Review;
import com.rewit.domain.model.ReviewTarget;
import com.rewit.domain.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes Reais de Persistência no PostgreSQL: Governança, Roles e Auditoria (Step 26.1)")
class ModerationAuditLogPersistenceIntegrationTest {

    @Autowired
    private ModerationAuditLogRepository auditLogRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private RateableTargetRepository rateableTargetRepository;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("Persistir User com role USER por default e recuperar com sucesso")
    @Transactional
    void shouldPersistAndRetrieveUserWithDefaultRole() {
        String email = "test.default.role." + UUID.randomUUID() + "@rewit.com";
        User user = new User(null, email, "hash_123", AuthProvider.LOCAL, null);

        assertEquals(Role.USER, user.getRole());

        User savedUser = userRepository.save(user);
        assertNotNull(savedUser);
        assertEquals(Role.USER, savedUser.getRole());

        Optional<User> found = userRepository.findById(savedUser.getId());
        assertTrue(found.isPresent());
        assertEquals(Role.USER, found.get().getRole());
    }

    @Test
    @DisplayName("Persistir e atualizar roles MODERATOR e ADMIN com sucesso no PostgreSQL")
    @Transactional
    void shouldPersistAndRetrieveModeratorAndAdminRoles() {
        String modEmail = "test.mod." + UUID.randomUUID() + "@rewit.com";
        User modUser = new User(null, modEmail, "hash_mod", AuthProvider.LOCAL, null);
        modUser.changeRole(Role.MODERATOR);

        User savedMod = userRepository.save(modUser);
        assertEquals(Role.MODERATOR, savedMod.getRole());

        Optional<User> foundMod = userRepository.findById(savedMod.getId());
        assertTrue(foundMod.isPresent());
        assertEquals(Role.MODERATOR, foundMod.get().getRole());

        // Alterar para ADMIN
        savedMod.changeRole(Role.ADMIN);
        User savedAdmin = userRepository.save(savedMod);
        assertEquals(Role.ADMIN, savedAdmin.getRole());

        Optional<User> foundAdmin = userRepository.findById(savedAdmin.getId());
        assertTrue(foundAdmin.isPresent());
        assertEquals(Role.ADMIN, foundAdmin.get().getRole());
    }

    @Test
    @DisplayName("Banco deve rejeitar inserção com role inválida violando chk_users_role")
    @Transactional
    void shouldRejectInvalidRoleInDatabase() {
        UUID invalidUserId = UUID.randomUUID();
        String invalidEmail = "invalid.role." + invalidUserId + "@rewit.com";

        assertThrows(Exception.class, () -> jdbcTemplate.update(
                "INSERT INTO users (id, email, password_hash, auth_provider, is_active, is_verified, role, created_at, updated_at) " +
                        "VALUES (?, ?, 'hash', 'LOCAL', true, false, 'SUPER_ADMIN', NOW(), NOW())",
                invalidUserId, invalidEmail
        ));
    }

    @Test
    @DisplayName("Persistir ModerationAuditLog no PostgreSQL com sucesso e integridade referencial")
    @Transactional
    void shouldPersistModerationAuditLogWithReferentialIntegrity() {
        // 1. Criar Moderador
        String modEmail = "moderator." + UUID.randomUUID() + "@rewit.com";
        User moderator = new User(null, modEmail, "hash_mod", AuthProvider.LOCAL, null);
        moderator.changeRole(Role.MODERATOR);
        User savedMod = userRepository.save(moderator);

        // 2. Criar Autor e Local para Review
        String authorEmail = "author." + UUID.randomUUID() + "@rewit.com";
        User author = userRepository.save(new User(null, authorEmail, "hash_author", AuthProvider.LOCAL, null));

        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = placeRepository.save(new Place(
                null, "Restaurante Teste " + suffix, "restaurante-teste-" + suffix,
                "RESTAURANTE", "Descrição", "Rua Teste, 123", "123", "Centro",
                "Curitiba", "PR", "BR", -25.4284, -49.2733, 50, "USER", false, null, "ACTIVE"
        ));

        RateableTarget target = rateableTargetRepository.save(new RateableTarget(UUID.randomUUID(), TargetType.PLACE));

        // 3. Criar Review
        Review review = new Review(
                null, author.getId(), place.getId(), "Avaliação a ser moderada",
                false, false,
                ReviewStatus.UNDER_REVIEW, "PUBLIC",
                null, null, null,
                Instant.now(), Instant.now()
        );
        review.addTarget(new ReviewTarget(null, review.getId(), target.getId(), new BigDecimal("3.0"), "Comentário"));
        Review savedReview = reviewRepository.save(review);

        // 4. Criar e persistir ModerationAuditLog
        ModerationAuditLog auditLog = new ModerationAuditLog(
                null,
                savedReview.getId(),
                savedMod.getId(),
                ModerationAction.REMOVE_REVIEW,
                ModerationDecision.ACCEPTED,
                "SPAM_AND_ABUSE",
                "Avaliação viola as diretrizes de conduta da comunidade",
                ReviewStatus.UNDER_REVIEW,
                ReviewStatus.REMOVED,
                2,
                Instant.now()
        );

        ModerationAuditLog savedLog = auditLogRepository.save(auditLog);

        assertNotNull(savedLog);
        assertEquals(savedReview.getId(), savedLog.getReviewId());
        assertEquals(savedMod.getId(), savedLog.getModeratorUserId());
        assertEquals(ModerationAction.REMOVE_REVIEW, savedLog.getAction());
        assertEquals(ModerationDecision.ACCEPTED, savedLog.getDecision());
        assertEquals("SPAM_AND_ABUSE", savedLog.getReasonCode());
        assertEquals("Avaliação viola as diretrizes de conduta da comunidade", savedLog.getJustification());
        assertEquals(ReviewStatus.UNDER_REVIEW, savedLog.getPreviousReviewStatus());
        assertEquals(ReviewStatus.REMOVED, savedLog.getNewReviewStatus());
        assertEquals(2, savedLog.getReportsAffectedCount());
        assertNotNull(savedLog.getCreatedAt());

        // 5. Verificar persistência direta no PostgreSQL via JDBC
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM moderation_audit_logs WHERE id = ?",
                Integer.class,
                savedLog.getId()
        );
        assertEquals(1, count);
    }
}
