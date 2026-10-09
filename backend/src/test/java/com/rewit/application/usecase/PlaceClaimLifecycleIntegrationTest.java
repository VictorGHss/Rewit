package com.rewit.application.usecase;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.dto.business.BusinessDtos.BusinessAccountView;
import com.rewit.application.dto.business.BusinessDtos.PlaceClaimView;
import com.rewit.application.dto.common.PageResult;
import com.rewit.application.port.PlaceRepository;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.enums.PlaceClaimDecision;
import com.rewit.domain.enums.PlaceClaimStatus;
import com.rewit.domain.enums.VerificationStatus;
import com.rewit.domain.model.Place;
import com.rewit.presentation.dto.auth.RegisterRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Ciclo de vida da reivindicação de locais (C9) com PostgreSQL real: contas comerciais, solicitações, decisão,
 * vínculo atômico do local, corridas e as constraints da V23.
 */
@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Reivindicação de locais (C9): contas comerciais, solicitações e decisão com PostgreSQL real")
class PlaceClaimLifecycleIntegrationTest {

    private static final String EVIDENCE = "Sou o proprietário; contrato social e alvará disponíveis para conferência.";
    private static final String REASON = "Documentação conferida e compatível com o local.";

    @Autowired private WebApplicationContext webApplicationContext;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private PlaceRepository placeRepository;
    @Autowired private CreateBusinessAccountUseCase createBusinessAccount;
    @Autowired private ListMyBusinessAccountsUseCase listMyBusinessAccounts;
    @Autowired private RequestPlaceClaimUseCase requestPlaceClaim;
    @Autowired private ListBusinessPlaceClaimsUseCase listBusinessPlaceClaims;
    @Autowired private QueryAdminPlaceClaimsUseCase queryAdminPlaceClaims;
    @Autowired private DecidePlaceClaimUseCase decidePlaceClaim;

    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(webApplicationContext).build();
    }

    // ---------------------------------------------------------------------------------------------------------
    // Conta comercial
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Conta criada PENDING e FREE para o ator; documento duplicado (outra máscara) é 409; listagem isolada")
    void businessAccountCreation() throws Exception {
        UUID owner = registerUser("biz_owner");
        UUID other = registerUser("biz_other");
        String digits = randomDigits(14);
        String masked = digits.substring(0, 2) + "." + digits.substring(2, 5) + "." + digits.substring(5, 8) + "/"
                + digits.substring(8, 12) + "-" + digits.substring(12);

        BusinessAccountView created = createBusinessAccount.execute(owner, "  Café   do Centro ", masked);

        assertEquals(VerificationStatus.PENDING, created.verificationStatus());
        assertEquals("FREE", created.planTier());
        assertEquals("Café do Centro", created.corporateName());
        assertEquals(digits, created.taxId());
        assertEquals(owner, jdbcTemplate.queryForObject("SELECT user_id FROM business_accounts WHERE id = ?", UUID.class,
                created.id()), "vinculada ao ator");

        BusinessException duplicate = assertThrows(BusinessException.class,
                () -> createBusinessAccount.execute(other, "Outra Empresa", digits));
        assertEquals("BUSINESS_TAX_ID_ALREADY_EXISTS", duplicate.getErrorCode());
        assertEquals(409, duplicate.getStatus().value());

        assertEquals(List.of(created.id()), listMyBusinessAccounts.execute(owner).stream().map(view -> view.id()).toList());
        assertTrue(listMyBusinessAccounts.execute(other).isEmpty(), "só as contas do próprio usuário");
    }

    @Test
    @DisplayName("Conta inativa não cria conta comercial: 401 ACCOUNT_DISABLED")
    void disabledUserCannotCreateAccount() throws Exception {
        UUID user = registerUser("biz_disabled");
        jdbcTemplate.update("UPDATE users SET account_status = 'DEACTIVATED', is_active = FALSE WHERE id = ?", user);

        BusinessException ex = assertThrows(BusinessException.class,
                () -> createBusinessAccount.execute(user, "Empresa Inativa", randomDigits(14)));
        assertEquals("ACCOUNT_DISABLED", ex.getErrorCode());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Solicitação
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Solicitação: só o administrador da conta; local inexistente, indisponível, vinculado ou já pendente")
    void requestRules() throws Exception {
        UUID owner = registerUser("claim_owner");
        UUID stranger = registerUser("claim_stranger");
        BusinessAccountView account = account(owner);
        BusinessAccountView otherAccount = account(registerUser("claim_other_owner"));
        UUID place = place("ACTIVE");

        assertCode("BUSINESS_ACCOUNT_NOT_FOUND", 404,
                () -> requestPlaceClaim.execute(stranger, account.id(), place, EVIDENCE));
        assertCode("BUSINESS_ACCOUNT_NOT_FOUND", 404,
                () -> requestPlaceClaim.execute(owner, UUID.randomUUID(), place, EVIDENCE));
        assertCode("PLACE_NOT_FOUND", 404, () -> requestPlaceClaim.execute(owner, account.id(), UUID.randomUUID(), EVIDENCE));
        assertCode("PLACE_NOT_FOUND", 404, () -> requestPlaceClaim.execute(owner, account.id(), place("CLOSED"), EVIDENCE));
        assertCode("INVALID_EVIDENCE_DESCRIPTION", 400, () -> requestPlaceClaim.execute(owner, account.id(), place, "curta"));

        PlaceClaimView claim = requestPlaceClaim.execute(owner, account.id(), place, "  " + EVIDENCE + "  ");
        assertEquals(PlaceClaimStatus.PENDING, claim.status());
        assertEquals(EVIDENCE, claim.evidenceDescription());
        assertEquals(account.id(), claim.businessAccountId());
        assertEquals(account.taxId(), claim.taxId());
        assertEquals(place, claim.placeId());
        assertEquals("Curitiba", claim.city());
        assertEquals("PR", claim.state());
        assertNull(claim.decidedAt());

        // Uma pendente por local, de qualquer conta
        assertCode("PLACE_CLAIM_ALREADY_PENDING", 409,
                () -> requestPlaceClaim.execute(ownerOf(otherAccount), otherAccount.id(), place, EVIDENCE));

        // A mesma conta pode reivindicar vários locais
        assertEquals(PlaceClaimStatus.PENDING, requestPlaceClaim.execute(owner, account.id(), place("ACTIVE"), EVIDENCE).status());

        // Local já vinculado
        UUID claimedPlace = place("ACTIVE");
        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", otherAccount.id(), claimedPlace);
        assertCode("PLACE_ALREADY_CLAIMED", 409, () -> requestPlaceClaim.execute(owner, account.id(), claimedPlace, EVIDENCE));
    }

    @Test
    @DisplayName("Conta REJECTED não solicita reivindicações")
    void rejectedAccountCannotRequest() throws Exception {
        UUID owner = registerUser("claim_rejected");
        BusinessAccountView account = account(owner);
        jdbcTemplate.update("UPDATE business_accounts SET verification_status = 'REJECTED' WHERE id = ?", account.id());

        assertCode("BUSINESS_ACCOUNT_REJECTED", 409, () -> requestPlaceClaim.execute(owner, account.id(), place("ACTIVE"), EVIDENCE));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Decisão
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Aprovação: vincula o local, decide a solicitação e verifica a conta na mesma transação")
    void approveLinksPlaceAndVerifiesAccount() throws Exception {
        UUID owner = registerUser("approve_owner");
        BusinessAccountView account = account(owner);
        UUID place = place("ACTIVE");
        UUID moderator = moderator("approve_mod");
        PlaceClaimView claim = requestPlaceClaim.execute(owner, account.id(), place, EVIDENCE);
        Instant decidedAt = Instant.parse("2026-10-10T15:00:00Z");

        PlaceClaimView approved = decidePlaceClaim.execute(moderator, claim.id(), PlaceClaimDecision.APPROVE,
                "  " + REASON + " ", decidedAt);

        assertEquals(PlaceClaimStatus.APPROVED, approved.status());
        assertEquals(decidedAt, approved.decidedAt());
        assertEquals(REASON, approved.decisionReason());
        assertEquals(account.id(), jdbcTemplate.queryForObject("SELECT claimed_by_business_id FROM places WHERE id = ?",
                UUID.class, place));
        assertEquals(moderator, jdbcTemplate.queryForObject(
                "SELECT decided_by_user_id FROM place_claim_requests WHERE id = ?", UUID.class, claim.id()));
        assertEquals("APPROVED", jdbcTemplate.queryForObject(
                "SELECT verification_status FROM business_accounts WHERE id = ?", String.class, account.id()));
        assertFalse(jdbcTemplate.queryForObject("SELECT is_verified FROM places WHERE id = ?", Boolean.class, place),
                "is_verified não é alterado sem regra explícita");

        // Segunda aprovação de outro local: a conta já aprovada não é regravada
        Timestamp updatedAt = jdbcTemplate.queryForObject("SELECT updated_at FROM business_accounts WHERE id = ?",
                Timestamp.class, account.id());
        PlaceClaimView second = requestPlaceClaim.execute(owner, account.id(), place("ACTIVE"), EVIDENCE);
        decidePlaceClaim.execute(moderator, second.id(), PlaceClaimDecision.APPROVE, REASON, decidedAt.plusSeconds(60));
        assertEquals(updatedAt, jdbcTemplate.queryForObject("SELECT updated_at FROM business_accounts WHERE id = ?",
                Timestamp.class, account.id()));

        // Decidida não volta a ser decidida
        assertCode("PLACE_CLAIM_ALREADY_DECIDED", 409,
                () -> decidePlaceClaim.execute(moderator, claim.id(), PlaceClaimDecision.REJECT, REASON, Instant.now()));
    }

    @Test
    @DisplayName("Rejeição: decide só a solicitação; conta segue PENDING e o local pode ser reivindicado de novo")
    void rejectKeepsAccountAndFreesPlace() throws Exception {
        UUID owner = registerUser("reject_owner");
        BusinessAccountView account = account(owner);
        UUID place = place("ACTIVE");
        UUID moderator = moderator("reject_mod");
        PlaceClaimView claim = requestPlaceClaim.execute(owner, account.id(), place, EVIDENCE);

        PlaceClaimView rejected = decidePlaceClaim.execute(moderator, claim.id(), PlaceClaimDecision.REJECT,
                "Evidência insuficiente para comprovar a gestão.", Instant.now());

        assertEquals(PlaceClaimStatus.REJECTED, rejected.status());
        assertEquals("Evidência insuficiente para comprovar a gestão.", rejected.decisionReason());
        assertEquals("PENDING", jdbcTemplate.queryForObject("SELECT verification_status FROM business_accounts WHERE id = ?",
                String.class, account.id()));
        assertNull(jdbcTemplate.queryForObject("SELECT claimed_by_business_id FROM places WHERE id = ?", UUID.class, place));

        // Nova solicitação legítima, da mesma conta ou de outra
        assertEquals(PlaceClaimStatus.PENDING, requestPlaceClaim.execute(owner, account.id(), place, EVIDENCE).status());
        assertCode("PLACE_CLAIM_ALREADY_DECIDED", 409,
                () -> decidePlaceClaim.execute(moderator, claim.id(), PlaceClaimDecision.APPROVE, REASON, Instant.now()));
    }

    @Test
    @DisplayName("Decisão: só MODERATOR/ADMIN, nunca o administrador da conta, com justificativa e solicitação existente")
    void decisionAuthorization() throws Exception {
        UUID owner = registerUser("decide_owner");
        BusinessAccountView account = account(owner);
        PlaceClaimView claim = requestPlaceClaim.execute(owner, account.id(), place("ACTIVE"), EVIDENCE);

        assertCode("FORBIDDEN", 403, () -> decidePlaceClaim.execute(registerUser("decide_user"), claim.id(),
                PlaceClaimDecision.APPROVE, REASON, Instant.now()));

        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", owner);
        assertCode("SELF_DECISION_FORBIDDEN", 403,
                () -> decidePlaceClaim.execute(owner, claim.id(), PlaceClaimDecision.APPROVE, REASON, Instant.now()));

        UUID admin = registerUser("decide_admin");
        jdbcTemplate.update("UPDATE users SET role = 'ADMIN' WHERE id = ?", admin);
        assertCode("INVALID_DECISION_REASON", 400,
                () -> decidePlaceClaim.execute(admin, claim.id(), PlaceClaimDecision.APPROVE, "curta", Instant.now()));
        assertCode("PLACE_CLAIM_NOT_FOUND", 404,
                () -> decidePlaceClaim.execute(admin, UUID.randomUUID(), PlaceClaimDecision.APPROVE, REASON, Instant.now()));
        assertEquals("PENDING", jdbcTemplate.queryForObject("SELECT status FROM place_claim_requests WHERE id = ?",
                String.class, claim.id()), "tentativas recusadas não decidem");

        assertEquals(PlaceClaimStatus.APPROVED,
                decidePlaceClaim.execute(admin, claim.id(), PlaceClaimDecision.APPROVE, REASON, Instant.now()).status());
    }

    @Test
    @DisplayName("Pendente sobre local já vinculado (estado legado): aprovação recusa e o vínculo não muda")
    void approvalNeverOverwritesClaimedPlace() throws Exception {
        UUID owner = registerUser("stale_owner");
        BusinessAccountView account = account(owner);
        BusinessAccountView holder = account(registerUser("stale_holder"));
        UUID place = place("ACTIVE");
        PlaceClaimView claim = requestPlaceClaim.execute(owner, account.id(), place, EVIDENCE);
        jdbcTemplate.update("UPDATE places SET claimed_by_business_id = ? WHERE id = ?", holder.id(), place);

        assertCode("PLACE_ALREADY_CLAIMED", 409, () -> decidePlaceClaim.execute(moderator("stale_mod"), claim.id(),
                PlaceClaimDecision.APPROVE, REASON, Instant.now()));

        assertEquals(holder.id(), jdbcTemplate.queryForObject("SELECT claimed_by_business_id FROM places WHERE id = ?",
                UUID.class, place));
        assertEquals("PENDING", jdbcTemplate.queryForObject("SELECT status FROM place_claim_requests WHERE id = ?",
                String.class, claim.id()), "rollback: a solicitação continua pendente");
        assertEquals("PENDING", jdbcTemplate.queryForObject("SELECT verification_status FROM business_accounts WHERE id = ?",
                String.class, account.id()));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Concorrência
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Corrida: contas diferentes solicitam o mesmo local ao mesmo tempo; só uma pendente")
    void concurrentRequestsForSamePlace() throws Exception {
        UUID place = place("ACTIVE");
        List<UUID> owners = new ArrayList<>();
        List<UUID> accounts = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID owner = registerUser("race_req_" + i);
            owners.add(owner);
            accounts.add(account(owner).id());
        }

        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < owners.size(); i++) {
            UUID owner = owners.get(i);
            UUID accountId = accounts.get(i);
            calls.add(() -> requestPlaceClaim.execute(owner, accountId, place, EVIDENCE));
        }
        List<Object> outcomes = runConcurrently(calls);

        assertEquals(1, outcomes.stream().filter(PlaceClaimView.class::isInstance).count(), outcomes.toString());
        for (Object outcome : outcomes) {
            if (outcome instanceof BusinessException ex) {
                assertEquals("PLACE_CLAIM_ALREADY_PENDING", ex.getErrorCode());
            } else {
                assertTrue(outcome instanceof PlaceClaimView, "falha inesperada: " + outcome);
            }
        }
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT count(*) FROM place_claim_requests WHERE place_id = ? AND status = 'PENDING'", Integer.class, place));
    }

    @Test
    @DisplayName("Corrida: moderadores aprovam e rejeitam a mesma solicitação ao mesmo tempo; uma decisão vence")
    void concurrentDecisionsOnSameClaim() throws Exception {
        UUID owner = registerUser("race_dec_owner");
        BusinessAccountView account = account(owner);
        UUID place = place("ACTIVE");
        PlaceClaimView claim = requestPlaceClaim.execute(owner, account.id(), place, EVIDENCE);
        List<Callable<Object>> calls = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            UUID moderator = moderator("race_dec_mod_" + i);
            PlaceClaimDecision decision = i % 2 == 0 ? PlaceClaimDecision.APPROVE : PlaceClaimDecision.REJECT;
            calls.add(() -> decidePlaceClaim.execute(moderator, claim.id(), decision, REASON, Instant.now()));
        }
        List<Object> outcomes = runConcurrently(calls);

        List<PlaceClaimView> winners = outcomes.stream().filter(PlaceClaimView.class::isInstance)
                .map(PlaceClaimView.class::cast).toList();
        assertEquals(1, winners.size(), outcomes.toString());
        for (Object outcome : outcomes) {
            if (outcome instanceof BusinessException ex) {
                assertEquals("PLACE_CLAIM_ALREADY_DECIDED", ex.getErrorCode());
            } else {
                assertTrue(outcome instanceof PlaceClaimView, "falha inesperada: " + outcome);
            }
        }
        UUID claimedBy = jdbcTemplate.queryForObject("SELECT claimed_by_business_id FROM places WHERE id = ?", UUID.class, place);
        assertEquals(winners.get(0).status() == PlaceClaimStatus.APPROVED ? account.id() : null, claimedBy,
                "vínculo coerente com a decisão vencedora");
        assertEquals(winners.get(0).status().name(), jdbcTemplate.queryForObject(
                "SELECT status FROM place_claim_requests WHERE id = ?", String.class, claim.id()));
    }

    // ---------------------------------------------------------------------------------------------------------
    // Listagens
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("Histórico da conta paginado, isolado entre contas e filtrável; fila administrativa por status")
    void listings() throws Exception {
        UUID owner = registerUser("list_owner");
        UUID otherOwner = registerUser("list_other");
        BusinessAccountView account = account(owner);
        BusinessAccountView otherAccount = account(otherOwner);
        List<UUID> mine = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            mine.add(requestPlaceClaim.execute(owner, account.id(), place("ACTIVE"), EVIDENCE).id());
        }
        UUID others = requestPlaceClaim.execute(otherOwner, otherAccount.id(), place("ACTIVE"), EVIDENCE).id();
        decidePlaceClaim.execute(moderator("list_mod"), mine.get(0), PlaceClaimDecision.REJECT, REASON, Instant.now());

        PageResult<PlaceClaimView> first = listBusinessPlaceClaims.execute(owner, account.id(), null, 0, 2);
        PageResult<PlaceClaimView> second = listBusinessPlaceClaims.execute(owner, account.id(), null, 1, 2);
        assertEquals(3, first.totalElements());
        assertEquals(2, first.totalPages());
        List<UUID> listed = new ArrayList<>();
        first.content().forEach(view -> listed.add(view.id()));
        second.content().forEach(view -> listed.add(view.id()));
        assertEquals(List.of(mine.get(2), mine.get(1), mine.get(0)), listed, "mais recentes primeiro, sem perda nem repetição");
        assertFalse(listed.contains(others), "isolado entre contas");

        PageResult<PlaceClaimView> rejected = listBusinessPlaceClaims.execute(owner, account.id(), PlaceClaimStatus.REJECTED, 0, 10);
        assertEquals(List.of(mine.get(0)), rejected.content().stream().map(view -> view.id()).toList());
        assertNotNull(rejected.content().get(0).decidedAt());

        assertCode("BUSINESS_ACCOUNT_NOT_FOUND", 404,
                () -> listBusinessPlaceClaims.execute(otherOwner, account.id(), null, 0, 10));
        assertCode("INVALID_PAGE_SIZE", 400, () -> listBusinessPlaceClaims.execute(owner, account.id(), null, 0, 51));
        assertCode("INVALID_PAGE", 400, () -> queryAdminPlaceClaims.execute(-1, 10, null));

        // Fila administrativa: pendentes, mais antigas primeiro, com conta e local
        List<UUID> queue = new ArrayList<>();
        int page = 0;
        PageResult<PlaceClaimView> slice;
        do {
            slice = queryAdminPlaceClaims.execute(page++, 50, PlaceClaimStatus.PENDING);
            slice.content().forEach(view -> {
                assertEquals(PlaceClaimStatus.PENDING, view.status());
                queue.add(view.id());
            });
        } while (!slice.isLast());
        assertTrue(queue.indexOf(mine.get(1)) < queue.indexOf(mine.get(2)), "mais antigas primeiro");
        assertTrue(queue.contains(others));
        assertFalse(queue.contains(mine.get(0)), "rejeitada fora do filtro PENDING");
    }

    // ---------------------------------------------------------------------------------------------------------
    // Migration V23
    // ---------------------------------------------------------------------------------------------------------

    @Test
    @DisplayName("V23 no PostgreSQL: pendência única por local, status, evidência e consistência da decisão")
    void migrationConstraints() throws Exception {
        UUID owner = registerUser("v23_owner");
        UUID account = account(owner).id();
        UUID place = place("ACTIVE");
        String insert = "INSERT INTO place_claim_requests (id, business_account_id, place_id, evidence_description, status, "
                + "decided_at, decided_by_user_id, decision_reason) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        Timestamp now = Timestamp.from(Instant.now());

        jdbcTemplate.update(insert, UUID.randomUUID(), account, place, EVIDENCE, "PENDING", null, null, null);
        assertViolation("uq_place_claim_pending_place",
                () -> jdbcTemplate.update(insert, UUID.randomUUID(), account, place, EVIDENCE, "PENDING", null, null, null));
        // Decididas não contam para a pendência única
        jdbcTemplate.update(insert, UUID.randomUUID(), account, place, EVIDENCE, "REJECTED", now, owner, REASON);

        UUID other = place("ACTIVE");
        assertViolation("chk_place_claim_status",
                () -> jdbcTemplate.update(insert, UUID.randomUUID(), account, other, EVIDENCE, "CANCELLED", now, owner, REASON));
        assertViolation("chk_place_claim_evidence_length",
                () -> jdbcTemplate.update(insert, UUID.randomUUID(), account, other, "x".repeat(19), "PENDING", null, null, null));
        assertViolation("chk_place_claim_decision",
                () -> jdbcTemplate.update(insert, UUID.randomUUID(), account, other, EVIDENCE, "PENDING", now, owner, REASON));
        assertViolation("chk_place_claim_decision",
                () -> jdbcTemplate.update(insert, UUID.randomUUID(), account, other, EVIDENCE, "APPROVED", now, owner, "   "));
        assertViolation("chk_place_claim_decision",
                () -> jdbcTemplate.update(insert, UUID.randomUUID(), account, other, EVIDENCE, "APPROVED", null, owner, REASON));
        assertViolation("place_claim_requests_place_id_fkey",
                () -> jdbcTemplate.update(insert, UUID.randomUUID(), account, UUID.randomUUID(), EVIDENCE, "PENDING", null, null, null));

        List<String> indexes = jdbcTemplate.queryForList(
                "SELECT indexname FROM pg_indexes WHERE tablename = 'place_claim_requests' ORDER BY indexname", String.class);
        assertTrue(indexes.containsAll(List.of("idx_place_claim_business_created", "idx_place_claim_status_created",
                "uq_place_claim_pending_place")), indexes.toString());
    }

    // ---------------------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------------------

    private UUID ownerOf(BusinessAccountView account) {
        return jdbcTemplate.queryForObject("SELECT user_id FROM business_accounts WHERE id = ?", UUID.class, account.id());
    }

    private List<Object> runConcurrently(List<Callable<Object>> calls) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(calls.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Object>> futures = new ArrayList<>();
            for (Callable<Object> call : calls) {
                futures.add(executor.submit(() -> {
                    start.await();
                    try {
                        return call.call();
                    } catch (BusinessException ex) {
                        return ex;
                    }
                }));
            }
            start.countDown();
            List<Object> outcomes = new ArrayList<>();
            for (Future<Object> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            executor.shutdownNow();
        }
    }

    private static void assertCode(String code, int status, org.junit.jupiter.api.function.Executable call) {
        BusinessException ex = assertThrows(BusinessException.class, call);
        assertEquals(code, ex.getErrorCode());
        assertEquals(status, ex.getStatus().value());
    }

    private static void assertViolation(String constraint, org.junit.jupiter.api.function.Executable call) {
        DataIntegrityViolationException ex = assertThrows(DataIntegrityViolationException.class, call);
        assertTrue(ex.getMostSpecificCause().getMessage().contains(constraint), ex.getMostSpecificCause().getMessage());
    }

    private BusinessAccountView account(UUID owner) {
        return createBusinessAccount.execute(owner, "Empresa " + owner.toString().substring(0, 8), randomDigits(14));
    }

    private UUID moderator(String prefix) throws Exception {
        UUID user = registerUser(prefix);
        jdbcTemplate.update("UPDATE users SET role = 'MODERATOR' WHERE id = ?", user);
        return user;
    }

    private UUID place(String status) {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place saved = placeRepository.save(new Place(null, "Local Reivindicação " + suffix, "claim-" + UUID.randomUUID(),
                "RESTAURANTE", "Descrição", "Rua Claim, 1", "Curitiba", "PR", "BR", -25.43, -49.27, 50, "USER", false,
                null, status));
        return saved.getId();
    }

    private static String randomDigits(int count) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            builder.append(ThreadLocalRandom.current().nextInt(10));
        }
        return builder.toString();
    }

    private UUID registerUser(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        MvcResult result = mockMvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(prefix + "." + suffix + "@rewit.test",
                                "Senha@" + suffix, prefix + "_" + suffix, "Nome " + suffix))))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return UUID.fromString(node.get("user").get("id").asText());
    }
}
