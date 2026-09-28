package com.rewit.infrastructure.persistence;

import com.rewit.application.port.ProfileRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.AuthProvider;
import com.rewit.domain.model.Profile;
import com.rewit.domain.model.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes Reais de Persistência no PostgreSQL: User e Profile (Step 3)")
class UserAndProfilePersistenceTest {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProfileRepository profileRepository;

    @Test
    @DisplayName("Persistir User e recuperar por UUID e por e-mail normalizado")
    @Transactional
    void shouldPersistAndRetrieveUserByUuidAndNormalizedEmail() {
        String rawEmail = "  Pedro.Alvares@REWIT.COM  ";
        User user = new User(null, rawEmail, "secret_hash_123", AuthProvider.LOCAL, null);

        User savedUser = userRepository.save(user);
        assertNotNull(savedUser);
        assertEquals(user.getId(), savedUser.getId());
        assertEquals("pedro.alvares@rewit.com", savedUser.getEmail());

        // Recuperar por UUID
        Optional<User> foundById = userRepository.findById(savedUser.getId());
        assertTrue(foundById.isPresent());
        assertEquals(savedUser.getId(), foundById.get().getId());
        assertEquals("pedro.alvares@rewit.com", foundById.get().getEmail());

        // Recuperar por e-mail (com maiúsculas e espaços)
        Optional<User> foundByEmail = userRepository.findByEmail("  PEDRO.ALVARES@rewit.com  ");
        assertTrue(foundByEmail.isPresent());
        assertEquals(savedUser.getId(), foundByEmail.get().getId());

        // existsByEmail
        assertTrue(userRepository.existsByEmail("pedro.alvares@rewit.com"));
        assertTrue(userRepository.existsByEmail("PEDRO.ALVARES@REWIT.COM"));
        assertFalse(userRepository.existsByEmail("inexistente@rewit.com"));
    }

    @Test
    @DisplayName("Rejeitar e-mail duplicado case-insensitive no PostgreSQL")
    @Transactional
    void shouldRejectDuplicateEmailCaseInsensitive() {
        String email = "unicidade.teste@rewit.com";
        User user1 = new User(null, email, "hash1", AuthProvider.LOCAL, null);
        userRepository.save(user1);

        // Tentar salvar outro usuário com o mesmo e-mail em caixa alta
        User user2 = new User(null, "UNICIDADE.TESTE@REWIT.COM", "hash2", AuthProvider.LOCAL, null);

        // O PostgreSQL com o índice único uq_users_email_lower deve rejeitar
        assertThrows(Exception.class, () -> {
            userRepository.save(user2);
        });
    }

    @Test
    @DisplayName("Persistir Profile vinculado a User e recuperar por userId e handle")
    @Transactional
    void shouldPersistProfileLinkedToUserAndRetrieve() {
        User user = new User(null, "usuario.perfil@rewit.com", "hash", AuthProvider.LOCAL, null);
        userRepository.save(user);

        Profile profile = new Profile(null, user.getId(), "@Viajante_Curitiba", "Viajante de Curitiba", "Bio de viagem", "https://img.rewit.com/avatar.jpg");
        Profile savedProfile = profileRepository.save(profile);

        assertNotNull(savedProfile);
        assertEquals("viajante_curitiba", savedProfile.getHandle().toLowerCase());
        assertEquals(user.getId(), savedProfile.getUserId());

        // Recuperar por userId
        Optional<Profile> foundByUserId = profileRepository.findByUserId(user.getId());
        assertTrue(foundByUserId.isPresent());
        assertEquals(savedProfile.getId(), foundByUserId.get().getId());
        assertEquals("Viajante de Curitiba", foundByUserId.get().getDisplayName());

        // Recuperar por handle case-insensitive
        Optional<Profile> foundByHandle = profileRepository.findByHandle("VIAJANTE_CURITIBA");
        assertTrue(foundByHandle.isPresent());
        assertEquals(user.getId(), foundByHandle.get().getUserId());

        // Checar existência
        assertTrue(profileRepository.existsByHandle("@viajante_curitiba"));
        assertTrue(profileRepository.existsByUserId(user.getId()));
    }

    @Test
    @DisplayName("Impedir Profile órfão quando userId não existir no banco")
    void shouldPreventOrphanProfile() {
        UUID nonExistentUserId = UUID.randomUUID();
        Profile orphanProfile = new Profile(null, nonExistentUserId, "orfao_user", "Orfão", null, null);

        BusinessException ex = assertThrows(BusinessException.class, () -> {
            profileRepository.save(orphanProfile);
        });

        assertEquals("USER_NOT_FOUND", ex.getErrorCode());
        assertFalse(profileRepository.existsByHandle("orfao_user"));
    }

    @Test
    @DisplayName("Garantir relação 1:1 estrita entre User e Profile")
    @Transactional
    void shouldEnforceOneToOneRelationshipBetweenUserAndProfile() {
        User user = new User(null, "one.to.one@rewit.com", "hash", AuthProvider.LOCAL, null);
        userRepository.save(user);

        Profile profile1 = new Profile(null, user.getId(), "perfil_um", "Perfil 1", null, null);
        profileRepository.save(profile1);

        // Tentar criar um segundo perfil para o mesmo usuário
        Profile profile2 = new Profile(null, user.getId(), "perfil_dois", "Perfil 2", null, null);

        assertThrows(Exception.class, () -> {
            profileRepository.save(profile2);
        });
    }

    @Test
    @DisplayName("Soft-delete: Usuário soft-deleted não deve aparecer em buscas ativas, mas e-mail permanece reservado")
    @Transactional
    void shouldExcludeSoftDeletedUserFromActiveQueriesWhileEmailRemainsReserved() {
        String email = "soft.delete.test@rewit.com";
        User user = new User(null, email, "hash", AuthProvider.LOCAL, null);
        userRepository.save(user);

        // Soft delete
        user.softDelete();
        userRepository.save(user);

        // Usuário não deve aparecer em buscas ativas normais
        Optional<User> activeUserById = userRepository.findById(user.getId());
        assertFalse(activeUserById.isPresent(), "Usuário soft-deleted não deve ser retornado por findById");

        Optional<User> activeUserByEmail = userRepository.findByEmail(email);
        assertFalse(activeUserByEmail.isPresent(), "Usuário soft-deleted não deve ser retornado por findByEmail");

        // Porém o e-mail permanece reservado no sistema
        assertTrue(userRepository.existsByEmail(email), "existsByEmail deve retornar true para e-mail reservado");

        // Tentativa de cadastrar novo usuário com o mesmo e-mail deve falhar no banco (regra de MVP)
        User impostor = new User(null, email, "nova_senha", AuthProvider.LOCAL, null);
        assertThrows(Exception.class, () -> {
            userRepository.save(impostor);
        });
    }

    @Test
    @DisplayName("Garantir unicidade de provedor federado (GOOGLE, providerUserId)")
    @Transactional
    void shouldEnforceFederatedIdentityUniqueness() {
        String remoteId = "google-oauth-unique-998877";

        User googleUser1 = new User(null, "google1@gmail.com", null, AuthProvider.GOOGLE, remoteId);
        userRepository.save(googleUser1);

        // Buscar por provedor e ID remoto ativo
        Optional<User> found = userRepository.findByAuthProviderAndProviderUserId(AuthProvider.GOOGLE, remoteId);
        assertTrue(found.isPresent());
        assertEquals(googleUser1.getId(), found.get().getId());

        // Tentar cadastrar outro usuário com o mesmo remoteId do Google deve violar a constraint
        User googleUserDuplicate = new User(null, "google2@gmail.com", null, AuthProvider.GOOGLE, remoteId);
        assertThrows(Exception.class, () -> {
            userRepository.save(googleUserDuplicate);
        });
    }

    @Test
    @DisplayName("Permitir múltiplos usuários LOCAL com providerUserId nulo sem colisão")
    @Transactional
    void shouldAllowMultipleLocalUsersWithNullProviderUserId() {
        User local1 = new User(null, "local1@rewit.com", "hash1", AuthProvider.LOCAL, null);
        User local2 = new User(null, "local2@rewit.com", "hash2", AuthProvider.LOCAL, null);

        assertDoesNotThrow(() -> {
            userRepository.save(local1);
            userRepository.save(local2);
        });
    }
}
