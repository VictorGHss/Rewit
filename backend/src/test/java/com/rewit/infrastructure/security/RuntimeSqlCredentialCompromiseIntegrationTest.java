package com.rewit.infrastructure.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.TokenService;
import com.rewit.config.PrivilegedTestDatabase;
import com.rewit.presentation.dto.auth.LoginRequest;
import com.rewit.presentation.dto.auth.LogoutRequest;
import com.rewit.presentation.dto.auth.RefreshRequest;
import com.rewit.presentation.dto.auth.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Validação de segurança contra comprometimento exclusivo de credencial SQL de runtime (rewit_app).
 * <p>
 * Cobre os vetores do achado S3-A13-F1:
 * 1. Rejeição de hashes de senha crus (sem pepper/envelope pw2).
 * 2. Rejeição do oráculo de cadastro e transplante de senhas entre contas.
 * 3. Rejeição de refresh tokens arbitrários / forjados via SQL.
 * 4. Rejeição de transplante de sessões entre contas (UPDATE bloqueado por trigger; DELETE+INSERT rejeitado pela API).
 * 5. Rejeição de ressurreição e extensão de expiração de sessões via SQL.
 * 6. Rejeição de sequestro por identidade social via manipulação de auth_provider ou provider_user_id.
 * 7. Rotação segura com chave anterior e upgrade imediato para chave ativa.
 * 8. Validação fail-closed de segredos criptográficos.
 * 9. Procedimento explícito de selamento de senhas legadas (dry-run e execução).
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("S3-A13-F1: Mitigação de comprometimento de credencial SQL de runtime (rewit_app)")
class RuntimeSqlCredentialCompromiseIntegrationTest {

    private static final String DEFAULT_PASSWORD = "Password123!";

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TokenService tokenService;

    @Autowired
    private Argon2PasswordHasher passwordHasher;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    // =========================================================================
    // 1. Rejeição de hash cru
    // =========================================================================

    @Test
    @DisplayName("1. Hash Argon2id cru inserido diretamente no banco deve ser rejeitado no login (401 INVALID_CREDENTIALS)")
    void shouldRejectRawArgon2idHashInjectedViaSql() throws Exception {
        UUID userId = UUID.randomUUID();
        String email = "raw-hash-" + userId + "@rewit.test";
        String rawArgon2 = new Argon2PasswordEncoder(16, 32, 1, 19456, 2).encode(DEFAULT_PASSWORD);

        // Inserção direta simulando atacante que injetou hash cru no banco
        PrivilegedTestDatabase.jdbc().update(
                "INSERT INTO users (id, email, password_hash, auth_provider, role, account_status) "
                        + "VALUES (?, ?, ?, 'LOCAL', 'USER', 'ACTIVE')",
                userId, email, rawArgon2
        );
        PrivilegedTestDatabase.jdbc().update(
                "INSERT INTO profiles (id, user_id, handle, display_name) VALUES (?, ?, ?, 'Raw User')",
                UUID.randomUUID(), userId, "raw_" + userId.toString().substring(0, 8)
        );

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, DEFAULT_PASSWORD))))
                .andReturn();

        assertEquals(401, result.getResponse().getStatus());
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("INVALID_CREDENTIALS", json.get("code").asText());
    }

    // =========================================================================
    // 2. Rejeição de oráculo de cadastro e transplante de hash de senha
    // =========================================================================

    @Test
    @DisplayName("2. Hash de senha transplantado entre contas via SQL deve falhar no login (vínculo HMAC com userId)")
    void shouldRejectPasswordHashTransplantationBetweenAccounts() throws Exception {
        String emailA = "account-a-" + UUID.randomUUID() + "@rewit.test";
        String emailB = "account-b-" + UUID.randomUUID() + "@rewit.test";
        String passwordA = "SecretPassAccountA-123!";
        String passwordB = "SecretPassAccountB-456!";

        // Cadastra conta A e conta B legitimamente via API
        register(emailA, passwordA);
        register(emailB, passwordB);

        // Atacante com acesso SQL de runtime copia o password_hash de A para B
        int updated = jdbcTemplate.update(
                "UPDATE users SET password_hash = (SELECT password_hash FROM users WHERE email = ?) WHERE email = ?",
                emailA, emailB
        );
        assertEquals(1, updated);

        // Tentar login na conta B usando a senha de A: deve FALHAR pois o HMAC deriva userId
        MvcResult attackResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(emailB, passwordA))))
                .andReturn();

        assertEquals(401, attackResult.getResponse().getStatus());
        JsonNode json = objectMapper.readTree(attackResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("INVALID_CREDENTIALS", json.get("code").asText());

        // Conta A continua funcionando perfeitamente com sua própria senha
        MvcResult legitResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(emailA, passwordA))))
                .andReturn();
        assertEquals(200, legitResult.getResponse().getStatus());
    }

    // =========================================================================
    // 3. Rejeição de refresh token forjado (SHA-256 puro, sem HMAC)
    // =========================================================================

    @Test
    @DisplayName("3. Sessão administrativa forjada com SHA-256(token) puro, expiração coerente e UUID do admin é rejeitada (HMAC exige segredo externo)")
    void shouldRejectForgedRefreshTokenInsertedViaSql() throws Exception {
        UUID adminId = PrivilegedTestDatabase.createUser("forged-admin", "ADMIN");
        Instant expiresAt = Instant.now().plusSeconds(86400).truncatedTo(ChronoUnit.SECONDS);
        // O atacante constrói um token canônico válido (UUID do admin + expiração + 32 bytes aleatórios)
        String forgedRawToken = tokenService.generateRefreshToken(adminId, expiresAt);
        String forgedSha256 = sha256Hex(forgedRawToken);
        assertEquals(64, forgedSha256.length());
        // Garantia de que o hash plantado é o SHA-256 real e difere do HMAC da aplicação
        assertNotEquals(forgedSha256, tokenService.hashRefreshToken(forgedRawToken));

        int sessionsBefore = countSessions(adminId);
        // Todos os campos são válidos: user_id existente, expires_at idêntico ao embutido no token, hash de 64 hex
        assertEquals(1, jdbcTemplate.update(
                "INSERT INTO auth_sessions (id, user_id, token_hash, issued_at, expires_at, created_at) "
                        + "VALUES (?, ?, ?, NOW(), ?, NOW())",
                UUID.randomUUID(), adminId, forgedSha256, Timestamp.from(expiresAt)));
        assertEquals(sessionsBefore + 1, countSessions(adminId), "a linha forjada precisa existir para o teste ser significativo");

        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(forgedRawToken))))
                .andReturn();

        assertEquals(401, refreshResult.getResponse().getStatus());
        JsonNode json = objectMapper.readTree(refreshResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("INVALID_REFRESH_TOKEN", json.get("code").asText());
        assertNull(json.get("accessToken"), "nenhum access token pode ser emitido");
        assertNull(json.get("refreshToken"), "nenhum refresh token pode ser emitido");
        assertEquals(sessionsBefore + 1, countSessions(adminId), "nenhuma nova sessão administrativa pode ser criada");
    }

    // =========================================================================
    // 4. Rejeição de transplante de sessão (UPDATE e DELETE+INSERT)
    // =========================================================================

    @Test
    @DisplayName("4. Transplante de sessão: UPDATE bloqueado por trigger, DELETE negado ao runtime e linha reconstruída rejeitada pelo vínculo de UUID")
    void shouldRejectSessionTransplantationViaUpdateAndViaDeleteInsert() throws Exception {
        String victimEmail = "victim-" + UUID.randomUUID() + "@rewit.test";
        JsonNode victimRegister = register(victimEmail, DEFAULT_PASSWORD);
        String victimRefreshToken = victimRegister.get("refreshToken").asText();
        String victimTokenHash = tokenService.hashRefreshToken(victimRefreshToken);
        Instant victimExpiry = tokenService.parseRefreshToken(victimRefreshToken).expiresAt();

        UUID adminId = PrivilegedTestDatabase.createUser("admin-target", "ADMIN");

        // 4a. UPDATE direto de user_id pelo runtime: bloqueado pela trigger
        DataAccessException ex = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("UPDATE auth_sessions SET user_id = ? WHERE token_hash = ?", adminId, victimTokenHash));
        assertTrue(containsError(ex, "auth_sessions.user_id"));

        // 4b. DELETE direto: o runtime não possui mais o privilégio (permission denied, não FK/constraint)
        DataAccessException exDelete = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("DELETE FROM auth_sessions WHERE token_hash = ?", victimTokenHash));
        assertTrue(containsError(exDelete, "permission denied"), "esperado permission denied em DELETE: " + exDelete.getMessage());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_sessions WHERE token_hash = ?", Integer.class, victimTokenHash));

        // 4c. Reconstrução com o mesmo hash sob outro dono, feita por conexão privilegiada apenas para provar a defesa
        //     de segunda camada (vínculo UUID do token); o runtime nem consegue chegar a esse estado.
        PrivilegedTestDatabase.jdbc().update("DELETE FROM auth_sessions WHERE token_hash = ?", victimTokenHash);
        PrivilegedTestDatabase.jdbc().update(
                "INSERT INTO auth_sessions (id, user_id, token_hash, issued_at, expires_at, created_at) "
                        + "VALUES (?, ?, ?, NOW(), ?, NOW())",
                UUID.randomUUID(), adminId, victimTokenHash, Timestamp.from(victimExpiry));

        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(victimRefreshToken))))
                .andReturn();
        assertEquals(401, refreshResult.getResponse().getStatus());
        JsonNode json = objectMapper.readTree(refreshResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("INVALID_REFRESH_TOKEN", json.get("code").asText());
    }

    // =========================================================================
    // 5. Ressurreição e extensão de expiração (F1-AUD-02)
    // =========================================================================

    private record RevokedSession(UUID userId, String accessToken, String refreshToken, String hash, Instant expiresAt) {
    }

    private RevokedSession registerAndRevokeByLogout() throws Exception {
        String email = "resurrect-" + UUID.randomUUID() + "@rewit.test";
        JsonNode reg = register(email, DEFAULT_PASSWORD);
        String accessToken = reg.get("accessToken").asText();
        String refreshToken = reg.get("refreshToken").asText();
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + accessToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LogoutRequest(refreshToken))))
                .andExpect(result -> assertEquals(204, result.getResponse().getStatus()));
        UUID userId = tokenService.parseRefreshToken(refreshToken).userId();
        return new RevokedSession(userId, accessToken, refreshToken, tokenService.hashRefreshToken(refreshToken),
                tokenService.parseRefreshToken(refreshToken).expiresAt());
    }

    /** Executa o ataque completo com a conexão de runtime e exige que o token antigo continue inútil. */
    private void assertCannotResurrect(RevokedSession s) throws Exception {
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT revoked_at FROM auth_sessions WHERE token_hash = ?", Timestamp.class, s.hash()), "pré-condição: sessão revogada");

        // 1. Excluir a linha: privilégio inexistente para rewit_app
        DataAccessException del = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("DELETE FROM auth_sessions WHERE token_hash = ?", s.hash()));
        assertTrue(containsError(del, "permission denied"), del.getMessage());
        // 2. A função SECURITY DEFINER de purge não toca sessão revogada ainda dentro do prazo, mesmo com data futura
        Integer purged = jdbcTemplate.queryForObject("SELECT public.rewit_purge_expired_auth_sessions(?, 100)",
                Integer.class, Timestamp.from(Instant.now().plusSeconds(86400L * 3650)));
        assertNotNull(purged);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_sessions WHERE token_hash = ?", Integer.class, s.hash()),
                "o corte é limitado ao relógio real: sessão ainda válida não pode ser apagada");
        // 3. A função de purge por usuário só atua sobre conta DELETED
        DataAccessException purgeUser = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.queryForObject("SELECT public.rewit_purge_deleted_user_auth_sessions(?)", Integer.class, s.userId()));
        assertTrue(containsError(purgeUser, "contas definitivamente DELETED"), purgeUser.getMessage());
        // 4. Alterar expiração / desrevogar
        assertTrue(containsError(assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE auth_sessions SET expires_at = NOW() + INTERVAL '365 days' WHERE token_hash = ?", s.hash())),
                "auth_sessions.expires_at"));
        assertTrue(containsError(assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE auth_sessions SET revoked_at = NULL WHERE token_hash = ?", s.hash())),
                "auth_sessions.revoked_at"));
        // 5. Refresh com token antigo continua rejeitado (nunca 200)
        MvcResult res = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(s.refreshToken()))))
                .andReturn();
        assertEquals(401, res.getResponse().getStatus());
        assertNull(objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8)).get("accessToken"));
    }

    @Test
    @DisplayName("5a. Sessão revogada por logout não pode ser apagada, reinserida nem ter expiração alterada")
    void shouldBlockResurrectionAfterLogout() throws Exception {
        assertCannotResurrect(registerAndRevokeByLogout());
    }

    @Test
    @DisplayName("5b. Sessão revogada por troca de senha não pode ser ressuscitada")
    void shouldBlockResurrectionAfterPasswordChange() throws Exception {
        String email = "pwchange-" + UUID.randomUUID() + "@rewit.test";
        JsonNode reg = register(email, DEFAULT_PASSWORD);
        String access = reg.get("accessToken").asText();
        String refresh = reg.get("refreshToken").asText();
        MvcResult change = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/v1/me/password")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":\"" + DEFAULT_PASSWORD + "\",\"newPassword\":\"NovaSenha456!x\"}"))
                .andReturn();
        assertTrue(change.getResponse().getStatus() < 300, "troca de senha deve ter sucesso: " + change.getResponse().getStatus());
        var meta = tokenService.parseRefreshToken(refresh);
        assertCannotResurrect(new RevokedSession(meta.userId(), access, refresh, tokenService.hashRefreshToken(refresh), meta.expiresAt()));
    }

    @Test
    @DisplayName("5c. Sessão revogada por rotação (token antigo) não pode ser ressuscitada")
    void shouldBlockResurrectionAfterRotation() throws Exception {
        String email = "rotate-" + UUID.randomUUID() + "@rewit.test";
        JsonNode reg = register(email, DEFAULT_PASSWORD);
        String oldRefresh = reg.get("refreshToken").asText();
        MvcResult rotated = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(oldRefresh))))
                .andReturn();
        assertEquals(200, rotated.getResponse().getStatus());
        var meta = tokenService.parseRefreshToken(oldRefresh);
        assertCannotResurrect(new RevokedSession(meta.userId(), reg.get("accessToken").asText(), oldRefresh,
                tokenService.hashRefreshToken(oldRefresh), meta.expiresAt()));
    }

    @Test
    @DisplayName("5d. Após purge legítimo de sessão expirada, linha reconstruída com hash conhecido e expiração adulterada é rejeitada")
    void shouldRejectRebuiltRowWithTamperedExpiryAfterLegitimatePurge() throws Exception {
        String email = "expired-" + UUID.randomUUID() + "@rewit.test";
        JsonNode reg = register(email, DEFAULT_PASSWORD);
        UUID userId = tokenService.parseRefreshToken(reg.get("refreshToken").asText()).userId();

        // Sessão legitimamente expirada no passado (preparada com conexão privilegiada), revogada ou não
        Instant pastExpiry = Instant.now().minusSeconds(86400).truncatedTo(ChronoUnit.SECONDS);
        String expiredRaw = tokenService.generateRefreshToken(userId, pastExpiry);
        String expiredHash = tokenService.hashRefreshToken(expiredRaw);
        PrivilegedTestDatabase.jdbc().update(
                "INSERT INTO auth_sessions (id, user_id, token_hash, issued_at, expires_at, created_at) VALUES (?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(), userId, expiredHash, Timestamp.from(pastExpiry.minusSeconds(3600)), Timestamp.from(pastExpiry),
                Timestamp.from(pastExpiry.minusSeconds(3600)));

        // Purge legítimo via função restrita, com a conexão de runtime
        Integer purged = jdbcTemplate.queryForObject("SELECT public.rewit_purge_expired_auth_sessions(?, 1000)",
                Integer.class, Timestamp.from(Instant.now()));
        assertNotNull(purged);
        assertEquals(0, countByHash(expiredHash), "a sessão expirada deve ter sido removida pelo purge legítimo");

        // O atacante (runtime) reinsere a linha com hash conhecido (ele conhece o hash porque o leu antes) e expiração futura
        Instant tamperedExpiry = Instant.now().plusSeconds(86400L * 365).truncatedTo(ChronoUnit.SECONDS);
        assertEquals(1, jdbcTemplate.update(
                "INSERT INTO auth_sessions (id, user_id, token_hash, issued_at, expires_at, created_at) VALUES (?, ?, ?, NOW(), ?, NOW())",
                UUID.randomUUID(), userId, expiredHash, Timestamp.from(tamperedExpiry)));
        assertEquals(1, countByHash(expiredHash), "a linha adulterada existe: o teste alcança a verificação de autenticação");

        MvcResult res = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(expiredRaw))))
                .andReturn();
        // A expiração embutida no token (passado) diverge da persistida (futuro) e o token em si já está expirado
        assertEquals(401, res.getResponse().getStatus());
        JsonNode json = objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("INVALID_REFRESH_TOKEN", json.get("code").asText());
        assertNull(json.get("accessToken"));
    }

    @Test
    @DisplayName("5e. Triggers continuam bloqueando alteração de revoked_at, expires_at e token_hash")
    void shouldBlockSessionResurrectionAndExpiryExtension() throws Exception {
        RevokedSession s = registerAndRevokeByLogout();
        assertTrue(containsError(assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE auth_sessions SET token_hash = 'tampered' WHERE token_hash = ?", s.hash())), "auth_sessions.token_hash"));
        assertTrue(containsError(assertThrows(DataAccessException.class, () -> jdbcTemplate.update(
                "UPDATE auth_sessions SET expires_at = NOW() + INTERVAL '100 days' WHERE token_hash = ?", s.hash())), "auth_sessions.expires_at"));
    }

    // =========================================================================
    // 6. Rejeição de sequestro por identidade social via SQL
    // =========================================================================

    @Test
    @DisplayName("6. Trigger bloqueia alteração de auth_provider e modificação de provider_user_id via runtime SQL")
    void shouldBlockSocialIdentityHijackingViaSql() throws Exception {
        String email = "social-hijack-" + UUID.randomUUID() + "@rewit.test";
        register(email, DEFAULT_PASSWORD);

        // 6a. Alterar auth_provider de LOCAL para GOOGLE via SQL
        DataAccessException exProvider = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("UPDATE users SET auth_provider = 'GOOGLE' WHERE email = ?", email));
        assertTrue(containsError(exProvider, "users.auth_provider"));

        // 6b. Definir provider_user_id em conta LOCAL
        DataAccessException exProviderId = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("UPDATE users SET provider_user_id = 'evil-sub-123' WHERE email = ?", email));
        assertTrue(containsError(exProviderId, "users.provider_user_id"));

        // 6c. Alterar provider_user_id em conta federada já existente
        UUID federatedUser = UUID.randomUUID();
        String subOriginal = "legit-sub-" + UUID.randomUUID();
        PrivilegedTestDatabase.jdbc().update(
                "INSERT INTO users (id, email, auth_provider, provider_user_id, role, account_status) "
                        + "VALUES (?, ?, 'GOOGLE', ?, 'USER', 'ACTIVE')",
                federatedUser, "google-" + federatedUser + "@gmail.com", subOriginal
        );

        DataAccessException exTamperFederated = assertThrows(DataAccessException.class, () ->
                jdbcTemplate.update("UPDATE users SET provider_user_id = 'attacker-sub-456' WHERE id = ?", federatedUser));
        assertTrue(containsError(exTamperFederated, "users.provider_user_id"));
    }

    // =========================================================================
    // 7. Rotação com chave anterior e upgrade imediato
    // =========================================================================

    @Test
    @DisplayName("7. Autenticação com chave anterior é aceita e atualiza hash/sessão imediatamente para a chave ativa")
    void shouldAuthenticateWithPreviousKeyAndUpgradeImmediately() throws Exception {
        UUID userId = UUID.randomUUID();
        String email = "rotation-" + userId + "@rewit.test";

        // 7a. Simula senha criada durante vigência da chave anterior (key-2026-a)
        // Usamos uma instância temporária do hasher que tem como chave ativa o segredo da chave anterior
        Argon2PasswordHasher previousKeyHasher = new Argon2PasswordHasher(
                new Argon2PasswordEncoder(16, 32, 1, 19456, 2),
                "test_previous_pepper_secret_with_sufficient_entropy_at_least_32_bytes!",
                "key-2026-a",
                null,
                null
        );
        String oldHash = previousKeyHasher.hash(userId, DEFAULT_PASSWORD);
        assertTrue(oldHash.startsWith("pw2$v1$key-2026-a$"));

        PrivilegedTestDatabase.jdbc().update(
                "INSERT INTO users (id, email, password_hash, auth_provider, role, account_status) "
                        + "VALUES (?, ?, ?, 'LOCAL', 'USER', 'ACTIVE')",
                userId, email, oldHash
        );
        PrivilegedTestDatabase.jdbc().update(
                "INSERT INTO profiles (id, user_id, handle, display_name) VALUES (?, ?, ?, 'Rotation User')",
                UUID.randomUUID(), userId, "rot_" + userId.toString().substring(0, 8)
        );

        // Login deve ter sucesso usando a chave anterior configurada na aplicação
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, DEFAULT_PASSWORD))))
                .andReturn();
        assertEquals(200, loginResult.getResponse().getStatus());

        // Confere que a aplicação fez rehash imediato para a chave ativa (key-2026-b)
        String upgradedHash = jdbcTemplate.queryForObject(
                "SELECT password_hash FROM users WHERE id = ?", String.class, userId);
        assertNotNull(upgradedHash);
        assertTrue(upgradedHash.startsWith("pw2$v1$key-2026-b$"), "O hash deve ter sido re-hasheado com a chave ativa key-2026-b");

        // 7b. Rotação de refresh token com chave anterior
        Instant previousExpiry = Instant.now().plusSeconds(86400).truncatedTo(ChronoUnit.SECONDS);
        String previousKeyRawToken = tokenService.generateRefreshToken(userId, previousExpiry);
        String previousKeyHash = tokenService.hashRefreshTokenWithPreviousKey(previousKeyRawToken).orElseThrow();

        PrivilegedTestDatabase.jdbc().update(
                "INSERT INTO auth_sessions (id, user_id, token_hash, issued_at, expires_at, created_at) "
                        + "VALUES (?, ?, ?, NOW(), ?, NOW())",
                UUID.randomUUID(), userId, previousKeyHash, Timestamp.from(previousExpiry)
        );

        // Refresh deve aceitar o token com hash da chave anterior
        MvcResult refreshResult = mockMvc.perform(post("/api/v1/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RefreshRequest(previousKeyRawToken))))
                .andReturn();
        assertEquals(200, refreshResult.getResponse().getStatus());

        JsonNode refreshJson = objectMapper.readTree(refreshResult.getResponse().getContentAsString(StandardCharsets.UTF_8));
        String newRefreshToken = refreshJson.get("refreshToken").asText();
        String activeNewHash = tokenService.hashRefreshToken(newRefreshToken);

        // Confere que a nova sessão salva no banco utiliza o hash da chave ATIVA
        Integer activeMatches = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM auth_sessions WHERE token_hash = ? AND revoked_at IS NULL",
                Integer.class, activeNewHash);
        assertEquals(1, activeMatches);
    }

    // =========================================================================
    // 8. Fail-closed: validação de segredos
    // =========================================================================

    @Test
    @DisplayName("8. Validador de segredos fail-closed: rejeita segredos curtos, vazios, placeholders e reúso entre funções")
    void shouldEnforceFailClosedSecretValidation() {
        // Segredos curtos (< 32 bytes)
        assertThrows(IllegalArgumentException.class, () ->
                SecuritySecretValidator.decodeSecret("TEST_SECRET", "short_secret_12345"));

        // Segredos nulos ou em branco
        assertThrows(IllegalArgumentException.class, () ->
                SecuritySecretValidator.decodeSecret("TEST_SECRET", ""));
        assertThrows(IllegalArgumentException.class, () ->
                SecuritySecretValidator.decodeSecret("TEST_SECRET", "   "));
        assertThrows(IllegalArgumentException.class, () ->
                SecuritySecretValidator.decodeSecret("TEST_SECRET", null));

        // Placeholders comuns
        assertThrows(IllegalArgumentException.class, () ->
                SecuritySecretValidator.decodeSecret("TEST_SECRET", "CHANGE_ME_CHANGE_ME_CHANGE_ME_CHANGE_ME_12345"));
        assertThrows(IllegalArgumentException.class, () ->
                SecuritySecretValidator.decodeSecret("TEST_SECRET", "your-secret-key-goes-here-and-is-long-enough"));

        // Bytes repetitivos de baixa entropia
        assertThrows(IllegalArgumentException.class, () ->
                SecuritySecretValidator.decodeSecret("TEST_SECRET", "0000000000000000000000000000000000000000"));

        // Reúso entre funções criptográficas distintas
        byte[] secretA = SecuritySecretValidator.decodeSecret("SECRET_A", "valid_secret_a_with_sufficient_entropy_length_32_bytes!");
        byte[] secretB = SecuritySecretValidator.decodeSecret("SECRET_B", "valid_secret_b_with_sufficient_entropy_length_32_bytes!");
        byte[] reusedSecret = SecuritySecretValidator.decodeSecret("REUSED", "valid_secret_a_with_sufficient_entropy_length_32_bytes!");

        assertDoesNotThrow(() ->
                SecuritySecretValidator.validateDistinctSecrets("SECRET_A", secretA, "SECRET_B", secretB));
        assertThrows(IllegalArgumentException.class, () ->
                SecuritySecretValidator.validateDistinctSecrets("SECRET_A", secretA, "SECRET_B", reusedSecret));
    }

    // =========================================================================
    // 9. Formatos legados rejeitados (sem selamento)
    // =========================================================================

    @Test
    @DisplayName("9. Formato legacy-sealed e qualquer hash não pw2 são rejeitados no login e nunca convertidos")
    void shouldRejectLegacySealedAndNonPw2Formats() throws Exception {
        UUID userId = UUID.randomUUID();
        String email = "legacy-" + userId + "@rewit.test";
        String rawArgon2 = new Argon2PasswordEncoder(16, 32, 1, 19456, 2).encode(DEFAULT_PASSWORD);
        String fakeSealed = "legacy-sealed$v1$" + "0".repeat(64) + "$" + rawArgon2.substring(1);
        PrivilegedTestDatabase.jdbc().update(
                "INSERT INTO users (id, email, password_hash, auth_provider, role, account_status) VALUES (?, ?, ?, 'LOCAL', 'USER', 'ACTIVE')",
                userId, email, fakeSealed);
        PrivilegedTestDatabase.jdbc().update(
                "INSERT INTO profiles (id, user_id, handle, display_name) VALUES (?, ?, ?, 'Legacy User')",
                UUID.randomUUID(), userId, "leg_" + userId.toString().substring(0, 8));

        MvcResult res = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, DEFAULT_PASSWORD))))
                .andReturn();
        assertEquals(401, res.getResponse().getStatus());
        assertEquals(fakeSealed, jdbcTemplate.queryForObject("SELECT password_hash FROM users WHERE id = ?", String.class, userId),
                "o hash não pode ser reescrito/convertido pela aplicação");
        assertFalse(passwordHasher.matches(userId, DEFAULT_PASSWORD, fakeSealed));
        assertFalse(passwordHasher.matches(userId, DEFAULT_PASSWORD, rawArgon2));
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private JsonNode register(String email, String password) throws Exception {
        String handle = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        RegisterRequest req = new RegisterRequest(email, password, handle, "Test User");
        MvcResult res = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andReturn();
        assertEquals(201, res.getResponse().getStatus());
        return objectMapper.readTree(res.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private int countSessions(UUID userId) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_sessions WHERE user_id = ?", Integer.class, userId);
    }

    private int countByHash(String hash) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM auth_sessions WHERE token_hash = ?", Integer.class, hash);
    }

    private static String sha256Hex(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private boolean containsError(Throwable t, String fragment) {
        while (t != null) {
            if (t.getMessage() != null && t.getMessage().contains(fragment)) {
                return true;
            }
            t = t.getCause();
        }
        return false;
    }
}
