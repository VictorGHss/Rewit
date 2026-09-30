package com.rewit.infrastructure.persistence;

import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.ReputationRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.User;
import com.rewit.domain.model.UserReputation;
import com.rewit.infrastructure.persistence.repository.UserReputationJpaRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Testes de integração PostgreSQL real para o subsistema de Reputação V1 (Step 23.0).
 * Valida: FK, unique por user, persistência, upsert, isolamento e consistência.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração PostgreSQL: Reputação V1 (Step 23.0)")
class ReputationPersistenceIntegrationTest {

    @Autowired private ReputationRepository reputationRepository;
    @Autowired private UserReputationJpaRepository jpaRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private ProfileRepository profileRepository;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("Snapshot novo e persistido e recuperado corretamente")
    void shouldSaveAndFindReputationSnapshot() {
        User user = createUser("rep_persist_a");
        UserReputation rep = new UserReputation(user.getId(), 1, 5, 3, 12, 4, Instant.now());

        reputationRepository.save(rep);

        Optional<UserReputation> found = reputationRepository.findByUserId(user.getId());
        assertTrue(found.isPresent());
        assertEquals(5,  found.get().getActiveReviews());
        assertEquals(3,  found.get().getVerifiedReviews());
        assertEquals(12, found.get().getHelpfulVotesReceived());
        assertEquals(4,  found.get().getDistinctTargetsReviewed());
        assertEquals(1,  found.get().getVersion());
        assertEquals(user.getId(), found.get().getUserId());
    }

    @Test
    @DisplayName("Upsert sobrescreve snapshot anterior para o mesmo userId")
    void shouldUpsertReputationSnapshot() {
        User user = createUser("rep_upsert_b");
        UserReputation first  = new UserReputation(user.getId(), 1, 2, 1, 5, 2, Instant.now());
        UserReputation second = new UserReputation(user.getId(), 1, 8, 4, 20, 6, Instant.now());

        reputationRepository.save(first);
        reputationRepository.save(second);

        Optional<UserReputation> found = reputationRepository.findByUserId(user.getId());
        assertTrue(found.isPresent());
        // Deve refletir o segundo snapshot
        assertEquals(8,  found.get().getActiveReviews());
        assertEquals(4,  found.get().getVerifiedReviews());
        assertEquals(20, found.get().getHelpfulVotesReceived());
        assertEquals(6,  found.get().getDistinctTargetsReviewed());

        // Deve haver exatamente 1 registro para o userId
        assertEquals(1L, jpaRepository.findAll().stream()
            .filter(e -> e.getUserId().equals(user.getId())).count());
    }

    @Test
    @DisplayName("findByUserId retorna Optional.empty para userId inexistente")
    void shouldReturnEmptyForUnknownUser() {
        Optional<UserReputation> found = reputationRepository.findByUserId(UUID.randomUUID());
        assertFalse(found.isPresent());
    }

    @Test
    @DisplayName("Reputacoes de usuarios diferentes sao isoladas")
    void shouldIsolateReputationsBetweenUsers() {
        User userA = createUser("rep_iso_a");
        User userB = createUser("rep_iso_b");

        reputationRepository.save(new UserReputation(userA.getId(), 1, 10, 5, 30, 8, Instant.now()));
        reputationRepository.save(new UserReputation(userB.getId(), 1,  2, 0,  3, 1, Instant.now()));

        Optional<UserReputation> repA = reputationRepository.findByUserId(userA.getId());
        Optional<UserReputation> repB = reputationRepository.findByUserId(userB.getId());

        assertTrue(repA.isPresent());
        assertTrue(repB.isPresent());
        assertEquals(10, repA.get().getActiveReviews());
        assertEquals(2,  repB.get().getActiveReviews());
    }

    @Test
    @DisplayName("verifiedReviews <= activeReviews e constraint do banco nao e violada")
    void shouldRespectVerifiedLteActiveConstraint() {
        User user = createUser("rep_constraint_c");
        // verifiedReviews == activeReviews e o limite aceitavel
        UserReputation rep = new UserReputation(user.getId(), 1, 5, 5, 10, 3, Instant.now());

        assertDoesNotThrow(() -> reputationRepository.save(rep));
        Optional<UserReputation> found = reputationRepository.findByUserId(user.getId());
        assertTrue(found.isPresent());
        assertEquals(found.get().getVerifiedReviews(), found.get().getActiveReviews());
    }

    @Test
    @DisplayName("calculatedAt e persistido e recuperado corretamente")
    void shouldPersistCalculatedAt() {
        User user = createUser("rep_ts_d");
        Instant before = Instant.now().minusSeconds(1);
        reputationRepository.save(new UserReputation(user.getId(), 1, 1, 0, 2, 1, Instant.now()));

        Optional<UserReputation> found = reputationRepository.findByUserId(user.getId());
        assertTrue(found.isPresent());
        assertNotNull(found.get().getCalculatedAt());
        assertTrue(found.get().getCalculatedAt().isAfter(before));
    }

    @Test
    @DisplayName("Consistencia apos rollback: snapshot nao deve ser criado")
    void shouldNotPersistSnapshotAfterRollback() {
        User user = createUser("rep_rollback_e");
        long initialCount = jpaRepository.count();

        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        assertThrows(RuntimeException.class, () ->
            tx.execute(status -> {
                UserReputation rep = new UserReputation(user.getId(), 1, 2, 1, 3, 2, Instant.now());
                reputationRepository.save(rep);
                throw new RuntimeException("Simulando falha para rollback de snapshot de reputacao");
            })
        );

        assertEquals(initialCount, jpaRepository.count(),
            "Snapshot de reputação deve ser revertido após rollback");
        assertTrue(reputationRepository.findByUserId(user.getId()).isEmpty());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private User createUser(String prefix) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String email  = prefix + "_" + suffix + "@reputation.test";
        User user = new User(null, email, "$2a$10$abcdefghijklmnopqrstuvwxyzABCDEF1234567890",
            AuthProvider.LOCAL, null);
        user = userRepository.save(user);
        Profile profile = new Profile(UUID.randomUUID(), user.getId(), "r_" + suffix,
            "Rep " + suffix, "Bio", null);
        profileRepository.save(profile);
        return user;
    }
}
