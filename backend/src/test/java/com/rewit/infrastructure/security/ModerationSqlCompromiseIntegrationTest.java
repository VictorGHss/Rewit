package com.rewit.infrastructure.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.dto.ReviewDto.CreateReviewCommand;
import com.rewit.application.dto.ReviewDto.CreateReviewTargetCommand;
import com.rewit.application.dto.business.BusinessResponseDtos.ModerateBusinessResponseCommand;
import com.rewit.application.dto.discussion.AdminDiscussionModerationDtos.ModerateDiscussionCommand;
import com.rewit.application.dto.discussion.DiscussionDtos.CreateDiscussionCommand;
import com.rewit.application.dto.report.ReportDtos.CreateReportCommand;
import com.rewit.application.dto.report.ReportDtos.ModerateReviewCommand;
import com.rewit.application.dto.storage.StorageQuarantineDtos.StoragePurgeOutcome;
import com.rewit.application.dto.storage.StorageQuarantineDtos.StoragePurgeResult;
import com.rewit.application.dto.storage.StorageReconciliationDtos.OrphanCandidate;
import com.rewit.application.port.ObjectStoragePort;
import com.rewit.application.port.PlaceMediaRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.StorageQuarantineRepository;
import com.rewit.application.service.DiscussionService;
import com.rewit.application.service.PlaceMediaService;
import com.rewit.application.service.ReportService;
import com.rewit.application.service.ReviewService;
import com.rewit.application.usecase.AdminAccountLifecycleUseCase;
import com.rewit.application.usecase.DeactivateAccountUseCase;
import com.rewit.application.usecase.DecidePlaceClaimUseCase;
import com.rewit.application.usecase.DeleteReviewUseCase;
import com.rewit.application.usecase.ModerateBusinessResponseUseCase;
import com.rewit.application.usecase.ModerateDiscussionUseCase;
import com.rewit.application.usecase.ModerateReviewUseCase;
import com.rewit.application.usecase.PurgeConfirmedOrphanStorageObjectUseCase;
import com.rewit.application.usecase.RemoveBusinessResponseUseCase;
import com.rewit.config.PrivilegedTestDatabase;
import com.rewit.config.TestInfrastructureConfig;
import com.rewit.domain.enums.BusinessResponseModerationAction;
import com.rewit.domain.enums.DiscussionModerationAction;
import com.rewit.domain.enums.ModerationAction;
import com.rewit.domain.enums.PlaceClaimDecision;
import com.rewit.domain.enums.RemovalSource;
import com.rewit.domain.enums.ReportReason;
import com.rewit.domain.model.Place;
import com.rewit.presentation.dto.auth.LoginRequest;
import com.rewit.presentation.dto.auth.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Bateria adversarial completa (S3-A13-F2 §9): comprova sob login PostgreSQL REAL ({@code rewit_app} comprometida,
 * {@code rewit_mod} restrita e {@code rewit_owner} apenas para fixtures) todos os 25 cenários de ataque, integridade
 * transacional bidirecional, autenticidade criptográfica HMAC-SHA256 e regressão funcional pública e administrativa.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("S3-A13-F2: hardening completo contra comprometimento SQL da credencial rewit_app (25 cenários)")
class ModerationSqlCompromiseIntegrationTest {

    private static final TestInfrastructureConfig INFRA = TestInfrastructureConfig.current();
    private static final String BOGUS_WELL_FORMED_MAC = "aud1$v1$" + "f".repeat(64);
    private static final String MALFORMED_MAC = "not-a-valid-mac";

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private ModerationAuditMacService macService;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private PlaceMediaRepository placeMediaRepository;
    @Autowired private ObjectStoragePort objectStoragePort;
    @Autowired private ReviewService reviewService;
    @Autowired private ReportService reportService;
    @Autowired private DiscussionService discussionService;
    @Autowired private PlaceMediaService placeMediaService;
    @Autowired private ModerateReviewUseCase moderateReviewUseCase;
    @Autowired private ModerateDiscussionUseCase moderateDiscussionUseCase;
    @Autowired private ModerateBusinessResponseUseCase moderateBusinessResponseUseCase;
    @Autowired private RemoveBusinessResponseUseCase removeBusinessResponseUseCase;
    @Autowired private DecidePlaceClaimUseCase decidePlaceClaimUseCase;
    @Autowired private AdminAccountLifecycleUseCase adminAccountLifecycleUseCase;
    @Autowired private DeactivateAccountUseCase deactivateAccountUseCase;
    @Autowired private DeleteReviewUseCase deleteReviewUseCase;
    @Autowired private StorageQuarantineRepository quarantineRepository;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).apply(springSecurity()).build();
    }

    // --------------------------------------------------------------------------------------- Helpers de fixture

    private static JdbcTemplate owner() {
        return PrivilegedTestDatabase.jdbc();
    }

    private static JdbcTemplate app() {
        return PrivilegedTestDatabase.appJdbc();
    }

    private static JdbcTemplate media() {
        return PrivilegedTestDatabase.mediaJdbc();
    }

    private static JdbcTemplate mod() {
        return PrivilegedTestDatabase.modJdbc();
    }

    private static Connection appConn() throws SQLException {
        return DriverManager.getConnection(INFRA.datasourceUrl(), INFRA.appDbUser(), INFRA.appDbPassword());
    }

    private static Connection mediaConn() throws SQLException {
        return DriverManager.getConnection(INFRA.datasourceUrl(), INFRA.mediaDbUser(), INFRA.mediaDbPassword());
    }

    private static Connection modConn() throws SQLException {
        return DriverManager.getConnection(INFRA.datasourceUrl(), INFRA.modDbUser(), INFRA.modDbPassword());
    }

    private static UUID seedPlace(String name) {
        UUID id = UUID.randomUUID();
        owner().update("INSERT INTO rateable_targets (id, target_type) VALUES (?, 'PLACE')", id);
        owner().update("INSERT INTO places (id, name, slug, category, address_text, city, state, coordinates, status) "
                        + "VALUES (?, ?, ?, 'CAFE', ?, 'Curitiba', 'PR', ST_SetSRID(ST_MakePoint(-49.27, -25.42), 4326), 'ACTIVE')",
                id, name + " " + id, "f2-" + id, "Rua F2, " + id);
        return id;
    }

    private static UUID seedBusiness(UUID userId, String status) {
        UUID id = UUID.randomUUID();
        String taxId = UUID.randomUUID().toString().replace("-", "").substring(0, 14);
        owner().update("INSERT INTO business_accounts (id, user_id, corporate_name, tax_id, verification_status) VALUES (?, ?, ?, ?, ?)",
                id, userId, "Empresa F2 " + taxId.substring(0, 6), taxId, status);
        return id;
    }

    private static UUID seedReview(UUID authorId, UUID placeId, String status) {
        UUID id = UUID.randomUUID();
        owner().update("INSERT INTO reviews (id, user_id, experience_text, status) VALUES (?, ?, 'Avaliação F2', ?)",
                id, authorId, status);
        owner().update("INSERT INTO review_targets (review_id, target_id, rating) VALUES (?, ?, 4)", id, placeId);
        return id;
    }

    private static UUID seedDiscussion(UUID reviewId, UUID authorId, String status) {
        UUID id = UUID.randomUUID();
        owner().update("INSERT INTO review_discussions (id, review_id, user_id, content, status) VALUES (?, ?, ?, 'Comentário F2', ?)",
                id, reviewId, authorId, status);
        return id;
    }

    private static UUID seedBusinessResponse(UUID reviewId, UUID businessId, UUID placeId, UUID authorId) {
        UUID id = UUID.randomUUID();
        owner().update("INSERT INTO business_responses (id, review_id, business_account_id, place_id, author_user_id, content) "
                        + "VALUES (?, ?, ?, ?, ?, 'Resposta oficial F2')",
                id, reviewId, businessId, placeId, authorId);
        return id;
    }

    private static UUID seedPlaceMedia(UUID placeId, UUID uploaderId, String status, String removalSource, Instant removedAt) {
        UUID id = UUID.randomUUID();
        owner().update("INSERT INTO place_media (id, place_id, user_id, object_key, media_type, mime_type, size_bytes, width, height, status, removal_source, removed_at) "
                        + "VALUES (?, ?, ?, ?, 'IMAGE', 'image/jpeg', 2048, 800, 600, ?, ?, ?)",
                id, placeId, uploaderId, "places/f2-" + id + ".jpg", status, removalSource,
                removedAt != null ? Timestamp.from(removedAt) : null);
        return id;
    }

    private static UUID seedReviewMedia(UUID reviewId, UUID authorId, String status) {
        UUID id = UUID.randomUUID();
        owner().update("INSERT INTO review_media (id, review_id, user_id, object_key, media_type, mime_type, size_bytes, width, height, status) "
                        + "VALUES (?, ?, ?, ?, 'IMAGE', 'image/jpeg', 1024, 640, 480, ?)",
                id, reviewId, authorId, "reviews/f2-" + id + ".jpg", status);
        return id;
    }

    private static UUID seedPlaceClaim(UUID placeId, UUID businessId) {
        UUID id = UUID.randomUUID();
        owner().update("INSERT INTO place_claim_requests (id, place_id, business_account_id, evidence_description, status) "
                        + "VALUES (?, ?, ?, 'Comprovante CNPJ e fachada oficial do estabelecimento.', 'PENDING')",
                id, placeId, businessId);
        return id;
    }

    private static UUID seedPlaceMediaReport(UUID mediaId, UUID reporterId) {
        UUID id = UUID.randomUUID();
        owner().update("INSERT INTO place_media_reports (id, place_media_id, reporter_user_id, reason, detail, status) "
                        + "VALUES (?, ?, ?, 'OFFENSIVE', 'Detalhe F2', 'PENDING')",
                id, mediaId, reporterId);
        return id;
    }

    // --------------------------------------------------------------------------------------- Cenários 1 a 5

    @Test
    @DisplayName("Cenário 1: rewit_app não consegue fabricar registros de auditoria com UUID real de ADMIN em nenhuma das 6 tabelas")
    void scenario01_appCannotForgeAuditLogsInAnyAuditTable() {
        UUID admin = PrivilegedTestDatabase.createUser("s01_admin", "ADMIN");
        UUID author = PrivilegedTestDatabase.createUser("s01_author", "USER");
        UUID bizUser = PrivilegedTestDatabase.createUser("s01_biz", "USER");
        UUID place = seedPlace("Local S01");
        UUID biz = seedBusiness(bizUser, "APPROVED");
        PrivilegedTestDatabase.setPlaceClaimedBy(place, biz);
        UUID review = seedReview(author, place, "ACTIVE");
        UUID discussion = seedDiscussion(review, author, "ACTIVE");
        UUID response = seedBusinessResponse(review, biz, place, bizUser);
        UUID media = seedPlaceMedia(place, author, "ACTIVE", null, null);
        UUID claim = seedPlaceClaim(place, biz);

        assertThrows(DataAccessException.class, () -> app().update(
                "INSERT INTO moderation_audit_logs (review_id, moderator_user_id, action, decision, reason_code, justification, previous_review_status, new_review_status, authenticity_mac) "
                        + "VALUES (?, ?, 'REMOVE_REVIEW', 'ACCEPTED', 'SPAM', 'Forjado', 'ACTIVE', 'REMOVED', ?)",
                review, admin, BOGUS_WELL_FORMED_MAC));

        assertThrows(DataAccessException.class, () -> app().update(
                "INSERT INTO discussion_moderation_audit_logs (discussion_id, moderator_user_id, action, decision, reason_code, justification, previous_status, new_status, authenticity_mac) "
                        + "VALUES (?, ?, 'REMOVE_DISCUSSION', 'ACCEPTED', 'SPAM', 'Forjado', 'UNDER_REVIEW', 'REMOVED', ?)",
                discussion, admin, BOGUS_WELL_FORMED_MAC));

        assertThrows(DataAccessException.class, () -> app().update(
                "INSERT INTO business_response_moderation_audit_logs (response_id, moderator_user_id, action, reason_code, justification, previous_status, new_status, authenticity_mac) "
                        + "VALUES (?, ?, 'REMOVE_RESPONSE', 'SPAM', 'Forjado', 'ACTIVE', 'REMOVED', ?)",
                response, admin, BOGUS_WELL_FORMED_MAC));

        assertThrows(DataAccessException.class, () -> app().update(
                "INSERT INTO place_media_moderation_audit_logs (place_media_id, place_id, actor_user_id, actor_type, action, reason, notes, authenticity_mac) "
                        + "VALUES (?, ?, ?, 'USER', 'MODERATOR_REMOVE', 'SPAM', 'Forjado', ?)",
                media, place, admin, BOGUS_WELL_FORMED_MAC));

        assertThrows(DataAccessException.class, () -> app().update(
                "INSERT INTO account_moderation_audit_logs (target_user_id, actor_user_id, action, previous_status, new_status, reason, authenticity_mac) "
                        + "VALUES (?, ?, 'SUSPEND', 'ACTIVE', 'SUSPENDED', 'Forjado', ?)",
                author, admin, BOGUS_WELL_FORMED_MAC));

        assertThrows(DataAccessException.class, () -> app().update(
                "INSERT INTO place_claim_moderation_audit_logs (claim_id, place_id, business_account_id, moderator_user_id, action, previous_status, new_status, decision_reason, authenticity_mac) "
                        + "VALUES (?, ?, ?, ?, 'APPROVE_CLAIM', 'PENDING', 'APPROVED', 'Forjado', ?)",
                claim, place, biz, admin, BOGUS_WELL_FORMED_MAC));

        assertEquals(0, owner().queryForObject("SELECT COUNT(*) FROM moderation_audit_logs WHERE review_id = ?", Integer.class, review));
        assertEquals(0, owner().queryForObject("SELECT COUNT(*) FROM discussion_moderation_audit_logs WHERE discussion_id = ?", Integer.class, discussion));
        assertEquals(0, owner().queryForObject("SELECT COUNT(*) FROM business_response_moderation_audit_logs WHERE response_id = ?", Integer.class, response));
        assertEquals(0, owner().queryForObject("SELECT COUNT(*) FROM place_media_moderation_audit_logs WHERE place_media_id = ?", Integer.class, media));
        assertEquals(0, owner().queryForObject("SELECT COUNT(*) FROM account_moderation_audit_logs WHERE target_user_id = ?", Integer.class, author));
        assertEquals(0, owner().queryForObject("SELECT COUNT(*) FROM place_claim_moderation_audit_logs WHERE claim_id = ?", Integer.class, claim));
    }

    @Test
    @DisplayName("Cenários 2, 3, 4 e 5: rewit_app não pode decidir/alterar nem inserir já decididas denúncias e reivindicações")
    void scenarios02to05_appCannotDirectlyDecideReportsOrClaims() {
        UUID admin = PrivilegedTestDatabase.createUser("s02_admin", "ADMIN");
        UUID author = PrivilegedTestDatabase.createUser("s02_author", "USER");
        UUID reporter = PrivilegedTestDatabase.createUser("s02_reporter", "USER");
        UUID bizUser = PrivilegedTestDatabase.createUser("s02_biz", "USER");
        UUID place = seedPlace("Local S02");
        UUID biz = seedBusiness(bizUser, "PENDING");
        UUID review = seedReview(author, place, "ACTIVE");
        UUID discussion = seedDiscussion(review, author, "ACTIVE");
        UUID media = seedPlaceMedia(place, author, "ACTIVE", null, null);

        UUID mediaReport = seedPlaceMediaReport(media, reporter);
        UUID reviewReport = UUID.randomUUID();
        owner().update("INSERT INTO review_reports (id, review_id, reporter_user_id, reason, status) VALUES (?, ?, ?, 'SPAM', 'PENDING')",
                reviewReport, review, reporter);
        UUID discussionReport = UUID.randomUUID();
        owner().update("INSERT INTO discussion_reports (id, discussion_id, reporter_user_id, reason, status) VALUES (?, ?, ?, 'SPAM', 'PENDING')",
                discussionReport, discussion, reporter);
        UUID claim = seedPlaceClaim(place, biz);

        // Cenário 2: place_media_reports UPDATE e INSERT já decidido
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE place_media_reports SET status = 'ACCEPTED', decided_by_user_id = ?, decided_at = NOW(), decision_notes = 'Forjado', authenticity_mac = ? WHERE id = ?",
                admin, BOGUS_WELL_FORMED_MAC, mediaReport));
        assertThrows(DataAccessException.class, () -> app().update(
                "INSERT INTO place_media_reports (place_media_id, reporter_user_id, reason, status, decided_by_user_id, decided_at) VALUES (?, ?, 'SPAM', 'ACCEPTED', ?, NOW())",
                media, admin, admin));
        assertEquals("PENDING", owner().queryForObject("SELECT status FROM place_media_reports WHERE id = ?", String.class, mediaReport));

        // Cenário 3: review_reports UPDATE e INSERT não-PENDING
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE review_reports SET status = 'ACCEPTED' WHERE id = ?", reviewReport));
        assertThrows(DataAccessException.class, () -> app().update(
                "INSERT INTO review_reports (review_id, reporter_user_id, reason, status) VALUES (?, ?, 'SPAM', 'ACCEPTED')",
                review, admin));
        assertEquals("PENDING", owner().queryForObject("SELECT status FROM review_reports WHERE id = ?", String.class, reviewReport));

        // Cenário 4: discussion_reports UPDATE e INSERT não-PENDING
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE discussion_reports SET status = 'REJECTED' WHERE id = ?", discussionReport));
        assertThrows(DataAccessException.class, () -> app().update(
                "INSERT INTO discussion_reports (discussion_id, reporter_user_id, reason, status) VALUES (?, ?, 'SPAM', 'REJECTED')",
                discussion, admin));
        assertEquals("PENDING", owner().queryForObject("SELECT status FROM discussion_reports WHERE id = ?", String.class, discussionReport));

        // Cenário 5: place_claim_requests UPDATE e INSERT já decidido
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE place_claim_requests SET status = 'APPROVED', decided_by_user_id = ?, decided_at = NOW(), decision_reason = 'Forjado', authenticity_mac = ? WHERE id = ?",
                admin, BOGUS_WELL_FORMED_MAC, claim));
        assertThrows(DataAccessException.class, () -> app().update(
                "INSERT INTO place_claim_requests (place_id, business_account_id, evidence_description, status, decided_by_user_id, decided_at) VALUES (?, ?, 'Forjado', 'APPROVED', ?, NOW())",
                place, biz, admin));
        assertEquals("PENDING", owner().queryForObject("SELECT status FROM place_claim_requests WHERE id = ?", String.class, claim));
    }

    // --------------------------------------------------------------------------------------- Cenários 6, 7 e 8

    @Test
    @DisplayName("Cenário 6: tentativa de transferir places.claimed_by_business_id via rewit_app é bloqueada ANTES dos triggers destrutivos V28/V29/V39")
    void scenario06_appCannotHijackPlaceOwnershipOrTriggerCommercialWipe() {
        UUID bizOwner1 = PrivilegedTestDatabase.createUser("s06_biz1", "USER");
        UUID bizOwner2 = PrivilegedTestDatabase.createUser("s06_biz2", "USER");
        UUID biz1 = seedBusiness(bizOwner1, "APPROVED");
        UUID biz2 = seedBusiness(bizOwner2, "APPROVED");
        UUID place = seedPlace("Restaurante S06");
        PrivilegedTestDatabase.setPlaceClaimedBy(place, biz1);

        // Popula dados comerciais protegidos por V28/V29/V39
        UUID productId = UUID.randomUUID();
        owner().update("INSERT INTO rateable_targets (id, target_type) VALUES (?, 'PRODUCT')", productId);
        owner().update("INSERT INTO products (id, name, status) VALUES (?, 'Produto S06', 'ACTIVE')", productId);
        owner().update("UPDATE places SET phone = '+5511999998888', website_url = 'https://s06.example' WHERE id = ?", place);
        owner().update("INSERT INTO place_opening_hours_schedules (place_id, business_account_id, updated_by_user_id) VALUES (?, ?, ?)",
                place, biz1, bizOwner1);
        owner().update("INSERT INTO place_amenity_configurations (place_id, business_account_id, updated_by_user_id) VALUES (?, ?, ?)",
                place, biz1, bizOwner1);
        owner().update("INSERT INTO place_product_offers (id, place_id, product_id, business_account_id, availability, updated_by_user_id) VALUES (?, ?, ?, ?, 'AVAILABLE', ?)",
                UUID.randomUUID(), place, productId, biz1, bizOwner1);

        // Ataques como rewit_app
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE places SET claimed_by_business_id = ? WHERE id = ?", biz2, place));
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE places SET claimed_by_business_id = NULL WHERE id = ?", place));
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE places SET is_verified = TRUE WHERE id = ?", place));
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE places SET status = 'INACTIVE' WHERE id = ?", place));

        // Confirma integridade total da titularidade e de todos os dados comerciais (V28/V29/V39 não foram disparados!)
        Map<String, Object> placeRow = owner().queryForMap(
                "SELECT claimed_by_business_id, is_verified, status, phone, website_url FROM places WHERE id = ?", place);
        assertEquals(biz1, placeRow.get("claimed_by_business_id"));
        assertEquals(Boolean.FALSE, placeRow.get("is_verified"));
        assertEquals("ACTIVE", placeRow.get("status"));
        assertEquals("+5511999998888", placeRow.get("phone"));
        assertEquals("https://s06.example", placeRow.get("website_url"));
        assertEquals(1, owner().queryForObject("SELECT COUNT(*) FROM place_opening_hours_schedules WHERE place_id = ?", Integer.class, place));
        assertEquals(1, owner().queryForObject("SELECT COUNT(*) FROM place_amenity_configurations WHERE place_id = ?", Integer.class, place));
        assertEquals(1, owner().queryForObject("SELECT COUNT(*) FROM place_product_offers WHERE place_id = ?", Integer.class, place));
    }

    @Test
    @DisplayName("Cenários 7 e 8: rewit_app não pode aprovar business_accounts nem suspender/excluir/promover contas em users")
    void scenarios07and08_appCannotApproveBusinessOrMutateAdminAccountLifecycle() {
        UUID admin = PrivilegedTestDatabase.createUser("s07_admin", "ADMIN");
        UUID moderator = PrivilegedTestDatabase.createUser("s07_mod", "MODERATOR");
        UUID normal = PrivilegedTestDatabase.createUser("s07_user", "USER");
        UUID suspended = PrivilegedTestDatabase.createUser("s07_susp", "USER");
        PrivilegedTestDatabase.setUserAccountStatus(suspended, "SUSPENDED");
        UUID deleted = PrivilegedTestDatabase.createUser("s07_del", "USER");
        PrivilegedTestDatabase.setUserAccountStatus(deleted, "DELETED");
        UUID biz = seedBusiness(normal, "PENDING");

        // Cenário 7: business_accounts
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE business_accounts SET verification_status = 'APPROVED' WHERE id = ?", biz));
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE business_accounts SET verification_status = 'REJECTED' WHERE id = ?", biz));
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE business_accounts SET user_id = ? WHERE id = ?", admin, biz));
        assertThrows(DataAccessException.class, () -> app().update(
                "INSERT INTO business_accounts (user_id, corporate_name, tax_id, verification_status) VALUES (?, 'Fake', '99887766554433', 'APPROVED')",
                moderator));
        assertEquals("PENDING", owner().queryForObject("SELECT verification_status FROM business_accounts WHERE id = ?", String.class, biz));

        // Cenário 8: users
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE users SET account_status = 'SUSPENDED', is_active = FALSE WHERE id = ?", admin));
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE users SET account_status = 'DELETED', is_active = FALSE, deleted_at = NOW() WHERE id = ?", moderator));
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE users SET role = 'ADMIN' WHERE id = ?", normal));
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE users SET account_status = 'ACTIVE', is_active = TRUE WHERE id = ?", suspended));
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE users SET account_status = 'ACTIVE', is_active = TRUE, deleted_at = NULL WHERE id = ?", deleted));

        assertEquals("ACTIVE", owner().queryForObject("SELECT account_status FROM users WHERE id = ?", String.class, admin));
        assertEquals("ACTIVE", owner().queryForObject("SELECT account_status FROM users WHERE id = ?", String.class, moderator));
        assertEquals("USER", owner().queryForObject("SELECT role FROM users WHERE id = ?", String.class, normal));
        assertEquals("SUSPENDED", owner().queryForObject("SELECT account_status FROM users WHERE id = ?", String.class, suspended));
        assertEquals("DELETED", owner().queryForObject("SELECT account_status FROM users WHERE id = ?", String.class, deleted));
    }

    // --------------------------------------------------------------------------------------- Cenários 9 a 15

    @Test
    @DisplayName("Cenários 9, 10, 11 e 15: rewit_app não pode moderar/restaurar reviews, discussions, business_responses nem review_media")
    void scenarios09to11and15_appCannotModerateOrRestoreContentEntities() {
        UUID author = PrivilegedTestDatabase.createUser("s09_author", "USER");
        UUID bizUser = PrivilegedTestDatabase.createUser("s09_biz", "USER");
        UUID place = seedPlace("Local S09");
        UUID biz = seedBusiness(bizUser, "APPROVED");
        PrivilegedTestDatabase.setPlaceClaimedBy(place, biz);

        UUID activeReview = seedReview(author, place, "ACTIVE");
        UUID removedReview = seedReview(author, place, "REMOVED");
        UUID underReview = seedReview(author, place, "UNDER_REVIEW");

        UUID activeDisc = seedDiscussion(activeReview, author, "ACTIVE");
        UUID removedDisc = seedDiscussion(activeReview, author, "REMOVED");
        UUID underReviewDisc = seedDiscussion(activeReview, author, "UNDER_REVIEW");

        UUID activeResp = seedBusinessResponse(activeReview, biz, place, bizUser);
        UUID modRemovedResp = seedBusinessResponse(removedReview, biz, place, bizUser);
        owner().update("UPDATE business_responses SET status = 'REMOVED', removed_at = NOW(), removed_by = 'MODERATION' WHERE id = ?", modRemovedResp);

        UUID removedRevMedia = seedReviewMedia(activeReview, author, "REMOVED");

        // Cenário 9: reviews
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE reviews SET status = 'UNDER_REVIEW' WHERE id = ?", activeReview),
                "Quarentena sem 3 denúncias pendentes deve ser bloqueada");
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE reviews SET status = 'ACTIVE' WHERE id = ?", removedReview));
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE reviews SET status = 'ACTIVE' WHERE id = ?", underReview));

        // Cenário 10: review_discussions
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE review_discussions SET status = 'UNDER_REVIEW' WHERE id = ?", activeDisc),
                "Quarentena de discussão sem 3 denúncias pendentes deve ser bloqueada");
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE review_discussions SET status = 'ACTIVE' WHERE id = ?", removedDisc));
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE review_discussions SET status = 'ACTIVE' WHERE id = ?", underReviewDisc));

        // Cenário 11: business_responses
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE business_responses SET status = 'REMOVED', removed_at = NOW(), removed_by = 'MODERATION' WHERE id = ?", activeResp));
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE business_responses SET status = 'ACTIVE', removed_at = NULL, removed_by = NULL WHERE id = ?", modRemovedResp));

        // Cenário 15: review_media
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE review_media SET status = 'ACTIVE' WHERE id = ?", removedRevMedia));
        assertThrows(DataAccessException.class, () -> app().update(
                "INSERT INTO review_media (review_id, user_id, object_key, media_type, mime_type, size_bytes, width, height, status) "
                        + "VALUES (?, ?, 'reviews/forged.jpg', 'IMAGE', 'image/jpeg', 500, 100, 100, 'REMOVED')",
                activeReview, author));

        assertEquals("ACTIVE", owner().queryForObject("SELECT status FROM reviews WHERE id = ?", String.class, activeReview));
        assertEquals("REMOVED", owner().queryForObject("SELECT status FROM reviews WHERE id = ?", String.class, removedReview));
        assertEquals("REMOVED", owner().queryForObject("SELECT status FROM review_discussions WHERE id = ?", String.class, removedDisc));
        assertEquals("ACTIVE", owner().queryForObject("SELECT status FROM business_responses WHERE id = ?", String.class, activeResp));
        assertEquals("REMOVED", owner().queryForObject("SELECT status FROM business_responses WHERE id = ?", String.class, modRemovedResp));
        assertEquals("REMOVED", owner().queryForObject("SELECT status FROM review_media WHERE id = ?", String.class, removedRevMedia));
    }

    @Test
    @DisplayName("Cenários 12, 13 e 14: place_media protege removal_source, bloqueia backdating de removed_at e impede expurgo < 24h (ADR-015)")
    void scenarios12to14_placeMediaGuardsRemovalSourceAntiBackdatingAnd24hRetention() {
        UUID author = PrivilegedTestDatabase.createUser("s12_author", "USER");
        UUID place = seedPlace("Local S12");
        UUID activeMedia = seedPlaceMedia(place, author, "ACTIVE", null, null);
        UUID modRemovedMedia = seedPlaceMedia(place, author, "REMOVED", "MODERATOR", Instant.now().minus(1, ChronoUnit.HOURS));
        UUID authorRemovedMedia = seedPlaceMedia(place, author, "REMOVED", "AUTHOR", Instant.now().minus(1, ChronoUnit.HOURS));

        // Cenário 12: removal_source e restauração
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE place_media SET status = 'REMOVED', removal_source = 'MODERATOR', removed_at = NOW() WHERE id = ?",
                activeMedia));
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE place_media SET removal_source = 'MODERATOR' WHERE id = ?", authorRemovedMedia));
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE place_media SET removal_source = 'AUTHOR' WHERE id = ?", modRemovedMedia));
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE place_media SET status = 'ACTIVE', removal_source = NULL, removed_at = NULL WHERE id = ?", modRemovedMedia));

        // Cenário 13: backdating de removed_at (na remoção e pós-remoção)
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE place_media SET status = 'REMOVED', removal_source = 'AUTHOR', removed_at = NOW() - INTERVAL '48 hours' WHERE id = ?",
                activeMedia));
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE place_media SET removed_at = NOW() - INTERVAL '48 hours' WHERE id = ?",
                authorRemovedMedia));

        // Cenário 14: purged_at prematuro (< 24h ADR-015) bloqueado no banco (tanto para rewit_app quanto rewit_media)
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE place_media SET purged_at = NOW() WHERE id = ?", authorRemovedMedia));
        assertThrows(DataAccessException.class, () -> media().update(
                "UPDATE place_media SET purged_at = NOW() WHERE id = ?", authorRemovedMedia));
        assertThrows(DataAccessException.class, () -> media().update(
                "UPDATE place_media SET purged_at = NOW() WHERE id = ?", modRemovedMedia));

        // Storage GC não encontra mídia removida há menos de 24h
        List<com.rewit.domain.model.PlaceMedia> gcCandidates =
                placeMediaRepository.findRemovalsOlderThan(Instant.now().minus(24, ChronoUnit.HOURS), 100);
        assertTrue(gcCandidates.stream().noneMatch(m -> m.getId().equals(authorRemovedMedia) || m.getId().equals(modRemovedMedia)),
                "Storage GC não pode incluir mídia removida há menos de 24h");
    }

    // --------------------------------------------------------------------------------------- Cenário 16

    @Test
    @DisplayName("Cenário 16: função SECURITY DEFINER rewit_auto_resolve_removed_place_media_reports é restrita a rewit_media, rejeita abuso e protege decisões prévias")
    void scenario16_securityDefinerAutoResolveBlocksAbuse() throws SQLException {
        UUID admin = PrivilegedTestDatabase.createUser("s16_admin", "ADMIN");
        UUID author = PrivilegedTestDatabase.createUser("s16_author", "USER");
        UUID reporter1 = PrivilegedTestDatabase.createUser("s16_rep1", "USER");
        UUID reporter2 = PrivilegedTestDatabase.createUser("s16_rep2", "USER");
        UUID place = seedPlace("Local S16");

        UUID activeMedia = seedPlaceMedia(place, author, "ACTIVE", null, null);
        UUID underReviewMedia = seedPlaceMedia(place, author, "UNDER_REVIEW", null, null);
        UUID modRemovedMedia = seedPlaceMedia(place, author, "REMOVED", "MODERATOR", Instant.now());
        UUID authorRemovedMedia = seedPlaceMedia(place, author, "REMOVED", "AUTHOR", Instant.now());

        seedPlaceMediaReport(activeMedia, reporter1);
        seedPlaceMediaReport(underReviewMedia, reporter1);
        seedPlaceMediaReport(modRemovedMedia, reporter1);

        UUID decidedReport = seedPlaceMediaReport(authorRemovedMedia, reporter1);
        owner().update("UPDATE place_media_reports SET status = 'ACCEPTED', decided_by_user_id = ?, decided_at = NOW(), decision_notes = 'Já decidida', authenticity_mac = ? WHERE id = ?",
                admin, BOGUS_WELL_FORMED_MAC, decidedReport);
        UUID pendingReport = seedPlaceMediaReport(authorRemovedMedia, reporter2);

        // SEC-F2-03: rewit_app não possui EXECUTE nesta função (nem em mídia AUTHOR-removed)
        assertThrows(DataAccessException.class, () -> app().queryForList(
                "SELECT * FROM public.rewit_auto_resolve_removed_place_media_reports(?, 'Chamada indevida por rewit_app')", authorRemovedMedia));

        // rewit_mod também não possui EXECUTE nesta função exclusiva do ciclo de vida público
        assertThrows(DataAccessException.class, () -> mod().queryForList(
                "SELECT * FROM public.rewit_auto_resolve_removed_place_media_reports(?, 'Chamada por rewit_mod')", authorRemovedMedia));

        // Abusos como rewit_media: mídia ACTIVE, UNDER_REVIEW, MODERATOR-removed ou notes vazias
        assertThrows(DataAccessException.class, () -> media().queryForList(
                "SELECT * FROM public.rewit_auto_resolve_removed_place_media_reports(?, 'Abuso em ACTIVE')", activeMedia));
        assertThrows(DataAccessException.class, () -> media().queryForList(
                "SELECT * FROM public.rewit_auto_resolve_removed_place_media_reports(?, 'Abuso em UNDER_REVIEW')", underReviewMedia));
        assertThrows(DataAccessException.class, () -> media().queryForList(
                "SELECT * FROM public.rewit_auto_resolve_removed_place_media_reports(?, 'Abuso em MODERATOR')", modRemovedMedia));
        assertThrows(DataAccessException.class, () -> media().queryForList(
                "SELECT * FROM public.rewit_auto_resolve_removed_place_media_reports(?, '   ')", authorRemovedMedia));

        // Auto-resolução como rewit_media SEM gravar AUTO_RESOLVE_REPORT na mesma transação falha no COMMIT (integridade bidirecional)
        assertThrows(DataAccessException.class, () -> media().queryForList(
                "SELECT * FROM public.rewit_auto_resolve_removed_place_media_reports(?, 'Sem auditoria na mesma TX')",
                authorRemovedMedia));
        assertEquals("PENDING", owner().queryForObject("SELECT status FROM place_media_reports WHERE id = ?", String.class, pendingReport));

        // Invocação legítima como rewit_media com AUTO_RESOLVE_REPORT na mesma transação resolve SOMENTE a PENDING, preservando a ACCEPTED
        try (Connection conn = mediaConn()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT report_id, reason FROM public.rewit_auto_resolve_removed_place_media_reports(?, ?)")) {
                ps.setObject(1, authorRemovedMedia);
                ps.setString(2, "Auto-resolução legítima após remoção do autor");
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(pendingReport, rs.getObject("report_id", UUID.class));
                    assertFalse(rs.next());
                }
            }
            execute(conn,
                    "INSERT INTO place_media_moderation_audit_logs (id, place_media_id, place_id, report_id, actor_user_id, actor_type, action, reason, authenticity_mac) "
                            + "VALUES (?, ?, ?, ?, NULL, 'SYSTEM', 'AUTO_RESOLVE_REPORT', 'OFFENSIVE', ?)",
                    UUID.randomUUID(), authorRemovedMedia, place, pendingReport, BOGUS_WELL_FORMED_MAC);
            conn.commit();
        }
        assertEquals("ACCEPTED", owner().queryForObject("SELECT status FROM place_media_reports WHERE id = ?", String.class, decidedReport));
        assertEquals("Já decidida", owner().queryForObject("SELECT decision_notes FROM place_media_reports WHERE id = ?", String.class, decidedReport));
        assertEquals("AUTO_RESOLVED", owner().queryForObject("SELECT status FROM place_media_reports WHERE id = ?", String.class, pendingReport));
    }

    // --------------------------------------------------------------------------------------- Cenários 17 e 18

    @Test
    @DisplayName("Cenários 17 e 18: MAC malformado é barrado pelo CHECK do banco; MAC forjado ou transplantado entre eventos é INVALID")
    void scenarios17and18_macFormatCheckAndTransplantDetection() {
        UUID admin = PrivilegedTestDatabase.createUser("s17_admin", "ADMIN");
        UUID author = PrivilegedTestDatabase.createUser("s17_author", "USER");
        UUID place = seedPlace("Local S17");
        UUID review1 = seedReview(author, place, "ACTIVE");
        UUID review2 = seedReview(author, place, "ACTIVE");

        // Cenário 17a: formato inválido rejeitado pelo CHECK do PostgreSQL mesmo como owner
        assertThrows(DataAccessException.class, () -> owner().update(
                "INSERT INTO moderation_audit_logs (review_id, moderator_user_id, action, decision, reason_code, justification, previous_review_status, new_review_status, authenticity_mac) "
                        + "VALUES (?, ?, 'REMOVE_REVIEW', 'ACCEPTED', 'SPAM', 'Malformado', 'ACTIVE', 'REMOVED', ?)",
                review1, admin, MALFORMED_MAC));

        // Cenário 17b: MAC bem-formado mas sem o segredo MODERATION_AUDIT_SECRET é INVALID na verificação
        Instant now = ModerationAuditPayload.canonicalTimestamp(Instant.now());
        ModerationAuditPayload p1 = ModerationAuditPayload.forReviewModeration(
                UUID.randomUUID(), review1, admin, "REMOVE_REVIEW", "ACCEPTED",
                "SPAM", "Justificativa real do evento 1.", "ACTIVE", "REMOVED", 0, now);
        assertEquals(AuditVerificationStatus.INVALID, macService.verify(p1, BOGUS_WELL_FORMED_MAC));

        // Cenário 18: MAC válido de um evento transplantado para outro evento (mesmo domínio e domínio diferente) é INVALID
        String mac1 = macService.computeMac(p1);
        assertEquals(AuditVerificationStatus.VERIFIED, macService.verify(p1, mac1));

        ModerationAuditPayload p2SameDomain = ModerationAuditPayload.forReviewModeration(
                UUID.randomUUID(), review2, admin, "REMOVE_REVIEW", "ACCEPTED",
                "SPAM", "Justificativa real do evento 1.", "ACTIVE", "REMOVED", 0, now);
        ModerationAuditPayload p2OtherDomain = ModerationAuditPayload.forDiscussionModeration(
                p1.eventId(), p1.targetId(), admin, "REMOVE_REVIEW", "ACCEPTED",
                "SPAM", "Justificativa real do evento 1.", "ACTIVE", "REMOVED", 0, now);

        assertEquals(AuditVerificationStatus.INVALID, macService.verify(p2SameDomain, mac1));
        assertEquals(AuditVerificationStatus.INVALID, macService.verify(p2OtherDomain, mac1));
    }

    // --------------------------------------------------------------------------------------- Cenários 19 e 20

    @Test
    @DisplayName("Cenários 19 e 20: integridade transacional bidirecional (DEFERRABLE) barra transição sem auditoria E auditoria sem transição nos 6 módulos")
    void scenarios19and20_bidirectionalDeferredIntegrityAcrossAll6Modules() {
        UUID admin = PrivilegedTestDatabase.createUser("s19_admin", "ADMIN");
        UUID author = PrivilegedTestDatabase.createUser("s19_author", "USER");
        UUID targetUser = PrivilegedTestDatabase.createUser("s19_target", "USER");
        UUID bizUser = PrivilegedTestDatabase.createUser("s19_biz", "USER");
        UUID place = seedPlace("Local S19");
        UUID biz = seedBusiness(bizUser, "APPROVED");
        PrivilegedTestDatabase.setPlaceClaimedBy(place, biz);
        UUID placeForClaim = seedPlace("Local Claim S19");
        UUID bizForClaim = seedBusiness(bizUser, "PENDING");
        UUID claim = seedPlaceClaim(placeForClaim, bizForClaim);

        UUID review = seedReview(author, place, "ACTIVE");
        UUID discussion = seedDiscussion(review, author, "UNDER_REVIEW");
        UUID response = seedBusinessResponse(review, biz, place, bizUser);
        UUID media = seedPlaceMedia(place, author, "ACTIVE", null, null);

        // --- Cenário 19: UPDATE administrativo em rewit_mod SEM registro de auditoria correspondente -> COMMIT falha!
        assertCommitFailsAsMod(conn -> execute(conn, "UPDATE reviews SET status = 'REMOVED' WHERE id = ?", review));
        assertCommitFailsAsMod(conn -> execute(conn, "UPDATE review_discussions SET status = 'REMOVED' WHERE id = ?", discussion));
        assertCommitFailsAsMod(conn -> execute(conn,
                "UPDATE business_responses SET status = 'REMOVED', removed_at = NOW(), removed_by = 'MODERATION' WHERE id = ?", response));
        assertCommitFailsAsMod(conn -> execute(conn,
                "UPDATE place_media SET status = 'REMOVED', removal_source = 'MODERATOR', removed_at = NOW() WHERE id = ?", media));
        assertCommitFailsAsMod(conn -> execute(conn,
                "UPDATE place_claim_requests SET status = 'REJECTED', decided_by_user_id = ?, decided_at = NOW(), decision_reason = 'Sem auditoria', authenticity_mac = ? WHERE id = ?",
                admin, BOGUS_WELL_FORMED_MAC, claim));
        assertCommitFailsAsMod(conn -> execute(conn,
                "UPDATE users SET account_status = 'SUSPENDED', is_active = FALSE WHERE id = ?", targetUser));

        // Todas as entidades permaneceram intactas após rollback
        assertEquals("ACTIVE", owner().queryForObject("SELECT status FROM reviews WHERE id = ?", String.class, review));
        assertEquals("UNDER_REVIEW", owner().queryForObject("SELECT status FROM review_discussions WHERE id = ?", String.class, discussion));
        assertEquals("ACTIVE", owner().queryForObject("SELECT status FROM business_responses WHERE id = ?", String.class, response));
        assertEquals("ACTIVE", owner().queryForObject("SELECT status FROM place_media WHERE id = ?", String.class, media));
        assertEquals("PENDING", owner().queryForObject("SELECT status FROM place_claim_requests WHERE id = ?", String.class, claim));
        assertEquals("ACTIVE", owner().queryForObject("SELECT account_status FROM users WHERE id = ?", String.class, targetUser));

        // --- Cenário 20: INSERT de auditoria em rewit_mod SEM transição real da entidade -> COMMIT falha!
        assertCommitFailsAsMod(conn -> execute(conn,
                "INSERT INTO moderation_audit_logs (review_id, moderator_user_id, action, decision, reason_code, justification, previous_review_status, new_review_status, authenticity_mac) "
                        + "VALUES (?, ?, 'REMOVE_REVIEW', 'ACCEPTED', 'SPAM', 'Órfã', 'ACTIVE', 'REMOVED', ?)",
                review, admin, BOGUS_WELL_FORMED_MAC));
        assertCommitFailsAsMod(conn -> execute(conn,
                "INSERT INTO discussion_moderation_audit_logs (discussion_id, moderator_user_id, action, decision, reason_code, justification, previous_status, new_status, authenticity_mac) "
                        + "VALUES (?, ?, 'REMOVE_DISCUSSION', 'ACCEPTED', 'SPAM', 'Órfã', 'UNDER_REVIEW', 'REMOVED', ?)",
                discussion, admin, BOGUS_WELL_FORMED_MAC));
        assertCommitFailsAsMod(conn -> execute(conn,
                "INSERT INTO business_response_moderation_audit_logs (response_id, moderator_user_id, action, reason_code, justification, previous_status, new_status, authenticity_mac) "
                        + "VALUES (?, ?, 'REMOVE_RESPONSE', 'SPAM', 'Órfã', 'ACTIVE', 'REMOVED', ?)",
                response, admin, BOGUS_WELL_FORMED_MAC));
        assertCommitFailsAsMod(conn -> execute(conn,
                "INSERT INTO place_media_moderation_audit_logs (place_media_id, place_id, actor_user_id, actor_type, action, reason, notes, authenticity_mac) "
                        + "VALUES (?, ?, ?, 'USER', 'MODERATOR_REMOVE', 'SPAM', 'Órfã', ?)",
                media, place, admin, BOGUS_WELL_FORMED_MAC));
        assertCommitFailsAsMod(conn -> execute(conn,
                "INSERT INTO place_claim_moderation_audit_logs (claim_id, place_id, business_account_id, moderator_user_id, action, previous_status, new_status, decision_reason, authenticity_mac) "
                        + "VALUES (?, ?, ?, ?, 'REJECT_CLAIM', 'PENDING', 'REJECTED', 'Órfã', ?)",
                claim, placeForClaim, bizForClaim, admin, BOGUS_WELL_FORMED_MAC));
        assertCommitFailsAsMod(conn -> execute(conn,
                "INSERT INTO account_moderation_audit_logs (target_user_id, actor_user_id, action, previous_status, new_status, reason, authenticity_mac) "
                        + "VALUES (?, ?, 'SUSPEND', 'ACTIVE', 'SUSPENDED', 'Órfã', ?)",
                targetUser, admin, BOGUS_WELL_FORMED_MAC));
    }

    // --------------------------------------------------------------------------------------- Cenários 21 e 22

    @Test
    @DisplayName("Cenário 21: falha ao gravar auditoria desfaz atomicamente entidade, denúncias, stats e reputação")
    void scenario21_auditFailureRollsBackEntireModerationTransaction() {
        UUID admin = PrivilegedTestDatabase.createUser("s21_admin", "ADMIN");
        UUID author = PrivilegedTestDatabase.createUser("s21_author", "USER");
        UUID reporter = PrivilegedTestDatabase.createUser("s21_rep", "USER");
        UUID place = seedPlace("Local S21");
        UUID review = seedReview(author, place, "ACTIVE");
        owner().update("INSERT INTO review_reports (review_id, reporter_user_id, reason, status) VALUES (?, ?, 'SPAM', 'PENDING')",
                review, reporter);
        owner().update("INSERT INTO rateable_target_stats (target_id, reviews_count, average_rating) VALUES (?, 1, 4.00) ON CONFLICT (target_id) DO UPDATE SET reviews_count = 1, average_rating = 4.00",
                place);
        owner().update("INSERT INTO user_reputation (user_id, active_reviews) VALUES (?, 1) ON CONFLICT (user_id) DO UPDATE SET active_reviews = 1",
                author);

        // Simula transação de rewit_mod que altera review, denúncia, stats e reputação, mas falha no INSERT de auditoria (MAC malformado)
        assertThrows(SQLException.class, () -> {
            try (Connection conn = modConn()) {
                conn.setAutoCommit(false);
                try {
                    execute(conn, "UPDATE reviews SET status = 'REMOVED' WHERE id = ?", review);
                    execute(conn, "UPDATE review_reports SET status = 'ACCEPTED' WHERE review_id = ?", review);
                    execute(conn, "UPDATE rateable_target_stats SET reviews_count = 0, average_rating = 0 WHERE target_id = ?", place);
                    execute(conn, "UPDATE user_reputation SET active_reviews = 0 WHERE user_id = ?", author);
                    // Falha proposital na escrita da auditoria
                    execute(conn,
                            "INSERT INTO moderation_audit_logs (review_id, moderator_user_id, action, decision, reason_code, justification, previous_review_status, new_review_status, authenticity_mac) "
                                    + "VALUES (?, ?, 'REMOVE_REVIEW', 'ACCEPTED', 'SPAM', 'Falha', 'ACTIVE', 'REMOVED', ?)",
                            review, admin, MALFORMED_MAC);
                    conn.commit();
                } catch (SQLException ex) {
                    conn.rollback();
                    throw ex;
                }
            }
        });

        assertEquals("ACTIVE", owner().queryForObject("SELECT status FROM reviews WHERE id = ?", String.class, review));
        assertEquals("PENDING", owner().queryForObject("SELECT status FROM review_reports WHERE review_id = ?", String.class, review));
        assertEquals(1, owner().queryForObject("SELECT reviews_count FROM rateable_target_stats WHERE target_id = ?", Integer.class, place));
        assertEquals(1, owner().queryForObject("SELECT active_reviews FROM user_reputation WHERE user_id = ?", Integer.class, author));
        assertEquals(0, owner().queryForObject("SELECT COUNT(*) FROM moderation_audit_logs WHERE review_id = ?", Integer.class, review));
    }

    @Test
    @DisplayName("Cenário 22: replay de decisões finais e auditorias duplicadas na mesma transação são bloqueados")
    void scenario22_decisionReplayAndDuplicateAuditInSameTxAreBlocked() {
        UUID admin = PrivilegedTestDatabase.createUser("s22_admin", "ADMIN");
        UUID author = PrivilegedTestDatabase.createUser("s22_author", "USER");
        UUID reporter = PrivilegedTestDatabase.createUser("s22_rep", "USER");
        UUID bizUser = PrivilegedTestDatabase.createUser("s22_biz", "USER");
        UUID place = seedPlace("Local S22");
        UUID biz = seedBusiness(bizUser, "PENDING");
        UUID media = seedPlaceMedia(place, author, "ACTIVE", null, null);
        UUID mediaReport = seedPlaceMediaReport(media, reporter);
        UUID claim = seedPlaceClaim(place, biz);

        // Decisão legítima inicial
        placeMediaService.decideReport(admin, mediaReport, "REJECT", "Improcedente após verificação.");
        decidePlaceClaimUseCase.execute(admin, claim, PlaceClaimDecision.REJECT,
                "Documentação enviada não comprova vínculo comercial.", Instant.now());

        // Replay de decisão final em place_media_reports (inclusive via rewit_mod direto!)
        assertThrows(DataAccessException.class, () -> mod().update(
                "UPDATE place_media_reports SET status = 'ACCEPTED' WHERE id = ?", mediaReport));
        assertThrows(DataAccessException.class, () -> mod().update(
                "UPDATE place_media_reports SET status = 'PENDING' WHERE id = ?", mediaReport));

        // Replay de decisão final em place_claim_requests (inclusive via rewit_mod direto!)
        assertThrows(DataAccessException.class, () -> mod().update(
                "UPDATE place_claim_requests SET status = 'APPROVED' WHERE id = ?", claim));
        assertThrows(DataAccessException.class, () -> mod().update(
                "UPDATE place_claim_requests SET status = 'PENDING' WHERE id = ?", claim));

        // Duas auditorias na mesma transação para a mesma entidade
        UUID review = seedReview(author, place, "ACTIVE");
        assertCommitFailsAsMod(conn -> {
            execute(conn, "UPDATE reviews SET status = 'REMOVED' WHERE id = ?", review);
            execute(conn,
                    "INSERT INTO moderation_audit_logs (review_id, moderator_user_id, action, decision, reason_code, justification, previous_review_status, new_review_status, authenticity_mac) "
                            + "VALUES (?, ?, 'REMOVE_REVIEW', 'ACCEPTED', 'SPAM', 'Primeira auditoria', 'ACTIVE', 'REMOVED', ?)",
                    review, admin, BOGUS_WELL_FORMED_MAC);
            execute(conn,
                    "INSERT INTO moderation_audit_logs (review_id, moderator_user_id, action, decision, reason_code, justification, previous_review_status, new_review_status, authenticity_mac) "
                            + "VALUES (?, ?, 'REMOVE_REVIEW', 'ACCEPTED', 'SPAM', 'Segunda auditoria duplicada', 'ACTIVE', 'REMOVED', ?)",
                    review, admin, BOGUS_WELL_FORMED_MAC);
        });
    }

    // --------------------------------------------------------------------------------------- Cenário 23

    @Test
    @DisplayName("Cenário 23: JWT obsoleto de moderador desativado ou rebaixado é rejeitado via API sem efeitos colaterais")
    void scenario23_staleJwtOfDisabledOrDemotedModeratorIsRejected() throws Exception {
        TestUser modDisabled = registerUser("s23_dis");
        PrivilegedTestDatabase.assignRole(modDisabled.userId(), "MODERATOR");
        String disabledToken = login(modDisabled.email(), modDisabled.password());
        PrivilegedTestDatabase.setUserAccountStatus(modDisabled.userId(), "SUSPENDED");

        TestUser modDemoted = registerUser("s23_dem");
        PrivilegedTestDatabase.assignRole(modDemoted.userId(), "MODERATOR");
        String demotedToken = login(modDemoted.email(), modDemoted.password());
        PrivilegedTestDatabase.assignRole(modDemoted.userId(), "USER");

        UUID author = PrivilegedTestDatabase.createUser("s23_author", "USER");
        UUID place = seedPlace("Local S23");
        UUID review = seedReview(author, place, "ACTIVE");

        String body = """
                {
                  "action": "REMOVE_REVIEW",
                  "reasonCode": "SPAM",
                  "justification": "Tentativa com token antigo após revogação de privilégio."
                }
                """;

        mockMvc.perform(post("/api/v1/admin/reviews/{id}/moderate", review)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + disabledToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/admin/reviews/{id}/moderate", review)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + demotedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());

        assertEquals("ACTIVE", owner().queryForObject("SELECT status FROM reviews WHERE id = ?", String.class, review));
        assertEquals(0, owner().queryForObject("SELECT COUNT(*) FROM moderation_audit_logs WHERE review_id = ?", Integer.class, review));
    }

    // --------------------------------------------------------------------------------------- Cenário 24

    @Test
    @DisplayName("Cenário 24: fluxos públicos legítimos continuam funcionando integralmente sob rewit_app (inclusive auto-quarentena, auto-resolve e expurgo > 24h)")
    void scenario24_legitimatePublicFlowsContinueWorkingUnderAppRole() {
        UUID author = PrivilegedTestDatabase.createUser("s24_author", "USER");
        UUID rep1 = PrivilegedTestDatabase.createUser("s24_rep1", "USER");
        UUID rep2 = PrivilegedTestDatabase.createUser("s24_rep2", "USER");
        UUID rep3 = PrivilegedTestDatabase.createUser("s24_rep3", "USER");
        UUID bizUser = PrivilegedTestDatabase.createUser("s24_biz", "USER");
        UUID placeId = seedPlace("Local S24");
        UUID biz = seedBusiness(bizUser, "APPROVED");
        PrivilegedTestDatabase.setPlaceClaimedBy(placeId, biz);

        // 1. Autor cria e remove própria avaliação + discussão
        UUID reviewToDelete = reviewService.createReview(new CreateReviewCommand(
                author, placeId, "Avaliação que o autor vai remover", false, "PUBLIC",
                List.of(new CreateReviewTargetCommand(placeId, new BigDecimal("5.0"), null)))).id();
        UUID discToDelete = discussionService.createDiscussion(
                new CreateDiscussionCommand(reviewToDelete, author, null, "Comentário do autor")).id();
        discussionService.deleteDiscussion(discToDelete, author);
        deleteReviewUseCase.execute(reviewToDelete, author, Instant.now());
        assertEquals("REMOVED", owner().queryForObject("SELECT status FROM review_discussions WHERE id = ?", String.class, discToDelete));
        assertEquals("REMOVED", owner().queryForObject("SELECT status FROM reviews WHERE id = ?", String.class, reviewToDelete));

        // 2. Empresa remove própria resposta oficial
        UUID activeReview = seedReview(author, placeId, "ACTIVE");
        UUID responseId = seedBusinessResponse(activeReview, biz, placeId, bizUser);
        removeBusinessResponseUseCase.execute(bizUser, responseId);
        assertEquals("REMOVED", owner().queryForObject("SELECT status FROM business_responses WHERE id = ?", String.class, responseId));
        assertEquals("BUSINESS", owner().queryForObject("SELECT removed_by FROM business_responses WHERE id = ?", String.class, responseId));

        // 3. Auto-quarentena por 3 denúncias reais em review e place_media (com MAC VERIFIED no AUTO_QUARANTINE)
        reportService.createReport(new CreateReportCommand(rep1, activeReview, ReportReason.SPAM, "Denúncia 1"));
        reportService.createReport(new CreateReportCommand(rep2, activeReview, ReportReason.SPAM, "Denúncia 2"));
        reportService.createReport(new CreateReportCommand(rep3, activeReview, ReportReason.SPAM, "Denúncia 3"));
        assertEquals("UNDER_REVIEW", owner().queryForObject("SELECT status FROM reviews WHERE id = ?", String.class, activeReview));

        UUID mediaQuarantine = seedPlaceMedia(placeId, author, "ACTIVE", null, null);
        placeMediaService.reportMedia(rep1, placeId, mediaQuarantine, "OFFENSIVE", "d1");
        placeMediaService.reportMedia(rep2, placeId, mediaQuarantine, "OFFENSIVE", "d2");
        placeMediaService.reportMedia(rep3, placeId, mediaQuarantine, "OFFENSIVE", "d3");
        assertEquals("UNDER_REVIEW", owner().queryForObject("SELECT status FROM place_media WHERE id = ?", String.class, mediaQuarantine));

        Map<String, Object> aqRow = owner().queryForMap(
                "SELECT id, place_media_id, place_id, report_id, actor_user_id, actor_type, action, reason, notes, created_at, authenticity_mac "
                        + "FROM place_media_moderation_audit_logs WHERE place_media_id = ? AND action = 'AUTO_QUARANTINE'",
                mediaQuarantine);
        assertEquals(AuditVerificationStatus.VERIFIED, verifyPlaceMediaAuditRow(aqRow));

        // 4. Autor, criador do local e representante comercial removem mídias sob rewit_media -> MAC VERIFIED nos 5 eventos públicos
        UUID placeManager = PrivilegedTestDatabase.createUser("s24_manager", "USER");
        owner().update("UPDATE places SET created_by_user_id = ? WHERE id = ?", placeManager, placeId);

        UUID mediaAuthorDel = seedPlaceMedia(placeId, author, "ACTIVE", null, null);
        UUID pendingReport = seedPlaceMediaReport(mediaAuthorDel, rep1);
        placeMediaService.deleteMedia(author, placeId, mediaAuthorDel, RemovalSource.AUTHOR);
        assertEquals("REMOVED", owner().queryForObject("SELECT status FROM place_media WHERE id = ?", String.class, mediaAuthorDel));
        assertEquals("AUTO_RESOLVED", owner().queryForObject("SELECT status FROM place_media_reports WHERE id = ?", String.class, pendingReport));

        Map<String, Object> authorRemoveAudit = owner().queryForMap(
                "SELECT id, place_media_id, place_id, report_id, actor_user_id, actor_type, action, reason, notes, created_at, authenticity_mac "
                        + "FROM place_media_moderation_audit_logs WHERE place_media_id = ? AND action = 'AUTHOR_REMOVE'",
                mediaAuthorDel);
        assertEquals(AuditVerificationStatus.VERIFIED, verifyPlaceMediaAuditRow(authorRemoveAudit));

        Map<String, Object> autoResolveAudit = owner().queryForMap(
                "SELECT id, place_media_id, place_id, report_id, actor_user_id, actor_type, action, reason, notes, created_at, authenticity_mac "
                        + "FROM place_media_moderation_audit_logs WHERE place_media_id = ? AND action = 'AUTO_RESOLVE_REPORT'",
                mediaAuthorDel);
        assertEquals(AuditVerificationStatus.VERIFIED, verifyPlaceMediaAuditRow(autoResolveAudit));

        UUID mediaManagerDel = seedPlaceMedia(placeId, author, "ACTIVE", null, null);
        placeMediaService.deleteMedia(placeManager, placeId, mediaManagerDel, RemovalSource.PLACE_MANAGER);
        Map<String, Object> managerRemoveAudit = owner().queryForMap(
                "SELECT id, place_media_id, place_id, report_id, actor_user_id, actor_type, action, reason, notes, created_at, authenticity_mac "
                        + "FROM place_media_moderation_audit_logs WHERE place_media_id = ? AND action = 'PLACE_MANAGER_REMOVE'",
                mediaManagerDel);
        assertEquals(AuditVerificationStatus.VERIFIED, verifyPlaceMediaAuditRow(managerRemoveAudit));

        UUID mediaBizDel = seedPlaceMedia(placeId, author, "ACTIVE", null, null);
        placeMediaService.deleteMedia(bizUser, placeId, mediaBizDel, RemovalSource.BUSINESS_OWNER);
        Map<String, Object> bizRemoveAudit = owner().queryForMap(
                "SELECT id, place_media_id, place_id, report_id, actor_user_id, actor_type, action, reason, notes, created_at, authenticity_mac "
                        + "FROM place_media_moderation_audit_logs WHERE place_media_id = ? AND action = 'BUSINESS_OWNER_REMOVE'",
                mediaBizDel);
        assertEquals(AuditVerificationStatus.VERIFIED, verifyPlaceMediaAuditRow(bizRemoveAudit));

        // Troca de foto de capa sob rewit_media
        UUID coverMedia = seedPlaceMedia(placeId, author, "ACTIVE", null, null);
        placeMediaService.setPrimaryMedia(placeManager, placeId, coverMedia);
        assertTrue(Boolean.TRUE.equals(owner().queryForObject("SELECT is_primary FROM place_media WHERE id = ?", Boolean.class, coverMedia)));

        // 5. Desativação legítima pelo próprio usuário (ACTIVE -> DEACTIVATED) e reativação via app
        UUID selfUser = PrivilegedTestDatabase.createUser("s24_self", "USER");
        deactivateAccountUseCase.execute(selfUser);
        assertEquals("DEACTIVATED", owner().queryForObject("SELECT account_status FROM users WHERE id = ?", String.class, selfUser));
        app().update("UPDATE users SET account_status = 'ACTIVE', is_active = TRUE WHERE id = ?", selfUser);
        assertEquals("ACTIVE", owner().queryForObject("SELECT account_status FROM users WHERE id = ?", String.class, selfUser));

        // 6. Expurgo (purged_at) permitido após > 24h (ADR-015) sob rewit_media (e bloqueado para rewit_app)
        UUID oldRemovedMedia = seedPlaceMedia(placeId, author, "REMOVED", "AUTHOR", Instant.now().minus(25, ChronoUnit.HOURS));
        assertThrows(DataAccessException.class, () -> app().update("UPDATE place_media SET purged_at = NOW() WHERE id = ?", oldRemovedMedia));
        media().update("UPDATE place_media SET purged_at = NOW() WHERE id = ?", oldRemovedMedia);
        assertNotNull(owner().queryForObject("SELECT purged_at FROM place_media WHERE id = ?", Timestamp.class, oldRemovedMedia));
    }

    // --------------------------------------------------------------------------------------- Cenário 25

    @Test
    @DisplayName("Cenário 25: todos os 6 módulos administrativos executam atomicamente sob rewit_mod e gravam MACs VERIFIED")
    void scenario25_all6AdminModulesSucceedAtomicallyWithVerifiedMacs() {
        UUID admin = PrivilegedTestDatabase.createUser("s25_admin", "ADMIN");
        UUID author = PrivilegedTestDatabase.createUser("s25_author", "USER");
        UUID reporter = PrivilegedTestDatabase.createUser("s25_rep", "USER");
        UUID bizUser = PrivilegedTestDatabase.createUser("s25_biz", "USER");
        UUID targetAccount = PrivilegedTestDatabase.createUser("s25_target", "USER");

        UUID place = seedPlace("Local S25");
        UUID claimPlace = seedPlace("Local Claim S25");
        UUID bizApproved = seedBusiness(bizUser, "APPROVED");
        PrivilegedTestDatabase.setPlaceClaimedBy(place, bizApproved);
        UUID bizPending = seedBusiness(bizUser, "PENDING");

        // Módulo 1: ModerateReviewUseCase
        UUID review = seedReview(author, place, "ACTIVE");
        owner().update("INSERT INTO review_reports (review_id, reporter_user_id, reason, status) VALUES (?, ?, 'SPAM', 'PENDING')",
                review, reporter);
        var revRes = moderateReviewUseCase.execute(new ModerateReviewCommand(
                review, admin, ModerationAction.REMOVE_REVIEW, "SPAM", "Avaliação spam confirmada em auditoria F2."));
        Map<String, Object> revAudit = owner().queryForMap(
                "SELECT id, review_id, moderator_user_id, action, decision, reason_code, justification, previous_review_status, new_review_status, reports_affected_count, created_at, authenticity_mac "
                        + "FROM moderation_audit_logs WHERE id = ?", revRes.auditLog().getId());
        assertEquals(AuditVerificationStatus.VERIFIED, macService.verify(
                ModerationAuditPayload.forReviewModeration(
                        (UUID) revAudit.get("id"), (UUID) revAudit.get("review_id"), (UUID) revAudit.get("moderator_user_id"),
                        (String) revAudit.get("action"), (String) revAudit.get("decision"), (String) revAudit.get("reason_code"),
                        (String) revAudit.get("justification"), (String) revAudit.get("previous_review_status"),
                        (String) revAudit.get("new_review_status"), ((Number) revAudit.get("reports_affected_count")).intValue(),
                        ((Timestamp) revAudit.get("created_at")).toInstant()),
                (String) revAudit.get("authenticity_mac")));

        // Módulo 2: ModerateDiscussionUseCase
        UUID reviewForDisc = seedReview(author, place, "ACTIVE");
        UUID discussion = seedDiscussion(reviewForDisc, author, "UNDER_REVIEW");
        owner().update("INSERT INTO discussion_reports (discussion_id, reporter_user_id, reason, status) VALUES (?, ?, 'SPAM', 'PENDING')",
                discussion, reporter);
        var discRes = moderateDiscussionUseCase.execute(new ModerateDiscussionCommand(
                discussion, admin, DiscussionModerationAction.REMOVE_DISCUSSION, "OFFENSIVE",
                "Comentário ofensivo confirmado pela moderação.", null));
        Map<String, Object> discAudit = owner().queryForMap(
                "SELECT id, discussion_id, moderator_user_id, action, decision, reason_code, justification, previous_status, new_status, reports_affected_count, created_at, authenticity_mac "
                        + "FROM discussion_moderation_audit_logs WHERE id = ?", discRes.auditLog().getId());
        assertEquals(AuditVerificationStatus.VERIFIED, macService.verify(
                ModerationAuditPayload.forDiscussionModeration(
                        (UUID) discAudit.get("id"), (UUID) discAudit.get("discussion_id"), (UUID) discAudit.get("moderator_user_id"),
                        (String) discAudit.get("action"), (String) discAudit.get("decision"), (String) discAudit.get("reason_code"),
                        (String) discAudit.get("justification"), (String) discAudit.get("previous_status"),
                        (String) discAudit.get("new_status"), ((Number) discAudit.get("reports_affected_count")).intValue(),
                        ((Timestamp) discAudit.get("created_at")).toInstant()),
                (String) discAudit.get("authenticity_mac")));

        // Módulo 3: ModerateBusinessResponseUseCase (remoção + restauração)
        UUID response = seedBusinessResponse(reviewForDisc, bizApproved, place, bizUser);
        var respRem = moderateBusinessResponseUseCase.execute(new ModerateBusinessResponseCommand(
                response, admin, BusinessResponseModerationAction.REMOVE_RESPONSE, "ABUSE",
                "Resposta oficial em violação às diretrizes.", null));
        verifyBusinessResponseAudit(respRem.auditLogId());
        var respRest = moderateBusinessResponseUseCase.execute(new ModerateBusinessResponseCommand(
                response, admin, BusinessResponseModerationAction.RESTORE_RESPONSE, "RECONSIDERATION",
                "Resposta revisada e restaurada pela moderação.", null));
        verifyBusinessResponseAudit(respRest.auditLogId());

        // Módulo 4: PlaceMediaService (decideReport ACCEPTED + restoreMediaByModerator)
        UUID media = seedPlaceMedia(place, author, "ACTIVE", null, null);
        String mediaKey = "places/f2-" + media + ".jpg";
        objectStoragePort.put(mediaKey, "image/jpeg", new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xD9});
        UUID mediaReport = seedPlaceMediaReport(media, reporter);
        placeMediaService.decideReport(admin, mediaReport, "ACCEPT", "Foto imprópria confirmada.");
        Map<String, Object> mrRow = owner().queryForMap(
                "SELECT id, place_media_id, decided_by_user_id, status, reason, decision_notes, decided_at, authenticity_mac "
                        + "FROM place_media_reports WHERE id = ?", mediaReport);
        assertEquals(AuditVerificationStatus.VERIFIED, macService.verify(
                ModerationAuditPayload.forPlaceMediaReportDecision(
                        (UUID) mrRow.get("id"), (UUID) mrRow.get("place_media_id"), (UUID) mrRow.get("decided_by_user_id"),
                        (String) mrRow.get("status"), (String) mrRow.get("reason"), (String) mrRow.get("decision_notes"),
                        ((Timestamp) mrRow.get("decided_at")).toInstant()),
                (String) mrRow.get("authenticity_mac")));
        placeMediaService.restoreMediaByModerator(admin, place, media, "Restauração após revisão administrativa.");
        List<Map<String, Object>> pmAudits = owner().queryForList(
                "SELECT id, place_media_id, place_id, report_id, actor_user_id, actor_type, action, reason, notes, created_at, authenticity_mac "
                        + "FROM place_media_moderation_audit_logs WHERE place_media_id = ?", media);
        assertFalse(pmAudits.isEmpty());
        for (Map<String, Object> pmAudit : pmAudits) {
            assertEquals(AuditVerificationStatus.VERIFIED, verifyPlaceMediaAuditRow(pmAudit));
        }

        // Módulo 5: DecidePlaceClaimUseCase (APPROVE -> aprova empresa, vincula local, assina decisão e trilha)
        UUID claim = seedPlaceClaim(claimPlace, bizPending);
        decidePlaceClaimUseCase.execute(admin, claim, PlaceClaimDecision.APPROVE,
                "Documentação validada integralmente pela equipe.", Instant.now());
        assertEquals(bizPending, owner().queryForObject("SELECT claimed_by_business_id FROM places WHERE id = ?", UUID.class, claimPlace));
        assertEquals("APPROVED", owner().queryForObject("SELECT verification_status FROM business_accounts WHERE id = ?", String.class, bizPending));

        Map<String, Object> claimRow = owner().queryForMap(
                "SELECT id, place_id, business_account_id, decided_by_user_id, status, decision_reason, decided_at, authenticity_mac "
                        + "FROM place_claim_requests WHERE id = ?", claim);
        assertEquals(AuditVerificationStatus.VERIFIED, macService.verify(
                ModerationAuditPayload.forPlaceClaimDecision(
                        (UUID) claimRow.get("id"), (UUID) claimRow.get("place_id"), (UUID) claimRow.get("business_account_id"),
                        (UUID) claimRow.get("decided_by_user_id"), (String) claimRow.get("status"),
                        (String) claimRow.get("decision_reason"), ((Timestamp) claimRow.get("decided_at")).toInstant()),
                (String) claimRow.get("authenticity_mac")));

        Map<String, Object> claimAudit = owner().queryForMap(
                "SELECT id, claim_id, place_id, business_account_id, moderator_user_id, action, previous_status, new_status, decision_reason, created_at, authenticity_mac "
                        + "FROM place_claim_moderation_audit_logs WHERE claim_id = ?", claim);
        assertEquals(AuditVerificationStatus.VERIFIED, macService.verify(
                ModerationAuditPayload.forPlaceClaimAudit(
                        (UUID) claimAudit.get("id"), (UUID) claimAudit.get("claim_id"), (UUID) claimAudit.get("place_id"),
                        (UUID) claimAudit.get("business_account_id"), (UUID) claimAudit.get("moderator_user_id"),
                        (String) claimAudit.get("action"), (String) claimAudit.get("previous_status"),
                        (String) claimAudit.get("new_status"), (String) claimAudit.get("decision_reason"),
                        ((Timestamp) claimAudit.get("created_at")).toInstant()),
                (String) claimAudit.get("authenticity_mac")));

        // Módulo 6: AdminAccountLifecycleUseCase (SUSPEND -> REINSTATE -> DELETE)
        adminAccountLifecycleUseCase.execute(admin, targetAccount, AdminAccountLifecycleUseCase.Action.SUSPEND, "Violação grave");
        adminAccountLifecycleUseCase.execute(admin, targetAccount, AdminAccountLifecycleUseCase.Action.REINSTATE, "Recurso aceito");
        adminAccountLifecycleUseCase.execute(admin, targetAccount, AdminAccountLifecycleUseCase.Action.DELETE, "Encerramento administrativo");
        assertEquals("DELETED", owner().queryForObject("SELECT account_status FROM users WHERE id = ?", String.class, targetAccount));

        List<Map<String, Object>> accAudits = owner().queryForList(
                "SELECT id, target_user_id, actor_user_id, action, previous_status, new_status, reason, created_at, authenticity_mac "
                        + "FROM account_moderation_audit_logs WHERE target_user_id = ? ORDER BY created_at ASC", targetAccount);
        assertEquals(3, accAudits.size());
        for (Map<String, Object> accAudit : accAudits) {
            assertEquals(AuditVerificationStatus.VERIFIED, macService.verify(
                    ModerationAuditPayload.forAccountLifecycleAudit(
                            (UUID) accAudit.get("id"), (UUID) accAudit.get("target_user_id"),
                            (UUID) accAudit.get("actor_user_id"), (String) accAudit.get("action"),
                            (String) accAudit.get("previous_status"), (String) accAudit.get("new_status"),
                            (String) accAudit.get("reason"), ((Timestamp) accAudit.get("created_at")).toInstant()),
                    (String) accAudit.get("authenticity_mac")));
        }
    }

    // --------------------------------------------------------------------------------------- Cenários 26 e 27 (SEC-F2-03)

    @Test
    @DisplayName("Cenário 26 (SEC-F2-03): rewit_app comprometida não consegue forjar nenhum dos 5 eventos públicos de place_media nem executar transições protegidas")
    void scenario26_appCannotForgeAnyPublicPlaceMediaEventOrTransition() {
        // Verifica identidade SQL real da conexão comprometida
        Map<String, Object> identity = app().queryForMap("SELECT session_user::text AS su, current_user::text AS cu");
        assertEquals("rewit_app", identity.get("su"));
        assertEquals("rewit_app", identity.get("cu"));

        UUID author = PrivilegedTestDatabase.createUser("s26_author", "USER");
        UUID manager = PrivilegedTestDatabase.createUser("s26_manager", "USER");
        UUID bizUser = PrivilegedTestDatabase.createUser("s26_biz", "USER");
        UUID rep1 = PrivilegedTestDatabase.createUser("s26_rep1", "USER");
        UUID rep2 = PrivilegedTestDatabase.createUser("s26_rep2", "USER");
        UUID rep3 = PrivilegedTestDatabase.createUser("s26_rep3", "USER");

        UUID place = seedPlace("Local S26");
        UUID biz = seedBusiness(bizUser, "APPROVED");
        owner().update("UPDATE places SET created_by_user_id = ?, claimed_by_business_id = ? WHERE id = ?", manager, biz, place);

        UUID mAuthor = seedPlaceMedia(place, author, "ACTIVE", null, null);
        UUID mManager = seedPlaceMedia(place, author, "ACTIVE", null, null);
        UUID mBiz = seedPlaceMedia(place, author, "ACTIVE", null, null);
        UUID mQuarantine = seedPlaceMedia(place, author, "ACTIVE", null, null);
        UUID r1 = seedPlaceMediaReport(mQuarantine, rep1);
        seedPlaceMediaReport(mQuarantine, rep2);
        seedPlaceMediaReport(mQuarantine, rep3);

        // Pré-condições simuladas já no estado-alvo para provar que mesmo INSERT isolado falha para rewit_app
        UUID mAlreadyRemoved = seedPlaceMedia(place, author, "REMOVED", "AUTHOR", Instant.now());
        UUID rAutoResolved = seedPlaceMediaReport(mAlreadyRemoved, rep1);
        owner().update("UPDATE place_media_reports SET status = 'AUTO_RESOLVED', decided_at = NOW(), decision_notes = 'seed' WHERE id = ?", rAutoResolved);

        // 1) AUTHOR_REMOVE: UPDATE isolado, INSERT isolado e ambos na mesma transação como rewit_app
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE place_media SET status = 'REMOVED', removal_source = 'AUTHOR', removed_at = NOW(), is_primary = false WHERE id = ?",
                mAuthor));
        assertThrows(DataAccessException.class, () -> app().update(
                "INSERT INTO place_media_moderation_audit_logs (place_media_id, place_id, actor_user_id, actor_type, action, reason, authenticity_mac) "
                        + "VALUES (?, ?, ?, 'USER', 'AUTHOR_REMOVE', 'forged', ?)",
                mAlreadyRemoved, place, author, BOGUS_WELL_FORMED_MAC));
        assertThrows(SQLException.class, () -> {
            try (Connection conn = appConn()) {
                conn.setAutoCommit(false);
                execute(conn, "UPDATE place_media SET status = 'REMOVED', removal_source = 'AUTHOR', removed_at = NOW(), is_primary = false WHERE id = ?", mAuthor);
                execute(conn, "INSERT INTO place_media_moderation_audit_logs (place_media_id, place_id, actor_user_id, actor_type, action, reason, authenticity_mac) VALUES (?, ?, ?, 'USER', 'AUTHOR_REMOVE', 'forged', ?)",
                        mAuthor, place, author, BOGUS_WELL_FORMED_MAC);
                conn.commit();
            }
        });

        // 2) PLACE_MANAGER_REMOVE
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE place_media SET status = 'REMOVED', removal_source = 'PLACE_MANAGER', removed_at = NOW(), is_primary = false WHERE id = ?",
                mManager));
        assertThrows(DataAccessException.class, () -> app().update(
                "INSERT INTO place_media_moderation_audit_logs (place_media_id, place_id, actor_user_id, actor_type, action, reason, authenticity_mac) "
                        + "VALUES (?, ?, ?, 'USER', 'PLACE_MANAGER_REMOVE', 'forged', ?)",
                mManager, place, manager, BOGUS_WELL_FORMED_MAC));

        // 3) BUSINESS_OWNER_REMOVE
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE place_media SET status = 'REMOVED', removal_source = 'BUSINESS_OWNER', removed_at = NOW(), is_primary = false WHERE id = ?",
                mBiz));
        assertThrows(DataAccessException.class, () -> app().update(
                "INSERT INTO place_media_moderation_audit_logs (place_media_id, place_id, actor_user_id, actor_type, action, reason, authenticity_mac) "
                        + "VALUES (?, ?, ?, 'USER', 'BUSINESS_OWNER_REMOVE', 'forged', ?)",
                mBiz, place, bizUser, BOGUS_WELL_FORMED_MAC));

        // 4) AUTO_QUARANTINE
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE place_media SET status = 'UNDER_REVIEW', is_primary = false WHERE id = ?",
                mQuarantine));
        assertThrows(DataAccessException.class, () -> app().update(
                "INSERT INTO place_media_moderation_audit_logs (place_media_id, place_id, report_id, actor_user_id, actor_type, action, reason, authenticity_mac) "
                        + "VALUES (?, ?, ?, NULL, 'SYSTEM', 'AUTO_QUARANTINE', 'forged', ?)",
                mQuarantine, place, r1, BOGUS_WELL_FORMED_MAC));

        // 5) AUTO_RESOLVE_REPORT (via SECURITY DEFINER ou INSERT direto)
        assertThrows(DataAccessException.class, () -> app().queryForList(
                "SELECT * FROM public.rewit_auto_resolve_removed_place_media_reports(?, 'forged')", mAlreadyRemoved));
        assertThrows(DataAccessException.class, () -> app().update(
                "INSERT INTO place_media_moderation_audit_logs (place_media_id, place_id, report_id, actor_user_id, actor_type, action, reason, authenticity_mac) "
                        + "VALUES (?, ?, ?, NULL, 'SYSTEM', 'AUTO_RESOLVE_REPORT', 'forged', ?)",
                mAlreadyRemoved, place, rAutoResolved, BOGUS_WELL_FORMED_MAC));

        // Criação direta de denúncia em place_media_reports também vedada a rewit_app
        assertThrows(DataAccessException.class, () -> app().update(
                "INSERT INTO place_media_reports (id, place_media_id, reporter_user_id, reason, detail, status) VALUES (?, ?, ?, 'OFFENSIVE', 'spam', 'PENDING')",
                UUID.randomUUID(), mAuthor, rep1));

        // Escalação via SET ROLE rewit_media vedada
        assertThrows(DataAccessException.class, () -> app().execute("SET ROLE rewit_media"));

        // Nenhum evento forjado foi persistido e nenhuma mídia foi alterada
        Integer auditCount = owner().queryForObject(
                "SELECT COUNT(*) FROM place_media_moderation_audit_logs WHERE place_id = ?", Integer.class, place);
        assertEquals(0, auditCount);
        assertEquals("ACTIVE", owner().queryForObject("SELECT status FROM place_media WHERE id = ?", String.class, mAuthor));
        assertEquals("ACTIVE", owner().queryForObject("SELECT status FROM place_media WHERE id = ?", String.class, mManager));
        assertEquals("ACTIVE", owner().queryForObject("SELECT status FROM place_media WHERE id = ?", String.class, mBiz));
        assertEquals("ACTIVE", owner().queryForObject("SELECT status FROM place_media WHERE id = ?", String.class, mQuarantine));
    }

    @Test
    @DisplayName("Cenário 27 (SEC-F2-03): isolamento estrito de rewit_media — sem escalação para rewit_mod, sem trilhas/ações administrativas e sem mutação fora do escopo")
    void scenario27_mediaRoleIsStrictlyIsolatedFromAdminAndOtherDomains() {
        Map<String, Object> identity = media().queryForMap("SELECT session_user::text AS su, current_user::text AS cu");
        assertEquals("rewit_media", identity.get("su"));
        assertEquals("rewit_media", identity.get("cu"));

        UUID admin = PrivilegedTestDatabase.createUser("s27_admin", "ADMIN");
        UUID target = PrivilegedTestDatabase.createUser("s27_target", "USER");
        UUID reporter = PrivilegedTestDatabase.createUser("s27_rep", "USER");
        UUID bizUser = PrivilegedTestDatabase.createUser("s27_biz", "USER");
        UUID place = seedPlace("Local S27");
        UUID biz = seedBusiness(bizUser, "PENDING");
        UUID claim = seedPlaceClaim(place, biz);
        UUID review = seedReview(target, place, "ACTIVE");
        UUID discussion = seedDiscussion(review, target, "ACTIVE");
        UUID mediaId = seedPlaceMedia(place, target, "ACTIVE", null, null);
        UUID reportId = seedPlaceMediaReport(mediaId, reporter);

        // Sem SET ROLE
        assertThrows(DataAccessException.class, () -> media().execute("SET ROLE rewit_mod"));
        assertThrows(DataAccessException.class, () -> media().execute("SET ROLE rewit_app"));

        // Sem escrita nas 5 trilhas exclusivamente administrativas
        assertThrows(DataAccessException.class, () -> media().update(
                "INSERT INTO moderation_audit_logs (review_id, moderator_user_id, action, decision, reason_code, justification, previous_review_status, new_review_status, reports_affected_count, authenticity_mac) "
                        + "VALUES (?, ?, 'REMOVE_REVIEW', 'REMOVE', 'SPAM', 'forged', 'ACTIVE', 'REMOVED', 0, ?)",
                review, admin, BOGUS_WELL_FORMED_MAC));
        assertThrows(DataAccessException.class, () -> media().update(
                "INSERT INTO discussion_moderation_audit_logs (discussion_id, moderator_user_id, action, decision, reason_code, justification, previous_status, new_status, reports_affected_count, authenticity_mac) "
                        + "VALUES (?, ?, 'REMOVE_DISCUSSION', 'REMOVE', 'SPAM', 'forged', 'ACTIVE', 'REMOVED', 0, ?)",
                discussion, admin, BOGUS_WELL_FORMED_MAC));
        assertThrows(DataAccessException.class, () -> media().update(
                "INSERT INTO account_moderation_audit_logs (target_user_id, actor_user_id, action, previous_status, new_status, reason, authenticity_mac) "
                        + "VALUES (?, ?, 'SUSPEND', 'ACTIVE', 'SUSPENDED', 'forged', ?)",
                target, admin, BOGUS_WELL_FORMED_MAC));
        assertThrows(DataAccessException.class, () -> media().update(
                "INSERT INTO place_claim_moderation_audit_logs (claim_id, place_id, business_account_id, moderator_user_id, action, previous_status, new_status, decision_reason, authenticity_mac) "
                        + "VALUES (?, ?, ?, ?, 'APPROVE_CLAIM', 'PENDING', 'APPROVED', 'forged', ?)",
                claim, place, biz, admin, BOGUS_WELL_FORMED_MAC));

        // Sem ações administrativas em place_media_moderation_audit_logs
        for (String adminAction : List.of("REMOVE", "RESTORE", "RELEASE_REVIEW")) {
            assertThrows(DataAccessException.class, () -> media().update(
                    "INSERT INTO place_media_moderation_audit_logs (place_media_id, place_id, actor_user_id, actor_type, action, reason, authenticity_mac) "
                            + "VALUES (?, ?, ?, 'USER', ?, 'forged', ?)",
                    mediaId, place, admin, adminAction, BOGUS_WELL_FORMED_MAC));
        }
        for (String reportAction : List.of("ACCEPT_REPORT", "REJECT_REPORT")) {
            assertThrows(DataAccessException.class, () -> media().update(
                    "INSERT INTO place_media_moderation_audit_logs (place_media_id, place_id, report_id, actor_user_id, actor_type, action, reason, authenticity_mac) "
                            + "VALUES (?, ?, ?, ?, 'USER', ?, 'forged', ?)",
                    mediaId, place, reportId, admin, reportAction, BOGUS_WELL_FORMED_MAC));
        }

        // Sem aprovar/rejeitar denúncias ou restaurar/remover como moderador
        assertThrows(DataAccessException.class, () -> media().update(
                "UPDATE place_media_reports SET status = 'ACCEPTED', decided_by_user_id = ?, decided_at = NOW(), authenticity_mac = ? WHERE id = ?",
                admin, BOGUS_WELL_FORMED_MAC, reportId));
        assertThrows(DataAccessException.class, () -> media().update(
                "UPDATE place_media SET status = 'REMOVED', removal_source = 'MODERATOR', removed_at = NOW() WHERE id = ?",
                mediaId));

        // Sem mutar contas, locais, empresas ou claims
        assertThrows(DataAccessException.class, () -> media().update(
                "UPDATE users SET account_status = 'SUSPENDED' WHERE id = ?", target));
        assertThrows(DataAccessException.class, () -> media().update(
                "UPDATE places SET name = 'Hijacked' WHERE id = ?", place));
        assertThrows(DataAccessException.class, () -> media().update(
                "UPDATE business_accounts SET verification_status = 'APPROVED' WHERE id = ?", biz));
        assertThrows(DataAccessException.class, () -> media().update(
                "UPDATE place_claim_requests SET status = 'APPROVED', decided_by_user_id = ? WHERE id = ?", admin, claim));

        // Sem EXECUTE nas funções de sessão/assertion
        assertThrows(DataAccessException.class, () -> media().queryForObject(
                "SELECT public.rewit_purge_expired_auth_sessions(NOW(), 10)", Integer.class));
        assertThrows(DataAccessException.class, () -> media().execute(
                "SELECT public.rewit_assert_runtime_privileges()"));

        // Integridade bidirecional também vale para rewit_media: transição de place_media sem audit log falha no COMMIT
        assertThrows(SQLException.class, () -> {
            try (Connection conn = mediaConn()) {
                conn.setAutoCommit(false);
                execute(conn, "UPDATE place_media SET status = 'REMOVED', removal_source = 'AUTHOR', removed_at = NOW(), is_primary = false WHERE id = ?", mediaId);
                conn.commit();
            }
        });
        assertEquals("ACTIVE", owner().queryForObject("SELECT status FROM place_media WHERE id = ?", String.class, mediaId));
    }

    @Test
    @DisplayName("Cenário 28 (SEC-F2-03/GC): rewit_media trava a quarentena mas não altera dados; liberação restrita a places/ CONFIRMED_ORPHAN")
    void scenario28_mediaRoleQuarantineLockOnlyAndNamespaceRestrictedRelease() throws SQLException {
        String suffix = UUID.randomUUID().toString();
        UUID placeEntry = seedQuarantine("places/" + UUID.randomUUID() + "/" + suffix + ".jpg", "CONFIRMED_ORPHAN");
        UUID placeObserved = seedQuarantine("places/" + UUID.randomUUID() + "/" + suffix + "-obs.jpg", "OBSERVED");
        UUID reviewEntry = seedQuarantine("reviews/" + UUID.randomUUID() + "/" + suffix + ".jpg", "CONFIRMED_ORPHAN");
        UUID foreignEntry = seedQuarantine("other/" + suffix + ".jpg", "CONFIRMED_ORPHAN");
        UUID trickEntry = seedQuarantine("placesX/" + suffix + ".jpg", "CONFIRMED_ORPHAN");
        Map<String, Object> before = owner().queryForMap("SELECT * FROM storage_object_quarantine WHERE id = ?", placeEntry);
        String placeKey = (String) before.get("object_key");
        Timestamp firstObserved = (Timestamp) before.get("first_observed_at");

        // --- Matriz de privilégios efetiva
        assertTrue(media().queryForObject(
                "SELECT has_column_privilege('rewit_media', 'public.storage_object_quarantine', 'id', 'UPDATE')", Boolean.class));
        for (String col : List.of("object_key", "status", "first_observed_at", "last_observed_at", "last_modified_at", "confirmed_at")) {
            assertFalse(media().queryForObject(
                    "SELECT has_column_privilege('rewit_media', 'public.storage_object_quarantine', ?, 'UPDATE')", Boolean.class, col), col);
        }
        assertFalse(media().queryForObject(
                "SELECT has_table_privilege('rewit_media', 'public.storage_object_quarantine', 'DELETE')", Boolean.class));
        assertFalse(media().queryForObject(
                "SELECT has_table_privilege('rewit_media', 'public.storage_object_quarantine', 'INSERT')", Boolean.class));
        for (String role : List.of("public", "rewit_app", "rewit_mod")) {
            assertFalse(owner().queryForObject(
                    "SELECT has_function_privilege(?, 'public.rewit_release_place_quarantine_entry(uuid)', 'EXECUTE')", Boolean.class, role), role);
        }
        assertTrue(owner().queryForObject(
                "SELECT has_function_privilege('rewit_media', 'public.rewit_release_place_quarantine_entry(uuid)', 'EXECUTE')", Boolean.class));

        // --- UPDATE: lock funciona (mesma consulta de lockConfirmedEntry), mutação não
        try (Connection conn = mediaConn()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id FROM storage_object_quarantine WHERE object_key = ? AND first_observed_at = ? AND status = 'CONFIRMED_ORPHAN' FOR UPDATE")) {
                ps.setString(1, placeKey);
                ps.setTimestamp(2, firstObserved);
                try (var rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(placeEntry, rs.getObject(1, UUID.class));
                }
            }
            conn.rollback();
        }
        // Lock de review (lockEntry também) funciona sob rewit_media
        assertEquals(reviewEntry, media().queryForObject(
                "SELECT id FROM storage_object_quarantine WHERE id = ? FOR UPDATE", UUID.class, reviewEntry));

        UUID newId = UUID.randomUUID();
        for (String attack : List.of(
                "UPDATE storage_object_quarantine SET object_key = 'reviews/hijack.jpg' WHERE id = ?",
                "UPDATE storage_object_quarantine SET status = 'OBSERVED', confirmed_at = NULL WHERE id = ?",
                "UPDATE storage_object_quarantine SET first_observed_at = first_observed_at - INTERVAL '30 days' WHERE id = ?",
                "UPDATE storage_object_quarantine SET confirmed_at = confirmed_at - INTERVAL '30 days' WHERE id = ?",
                "UPDATE storage_object_quarantine SET id = id WHERE id = ?",
                "UPDATE storage_object_quarantine SET id = '" + newId + "' WHERE id = ?")) {
            assertThrows(DataAccessException.class, () -> media().update(attack, placeEntry), attack);
        }
        assertThrows(DataAccessException.class, () -> media().update(
                "INSERT INTO storage_object_quarantine (object_key, status, first_observed_at, last_observed_at) VALUES ('places/x.jpg', 'CONFIRMED_ORPHAN', NOW(), NOW())"));
        assertEquals(before, owner().queryForMap("SELECT * FROM storage_object_quarantine WHERE id = ?", placeEntry));
        assertEquals(0, owner().queryForObject("SELECT COUNT(*) FROM storage_object_quarantine WHERE id = ?", Integer.class, newId));

        // --- DELETE direto falha para qualquer namespace
        for (UUID id : List.of(placeEntry, reviewEntry, foreignEntry)) {
            assertThrows(DataAccessException.class, () -> media().update("DELETE FROM storage_object_quarantine WHERE id = ?", id));
        }

        // --- Liberação restrita: reviews/, namespace desconhecido, prefixo enganoso e estado incompatível falham
        for (UUID id : List.of(reviewEntry, foreignEntry, trickEntry, placeObserved)) {
            assertThrows(DataAccessException.class, () -> media().queryForObject(
                    "SELECT public.rewit_release_place_quarantine_entry(?)", Boolean.class, id), id.toString());
        }
        assertEquals(4, owner().queryForObject("SELECT COUNT(*) FROM storage_object_quarantine WHERE id IN (?, ?, ?, ?)",
                Integer.class, reviewEntry, foreignEntry, trickEntry, placeObserved));

        // rewit_app e rewit_mod não executam a função (sem caminho indireto)
        assertThrows(DataAccessException.class, () -> app().queryForObject(
                "SELECT public.rewit_release_place_quarantine_entry(?)", Boolean.class, placeEntry));
        assertThrows(DataAccessException.class, () -> mod().queryForObject(
                "SELECT public.rewit_release_place_quarantine_entry(?)", Boolean.class, placeEntry));
        // Nenhuma outra função SECURITY DEFINER executável por rewit_media além das auditadas
        assertEquals(List.of("rewit_auto_resolve_removed_place_media_reports", "rewit_release_place_quarantine_entry"),
                owner().queryForList("SELECT p.proname::text FROM pg_proc p JOIN pg_namespace n ON n.oid = p.pronamespace "
                        + "WHERE n.nspname = 'public' AND p.prosecdef AND has_function_privilege('rewit_media', p.oid, 'EXECUTE') ORDER BY 1",
                        String.class));

        // Liberação válida de places/ CONFIRMED_ORPHAN funciona e é idempotente
        assertEquals(Boolean.TRUE, media().queryForObject("SELECT public.rewit_release_place_quarantine_entry(?)", Boolean.class, placeEntry));
        assertEquals(0, owner().queryForObject("SELECT COUNT(*) FROM storage_object_quarantine WHERE id = ?", Integer.class, placeEntry));
        assertEquals(Boolean.FALSE, media().queryForObject("SELECT public.rewit_release_place_quarantine_entry(?)", Boolean.class, placeEntry));

        // --- rewit_app: mantém o GC de reviews/ (lock, confirmação e liberação); também alcança places/ por
        // necessidade dos fluxos de observação/recheck que rodam sob rewit_app (documentado como risco residual)
        assertEquals(1, app().update("UPDATE storage_object_quarantine SET last_observed_at = last_observed_at WHERE id = ?", reviewEntry));
        assertEquals(1, app().update("DELETE FROM storage_object_quarantine WHERE id = ?", reviewEntry));
        assertTrue(app().queryForObject(
                "SELECT has_table_privilege('rewit_app', 'public.storage_object_quarantine', 'DELETE')", Boolean.class));

        owner().update("DELETE FROM storage_object_quarantine WHERE id IN (?, ?, ?)", foreignEntry, trickEntry, placeObserved);
    }

    private static UUID seedQuarantine(String objectKey, String status) {
        return owner().queryForObject(
                "INSERT INTO storage_object_quarantine (object_key, status, first_observed_at, last_observed_at, confirmed_at) "
                        + "VALUES (?, ?, NOW() - INTERVAL '2 days', NOW() - INTERVAL '1 day', "
                        + "CASE WHEN ? = 'CONFIRMED_ORPHAN' THEN NOW() - INTERVAL '1 day' END) RETURNING id",
                UUID.class, objectKey, status, status);
    }

    private static UUID seedQuarantineWithTimestamps(String objectKey, String status, Instant firstObserved, Instant confirmedAt) {
        return owner().queryForObject(
                "INSERT INTO storage_object_quarantine (object_key, status, first_observed_at, last_observed_at, confirmed_at) "
                        + "VALUES (?, ?, ?, ?, ?) RETURNING id",
                UUID.class, objectKey, status, Timestamp.from(firstObserved), Timestamp.from(firstObserved),
                confirmedAt != null ? Timestamp.from(confirmedAt) : null);
    }

    @Test
    @DisplayName("Cenário 29 (SEC-F2-04): integridade da quarentena, proteção contra retroatividade e expurgo físico seguro de órfãos")
    void scenario29_storageQuarantineIntegrityAndGraceHardening() {
        // --- 1. Matriz de privilégios SQL efetiva de rewit_app em storage_object_quarantine
        assertTrue(app().queryForObject(
                "SELECT has_table_privilege('rewit_app', 'public.storage_object_quarantine', 'SELECT')", Boolean.class));
        assertTrue(app().queryForObject(
                "SELECT has_table_privilege('rewit_app', 'public.storage_object_quarantine', 'DELETE')", Boolean.class));
        assertFalse(app().queryForObject(
                "SELECT has_table_privilege('rewit_app', 'public.storage_object_quarantine', 'UPDATE')", Boolean.class),
                "UPDATE geral no nível de tabela deve estar revogado de rewit_app");

        // Colunas permitidas para INSERT por rewit_app:
        for (String col : List.of("id", "object_key", "status", "first_observed_at", "last_observed_at", "last_modified_at")) {
            assertTrue(app().queryForObject(
                    "SELECT has_column_privilege('rewit_app', 'public.storage_object_quarantine', ?, 'INSERT')", Boolean.class, col), col);
        }
        // confirmed_at NÃO pode ser inserido por rewit_app:
        assertFalse(app().queryForObject(
                "SELECT has_column_privilege('rewit_app', 'public.storage_object_quarantine', 'confirmed_at', 'INSERT')", Boolean.class));

        // Colunas permitidas para UPDATE por rewit_app (apenas re-observação; sem UPDATE(id) em SEC-F2-04-R1):
        for (String col : List.of("last_observed_at", "last_modified_at")) {
            assertTrue(app().queryForObject(
                    "SELECT has_column_privilege('rewit_app', 'public.storage_object_quarantine', ?, 'UPDATE')", Boolean.class, col), col);
        }
        // Colunas estritamente PROIBIDAS para UPDATE por rewit_app (incluindo id):
        for (String col : List.of("id", "status", "confirmed_at", "first_observed_at", "object_key")) {
            assertFalse(app().queryForObject(
                    "SELECT has_column_privilege('rewit_app', 'public.storage_object_quarantine', ?, 'UPDATE')", Boolean.class, col), col);
        }

        // Função de confirmação restrita a rewit_app (e owner):
        assertTrue(owner().queryForObject(
                "SELECT has_function_privilege('rewit_app', 'public.rewit_confirm_storage_quarantine_entry(uuid)', 'EXECUTE')", Boolean.class));
        assertFalse(owner().queryForObject(
                "SELECT has_function_privilege('rewit_media', 'public.rewit_confirm_storage_quarantine_entry(uuid)', 'EXECUTE')", Boolean.class));
        assertFalse(owner().queryForObject(
                "SELECT has_function_privilege('rewit_mod', 'public.rewit_confirm_storage_quarantine_entry(uuid)', 'EXECUTE')", Boolean.class));
        assertFalse(owner().queryForObject(
                "SELECT has_function_privilege('public', 'public.rewit_confirm_storage_quarantine_entry(uuid)', 'EXECUTE')", Boolean.class));

        // --- 2. Inserção direta artificial por rewit_app é bloqueada ou neutralizada
        String keyConf = "reviews/" + UUID.randomUUID() + "/conf.jpg";
        assertThrows(DataAccessException.class, () -> app().update(
                "INSERT INTO storage_object_quarantine (object_key, status) VALUES (?, 'CONFIRMED_ORPHAN')", keyConf));

        String keyWithConfirmed = "reviews/" + UUID.randomUUID() + "/with_confirmed.jpg";
        assertThrows(DataAccessException.class, () -> app().update(
                "INSERT INTO storage_object_quarantine (object_key, confirmed_at) VALUES (?, NOW())", keyWithConfirmed));

        // Inserção com timestamp retroativo é neutralizada pelo trigger (first_observed_at vira clock_timestamp())
        String keyRetro = "reviews/" + UUID.randomUUID() + "/retro.jpg";
        app().update("INSERT INTO storage_object_quarantine (object_key, first_observed_at) VALUES (?, NOW() - INTERVAL '48 hours')", keyRetro);
        Map<String, Object> retroRow = owner().queryForMap("SELECT * FROM storage_object_quarantine WHERE object_key = ?", keyRetro);
        assertEquals("OBSERVED", retroRow.get("status"));
        assertNull(retroRow.get("confirmed_at"));
        Timestamp retroFirst = (Timestamp) retroRow.get("first_observed_at");
        assertTrue(retroFirst.toInstant().isAfter(Instant.now().minus(Duration.ofMinutes(5))),
                "first_observed_at retroativo deve ter sido normalizado com clock_timestamp() pelo trigger");

        // Inserção com timestamp futuro é neutralizada pelo trigger
        String keyFuture = "reviews/" + UUID.randomUUID() + "/future.jpg";
        app().update("INSERT INTO storage_object_quarantine (object_key, first_observed_at) VALUES (?, NOW() + INTERVAL '48 hours')", keyFuture);
        Map<String, Object> futureRow = owner().queryForMap("SELECT * FROM storage_object_quarantine WHERE object_key = ?", keyFuture);
        Timestamp futureFirst = (Timestamp) futureRow.get("first_observed_at");
        assertTrue(futureFirst.toInstant().isBefore(Instant.now().plus(Duration.ofMinutes(5))),
                "first_observed_at futuro deve ter sido normalizado com clock_timestamp() pelo trigger");

        // --- 3. Sequência de duas operações (INSERT OBSERVED + UPDATE imediato) é bloqueada
        String keyTwoOps = "reviews/" + UUID.randomUUID() + "/two_ops.jpg";
        app().update("INSERT INTO storage_object_quarantine (object_key) VALUES (?)", keyTwoOps);
        UUID twoOpsId = owner().queryForObject("SELECT id FROM storage_object_quarantine WHERE object_key = ?", UUID.class, keyTwoOps);

        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE storage_object_quarantine SET id = gen_random_uuid() WHERE id = ?", twoOpsId));
        assertThrows(DataAccessException.class, () -> media().update(
                "UPDATE storage_object_quarantine SET id = gen_random_uuid() WHERE id = ?", twoOpsId));
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE storage_object_quarantine SET status = 'CONFIRMED_ORPHAN' WHERE id = ?", twoOpsId));
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE storage_object_quarantine SET confirmed_at = NOW() WHERE id = ?", twoOpsId));
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE storage_object_quarantine SET first_observed_at = NOW() - INTERVAL '48 hours' WHERE id = ?", twoOpsId));
        assertThrows(DataAccessException.class, () -> app().update(
                "UPDATE storage_object_quarantine SET object_key = 'places/hijacked.jpg' WHERE id = ?", twoOpsId));

        // --- 4. Confirmação controlada via função SECURITY DEFINER
        // 4a. Antes de 24 horas: rejeitada com insufficient_privilege
        assertThrows(DataAccessException.class, () -> app().queryForObject(
                "SELECT public.rewit_confirm_storage_quarantine_entry(?)", Boolean.class, twoOpsId));

        // 4b. Role não autorizada (rewit_media e rewit_mod): rejeitadas
        assertThrows(DataAccessException.class, () -> media().queryForObject(
                "SELECT public.rewit_confirm_storage_quarantine_entry(?)", Boolean.class, twoOpsId));
        assertThrows(DataAccessException.class, () -> mod().queryForObject(
                "SELECT public.rewit_confirm_storage_quarantine_entry(?)", Boolean.class, twoOpsId));

        // 4c. UUID nulo e inexistente
        assertThrows(DataAccessException.class, () -> app().queryForObject(
                "SELECT public.rewit_confirm_storage_quarantine_entry(?)", Boolean.class, (UUID) null));
        assertEquals(Boolean.FALSE, app().queryForObject(
                "SELECT public.rewit_confirm_storage_quarantine_entry(?)", Boolean.class, UUID.randomUUID()));

        // 4d. Timestamp futuro inconsistente
        String keyFutureSeed = "reviews/" + UUID.randomUUID() + "/future_seed.jpg";
        UUID futureSeedId = seedQuarantineWithTimestamps(keyFutureSeed, "OBSERVED", Instant.now().plus(Duration.ofHours(2)), null);
        assertThrows(DataAccessException.class, () -> app().queryForObject(
                "SELECT public.rewit_confirm_storage_quarantine_entry(?)", Boolean.class, futureSeedId));

        // 4e. Confirmação autorizada após 24 horas: permitida, atômica e idempotente
        String keyValidGrace = "reviews/" + UUID.randomUUID() + "/valid_grace.jpg";
        UUID validGraceId = seedQuarantineWithTimestamps(keyValidGrace, "OBSERVED", Instant.now().minus(Duration.ofHours(25)), null);
        assertEquals(Boolean.TRUE, app().queryForObject(
                "SELECT public.rewit_confirm_storage_quarantine_entry(?)", Boolean.class, validGraceId));
        Map<String, Object> confirmedRow = owner().queryForMap(
                "SELECT status, confirmed_at FROM storage_object_quarantine WHERE id = ?", validGraceId);
        assertEquals("CONFIRMED_ORPHAN", confirmedRow.get("status"));
        assertNotNull(confirmedRow.get("confirmed_at"));
        // Idempotência
        assertEquals(Boolean.TRUE, app().queryForObject(
                "SELECT public.rewit_confirm_storage_quarantine_entry(?)", Boolean.class, validGraceId));

        // --- 5. Upsert legítimo e SELECT FOR UPDATE (rewit_app sem UPDATE(id) e rewit_media com UPDATE(id))
        assertEquals(twoOpsId, app().queryForObject(
                "SELECT id FROM storage_object_quarantine WHERE id = ? FOR UPDATE", UUID.class, twoOpsId));
        assertEquals(twoOpsId, media().queryForObject(
                "SELECT id FROM storage_object_quarantine WHERE id = ? FOR UPDATE", UUID.class, twoOpsId));
        quarantineRepository.recordObservations(List.of(new OrphanCandidate(keyTwoOps, 100, Instant.now(), Instant.now())));
        Map<String, Object> rowAfterUpsert = owner().queryForMap("SELECT * FROM storage_object_quarantine WHERE id = ?", twoOpsId);
        assertEquals(keyTwoOps, rowAfterUpsert.get("object_key"));
        assertEquals("OBSERVED", rowAfterUpsert.get("status"));
        assertNotNull(rowAfterUpsert.get("last_modified_at"));

        // --- 6. Validação do expurgo físico no storage SeaweedFS/S3 para ambos os namespaces
        UUID author = PrivilegedTestDatabase.createUser("s29_author", "USER");
        UUID place = seedPlace("Place S29");
        UUID reviewId = seedReview(author, place, "ACTIVE");
        PurgeConfirmedOrphanStorageObjectUseCase purgeUseCase = new PurgeConfirmedOrphanStorageObjectUseCase(quarantineRepository, objectStoragePort);

        // 6a. reviews/: órfão com < 24h não é excluído fisicamente
        String orphanReviewKey = "reviews/" + reviewId + "/" + UUID.randomUUID() + "/image.jpg";
        objectStoragePort.put(orphanReviewKey, "image/jpeg", "test-orphan-data".getBytes());
        assertTrue(objectStoragePort.exists(orphanReviewKey));
        UUID orphanReviewId = seedQuarantineWithTimestamps(orphanReviewKey, "CONFIRMED_ORPHAN", Instant.now().minus(Duration.ofHours(2)), Instant.now());
        assertThrows(IllegalStateException.class, () -> purgeUseCase.purge(orphanReviewKey));
        assertTrue(objectStoragePort.exists(orphanReviewKey), "Objeto de review < 24h deve ser preservado no storage");

        // 6b. reviews/: órfão com > 24h é excluído fisicamente
        owner().update("UPDATE storage_object_quarantine SET first_observed_at = NOW() - INTERVAL '25 hours' WHERE id = ?", orphanReviewId);
        StoragePurgeResult purgeRevResult = purgeUseCase.purge(orphanReviewKey);
        assertEquals(StoragePurgeOutcome.PURGED, purgeRevResult.outcome());
        assertFalse(objectStoragePort.exists(orphanReviewKey), "Objeto de review > 24h deve ser fisicamente excluído do storage");
        assertEquals(0, owner().queryForObject("SELECT COUNT(*) FROM storage_object_quarantine WHERE id = ?", Integer.class, orphanReviewId));

        // 6c. places/: órfão com < 24h não é excluído fisicamente
        String orphanPlaceKey = "places/" + place + "/" + UUID.randomUUID() + "/image.jpg";
        objectStoragePort.put(orphanPlaceKey, "image/jpeg", "test-place-orphan".getBytes());
        assertTrue(objectStoragePort.exists(orphanPlaceKey));
        UUID orphanPlaceId = seedQuarantineWithTimestamps(orphanPlaceKey, "CONFIRMED_ORPHAN", Instant.now().minus(Duration.ofHours(2)), Instant.now());
        assertThrows(IllegalStateException.class, () -> purgeUseCase.purge(orphanPlaceKey));
        assertTrue(objectStoragePort.exists(orphanPlaceKey), "Objeto de place < 24h deve ser preservado no storage");

        // 6d. places/: órfão com > 24h é excluído fisicamente (roteado por rewit_media e rewit_release_place_quarantine_entry)
        owner().update("UPDATE storage_object_quarantine SET first_observed_at = NOW() - INTERVAL '25 hours' WHERE id = ?", orphanPlaceId);
        StoragePurgeResult purgePlaceResult = purgeUseCase.purge(orphanPlaceKey);
        assertEquals(StoragePurgeOutcome.PURGED, purgePlaceResult.outcome());
        assertFalse(objectStoragePort.exists(orphanPlaceKey), "Objeto de place > 24h deve ser fisicamente excluído do storage");
        assertEquals(0, owner().queryForObject("SELECT COUNT(*) FROM storage_object_quarantine WHERE id = ?", Integer.class, orphanPlaceId));

        // 6e. Mídia ativa de review: protegida por referência relacional
        UUID activeReviewMediaId = UUID.randomUUID();
        String activeReviewKey = "reviews/" + reviewId + "/" + activeReviewMediaId + "/image.jpg";
        owner().update("INSERT INTO review_media (id, review_id, user_id, object_key, media_type, mime_type, size_bytes, width, height, status) "
                        + "VALUES (?, ?, ?, ?, 'IMAGE', 'image/jpeg', 1024, 640, 480, 'ACTIVE')",
                activeReviewMediaId, reviewId, author, activeReviewKey);
        objectStoragePort.put(activeReviewKey, "image/jpeg", "active-rev-data".getBytes());
        UUID activeRevQuarantineId = seedQuarantineWithTimestamps(activeReviewKey, "CONFIRMED_ORPHAN", Instant.now().minus(Duration.ofHours(25)), Instant.now());
        StoragePurgeResult activeRevPurge = purgeUseCase.purge(activeReviewKey);
        assertEquals(StoragePurgeOutcome.WITH_REFERENCE, activeRevPurge.outcome());
        assertTrue(objectStoragePort.exists(activeReviewKey), "Mídia ativa de review deve permanecer no storage");
        assertEquals(0, owner().queryForObject("SELECT COUNT(*) FROM storage_object_quarantine WHERE id = ?", Integer.class, activeRevQuarantineId));

        // 6f. Mídia ativa de place: protegida por referência relacional
        UUID activePlaceMediaId = UUID.randomUUID();
        String activePlaceKey = "places/" + place + "/" + activePlaceMediaId + "/image.jpg";
        owner().update("INSERT INTO place_media (id, place_id, user_id, object_key, media_type, mime_type, size_bytes, width, height, status) "
                        + "VALUES (?, ?, ?, ?, 'IMAGE', 'image/jpeg', 2048, 800, 600, 'ACTIVE')",
                activePlaceMediaId, place, author, activePlaceKey);
        objectStoragePort.put(activePlaceKey, "image/jpeg", "active-place-data".getBytes());
        UUID activePlaceQuarantineId = seedQuarantineWithTimestamps(activePlaceKey, "CONFIRMED_ORPHAN", Instant.now().minus(Duration.ofHours(25)), Instant.now());
        StoragePurgeResult activePlacePurge = purgeUseCase.purge(activePlaceKey);
        assertEquals(StoragePurgeOutcome.WITH_REFERENCE, activePlacePurge.outcome());
        assertTrue(objectStoragePort.exists(activePlaceKey), "Mídia ativa de place deve permanecer no storage");
        assertEquals(0, owner().queryForObject("SELECT COUNT(*) FROM storage_object_quarantine WHERE id = ?", Integer.class, activePlaceQuarantineId));

        // 6g. Mídia de place removida há menos de 24h (ADR-015): protegida contra expurgo
        UUID recentRemovedMediaId = UUID.randomUUID();
        String recentRemovedKey = "places/" + place + "/" + recentRemovedMediaId + "/image.jpg";
        owner().update("INSERT INTO place_media (id, place_id, user_id, object_key, media_type, mime_type, size_bytes, width, height, status, removal_source, removed_at) "
                        + "VALUES (?, ?, ?, ?, 'IMAGE', 'image/jpeg', 2048, 800, 600, 'REMOVED', 'AUTHOR', ?)",
                recentRemovedMediaId, place, author, recentRemovedKey, Timestamp.from(Instant.now().minus(Duration.ofHours(2))));
        objectStoragePort.put(recentRemovedKey, "image/jpeg", "recent-removed-data".getBytes());
        UUID recentRemovedQuarantineId = seedQuarantineWithTimestamps(recentRemovedKey, "CONFIRMED_ORPHAN", Instant.now().minus(Duration.ofHours(25)), Instant.now());
        StoragePurgeResult recentRemovedPurge = purgeUseCase.purge(recentRemovedKey);
        assertEquals(StoragePurgeOutcome.WITH_REFERENCE, recentRemovedPurge.outcome());
        assertTrue(objectStoragePort.exists(recentRemovedKey), "Mídia de place removida < 24h deve ser preservada (ADR-015)");

        // 6h. SEC-F2-04-ARCH-01: JVM adiantada (+30h) NÃO consegue provocar expurgo antecipado em reviews/ nem places/
        Clock aheadClock = Clock.fixed(Instant.now().plus(Duration.ofHours(30)), ZoneOffset.UTC);
        PurgeConfirmedOrphanStorageObjectUseCase aheadPurgeUseCase =
                new PurgeConfirmedOrphanStorageObjectUseCase(quarantineRepository, objectStoragePort, aheadClock);

        String aheadRevKey = "reviews/" + reviewId + "/" + UUID.randomUUID() + "/image.jpg";
        objectStoragePort.put(aheadRevKey, "image/jpeg", "ahead-rev-data".getBytes());
        UUID aheadRevId = seedQuarantineWithTimestamps(aheadRevKey, "CONFIRMED_ORPHAN", Instant.now().minus(Duration.ofHours(2)), Instant.now());
        IllegalStateException aheadRevEx = assertThrows(IllegalStateException.class, () -> aheadPurgeUseCase.purge(aheadRevKey));
        assertTrue(aheadRevEx.getMessage().contains("PostgreSQL"), "Bloqueio deve vir da autoridade temporal do PostgreSQL: " + aheadRevEx.getMessage());
        assertTrue(objectStoragePort.exists(aheadRevKey), "JVM adiantada não pode provocar exclusão física em reviews/");

        String aheadPlaceKey = "places/" + place + "/" + UUID.randomUUID() + "/image.jpg";
        objectStoragePort.put(aheadPlaceKey, "image/jpeg", "ahead-place-data".getBytes());
        UUID aheadPlaceId = seedQuarantineWithTimestamps(aheadPlaceKey, "CONFIRMED_ORPHAN", Instant.now().minus(Duration.ofHours(2)), Instant.now());
        IllegalStateException aheadPlaceEx = assertThrows(IllegalStateException.class, () -> aheadPurgeUseCase.purge(aheadPlaceKey));
        assertTrue(aheadPlaceEx.getMessage().contains("PostgreSQL"), "Bloqueio deve vir da autoridade temporal do PostgreSQL: " + aheadPlaceEx.getMessage());
        assertTrue(objectStoragePort.exists(aheadPlaceKey), "JVM adiantada não pode provocar exclusão física em places/");

        // 6i. SEC-F2-04-ARCH-01: JVM atrasada (-5h) preserva segurança (atraso aceitável até sincronização)
        Clock behindClock = Clock.fixed(Instant.now().minus(Duration.ofHours(5)), ZoneOffset.UTC);
        PurgeConfirmedOrphanStorageObjectUseCase behindPurgeUseCase =
                new PurgeConfirmedOrphanStorageObjectUseCase(quarantineRepository, objectStoragePort, behindClock);
        String behindRevKey = "reviews/" + reviewId + "/" + UUID.randomUUID() + "/image.jpg";
        objectStoragePort.put(behindRevKey, "image/jpeg", "behind-rev-data".getBytes());
        UUID behindRevId = seedQuarantineWithTimestamps(behindRevKey, "CONFIRMED_ORPHAN", Instant.now().minus(Duration.ofHours(25)), Instant.now());
        assertThrows(IllegalStateException.class, () -> behindPurgeUseCase.purge(behindRevKey));
        assertTrue(objectStoragePort.exists(behindRevKey), "JVM atrasada deve preservar segurança sem expurgo indevido");
        // Quando o relógio da JVM alcança o prazo, o expurgo é concluído normalmente
        assertEquals(StoragePurgeOutcome.PURGED, purgeUseCase.purge(behindRevKey).outcome());
        assertFalse(objectStoragePort.exists(behindRevKey));

        // 6j. SEC-F2-04-ARCH-01: confirmed_at inconsistente (< first_observed_at + 24h) bloqueado pelo PostgreSQL em ambos os namespaces
        String badConfRevKey = "reviews/" + reviewId + "/" + UUID.randomUUID() + "/image.jpg";
        objectStoragePort.put(badConfRevKey, "image/jpeg", "bad-conf-rev".getBytes());
        UUID badConfRevId = seedQuarantineWithTimestamps(badConfRevKey, "CONFIRMED_ORPHAN",
                Instant.now().minus(Duration.ofHours(25)), Instant.now().minus(Duration.ofHours(10)));
        IllegalStateException badConfRevEx = assertThrows(IllegalStateException.class, () -> purgeUseCase.purge(badConfRevKey));
        assertTrue(badConfRevEx.getMessage().contains("PostgreSQL"));
        assertTrue(objectStoragePort.exists(badConfRevKey), "confirmed_at < first_observed_at + 24h deve bloquear expurgo em reviews/");

        String badConfPlaceKey = "places/" + place + "/" + UUID.randomUUID() + "/image.jpg";
        objectStoragePort.put(badConfPlaceKey, "image/jpeg", "bad-conf-place".getBytes());
        UUID badConfPlaceId = seedQuarantineWithTimestamps(badConfPlaceKey, "CONFIRMED_ORPHAN",
                Instant.now().minus(Duration.ofHours(25)), Instant.now().minus(Duration.ofHours(10)));
        IllegalStateException badConfPlaceEx = assertThrows(IllegalStateException.class, () -> purgeUseCase.purge(badConfPlaceKey));
        assertTrue(badConfPlaceEx.getMessage().contains("PostgreSQL"));
        assertTrue(objectStoragePort.exists(badConfPlaceKey), "confirmed_at < first_observed_at + 24h deve bloquear expurgo em places/");

        // 6k. SEC-F2-04-ARCH-01: confirmed_at no futuro bloqueado pelo PostgreSQL em ambos os namespaces
        String futureConfRevKey = "reviews/" + reviewId + "/" + UUID.randomUUID() + "/image.jpg";
        objectStoragePort.put(futureConfRevKey, "image/jpeg", "future-conf-rev".getBytes());
        UUID futureConfRevId = seedQuarantineWithTimestamps(futureConfRevKey, "CONFIRMED_ORPHAN",
                Instant.now().minus(Duration.ofHours(25)), Instant.now().plus(Duration.ofHours(2)));
        IllegalStateException futureConfRevEx = assertThrows(IllegalStateException.class, () -> purgeUseCase.purge(futureConfRevKey));
        assertTrue(futureConfRevEx.getMessage().contains("PostgreSQL"));
        assertTrue(objectStoragePort.exists(futureConfRevKey), "confirmed_at futuro deve bloquear expurgo em reviews/");

        String futureConfPlaceKey = "places/" + place + "/" + UUID.randomUUID() + "/image.jpg";
        objectStoragePort.put(futureConfPlaceKey, "image/jpeg", "future-conf-place".getBytes());
        UUID futureConfPlaceId = seedQuarantineWithTimestamps(futureConfPlaceKey, "CONFIRMED_ORPHAN",
                Instant.now().minus(Duration.ofHours(25)), Instant.now().plus(Duration.ofHours(2)));
        IllegalStateException futureConfPlaceEx = assertThrows(IllegalStateException.class, () -> purgeUseCase.purge(futureConfPlaceKey));
        assertTrue(futureConfPlaceEx.getMessage().contains("PostgreSQL"));
        assertTrue(objectStoragePort.exists(futureConfPlaceKey), "confirmed_at futuro deve bloquear expurgo em places/");

        // Limpeza dos objetos de teste persistidos
        objectStoragePort.delete(activeReviewKey);
        objectStoragePort.delete(activePlaceKey);
        objectStoragePort.delete(recentRemovedKey);
        objectStoragePort.delete(aheadRevKey);
        objectStoragePort.delete(aheadPlaceKey);
        objectStoragePort.delete(badConfRevKey);
        objectStoragePort.delete(badConfPlaceKey);
        objectStoragePort.delete(futureConfRevKey);
        objectStoragePort.delete(futureConfPlaceKey);
        owner().update("DELETE FROM storage_object_quarantine WHERE id IN (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                retroRow.get("id"), futureRow.get("id"), twoOpsId, futureSeedId, validGraceId, recentRemovedQuarantineId,
                aheadRevId, aheadPlaceId, badConfRevId, badConfPlaceId, futureConfRevId, futureConfPlaceId);
    }

    // --------------------------------------------------------------------------------------- Utilitários

    private void verifyBusinessResponseAudit(UUID auditId) {
        Map<String, Object> row = owner().queryForMap(
                "SELECT id, response_id, moderator_user_id, action, reason_code, justification, previous_status, new_status, created_at, authenticity_mac "
                        + "FROM business_response_moderation_audit_logs WHERE id = ?", auditId);
        assertEquals(AuditVerificationStatus.VERIFIED, macService.verify(
                ModerationAuditPayload.forBusinessResponseModeration(
                        (UUID) row.get("id"), (UUID) row.get("response_id"), (UUID) row.get("moderator_user_id"),
                        (String) row.get("action"), (String) row.get("reason_code"), (String) row.get("justification"),
                        (String) row.get("previous_status"), (String) row.get("new_status"),
                        ((Timestamp) row.get("created_at")).toInstant()),
                (String) row.get("authenticity_mac")));
    }

    private AuditVerificationStatus verifyPlaceMediaAuditRow(Map<String, Object> row) {
        return macService.verify(
                ModerationAuditPayload.forPlaceMediaAudit(
                        (UUID) row.get("id"), (UUID) row.get("place_media_id"), (UUID) row.get("place_id"),
                        (UUID) row.get("report_id"), (UUID) row.get("actor_user_id"), (String) row.get("actor_type"),
                        (String) row.get("action"), (String) row.get("reason"), (String) row.get("notes"),
                        ((Timestamp) row.get("created_at")).toInstant()),
                (String) row.get("authenticity_mac"));
    }

    @FunctionalInterface
    private interface SqlWork {
        void run(Connection conn) throws SQLException;
    }

    private static void assertCommitFailsAsMod(SqlWork work) {
        assertThrows(SQLException.class, () -> {
            try (Connection conn = modConn()) {
                conn.setAutoCommit(false);
                try {
                    work.run(conn);
                    conn.commit();
                } catch (SQLException ex) {
                    try {
                        conn.rollback();
                    } catch (SQLException ignored) {
                    }
                    throw ex;
                }
            }
        });
    }

    private static void execute(Connection conn, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) {
                ps.setObject(i + 1, args[i]);
            }
            ps.executeUpdate();
        }
    }

    private record TestUser(UUID userId, String email, String password, String accessToken) {}

    private TestUser registerUser(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String email = prefix + "." + suffix + "@rewit.test";
        String password = "StrongPassword!123";
        RegisterRequest req = new RegisterRequest(email, password, prefix + "_" + suffix, "User " + suffix);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        return new TestUser(UUID.fromString(json.get("user").get("id").asText()), email, password, json.get("accessToken").asText());
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(email, password))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }
}
