package com.rewit.infrastructure.security;

import com.rewit.application.dto.auth.AuthDtos.AuthResult;
import com.rewit.application.dto.auth.AuthDtos.RefreshCommand;
import com.rewit.application.dto.auth.AuthDtos.RegisterCommand;
import com.rewit.application.port.AuthSessionRepository;
import com.rewit.application.port.UserRepository;
import com.rewit.application.service.AuthService;
import com.rewit.common.exception.BusinessException;
import com.rewit.domain.model.AuthSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes Reais de Concorrência: Rotação Atômica de Refresh Token no PostgreSQL (Step 4.1)")
class ConcurrentRefreshIntegrationTest {

    @Autowired
    private AuthService authService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private AuthSessionRepository authSessionRepository;

    @Autowired
    private JwtTokenService tokenService;

    private UUID createdUserId;

    @AfterEach
    void tearDown() {
        if (createdUserId != null) {
            authSessionRepository.revokeAllByUserId(createdUserId);
            userRepository.findById(createdUserId).ifPresent(user -> {
                user.softDelete();
                userRepository.save(user);
            });
        }
    }

    @Test
    @DisplayName("Dois requests simultâneos com o mesmo refresh token: exatamente um deve vencer e o outro falhar")
    void shouldAllowOnlyOneSuccessfulRotationUnderConcurrentRequests() throws Exception {
        String uniqueSuffix = UUID.randomUUID().toString().substring(0, 8);
        String email = "concurrency." + uniqueSuffix + "@rewit.com";
        String password = "StrongPassword@123";
        String handle = "concurrent_" + uniqueSuffix;

        // 1. Criar usuário e obter sessão inicial
        AuthResult registerResult = authService.register(new RegisterCommand(
                email, password, handle, "Concurrent User", "test-agent", "127.0.0.1"
        ));
        this.createdUserId = registerResult.user().getId();
        String initialRefreshToken = registerResult.refreshToken();
        String initialTokenHash = tokenService.hashRefreshToken(initialRefreshToken);

        Optional<AuthSession> initialSessionOpt = authSessionRepository.findByTokenHash(initialTokenHash);
        assertTrue(initialSessionOpt.isPresent(), "Sessão inicial deve existir no banco de dados");
        AuthSession initialSession = initialSessionOpt.get();
        assertFalse(initialSession.isRevoked(), "Sessão inicial deve estar ativa antes da rotação");

        // 2. Disparar duas tentativas estritamente simultâneas usando CyclicBarrier
        int concurrentThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(concurrentThreads);
        CyclicBarrier barrier = new CyclicBarrier(concurrentThreads);
        CountDownLatch latch = new CountDownLatch(concurrentThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);
        List<Throwable> exceptions = new CopyOnWriteArrayList<>();
        List<AuthResult> successfulResults = new CopyOnWriteArrayList<>();

        for (int i = 0; i < concurrentThreads; i++) {
            final int threadIndex = i;
            executor.submit(() -> {
                try {
                    // Sincroniza todas as threads para iniciarem no mesmo instante exato
                    barrier.await();

                    RefreshCommand cmd = new RefreshCommand(
                            initialRefreshToken,
                            "concurrent-agent-" + threadIndex,
                            "127.0.0.1"
                    );
                    AuthResult result = authService.refresh(cmd);
                    successCount.incrementAndGet();
                    successfulResults.add(result);
                } catch (Throwable t) {
                    failureCount.incrementAndGet();
                    exceptions.add(t);
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean completedInTime = latch.await(15, TimeUnit.SECONDS);
        executor.shutdown();
        assertTrue(completedInTime, "As operações concorrentes devem finalizar em tempo hábil");

        // 3. Validações de Concorrência
        assertEquals(1, successCount.get(), "Exatamente UMA requisição concorrente deve ser bem-sucedida");
        assertEquals(1, failureCount.get(), "Exatamente UMA requisição concorrente deve falhar");

        Throwable failedException = exceptions.getFirst();
        assertTrue(
                failedException instanceof BusinessException,
                "A falha deve ser uma BusinessException, recebido: " + failedException.getClass().getName()
        );
        BusinessException be = (BusinessException) failedException;
        assertEquals("REFRESH_TOKEN_REVOKED", be.getErrorCode(), "O erro deve indicar que o token já foi rotacionado/revogado");

        // 4. Verificação no PostgreSQL Real
        // 4.1 A sessão antiga deve estar revogada
        Optional<AuthSession> reloadedInitialSessionOpt = authSessionRepository.findByTokenHash(initialTokenHash);
        assertTrue(reloadedInitialSessionOpt.isPresent());
        AuthSession reloadedInitialSession = reloadedInitialSessionOpt.get();
        assertTrue(reloadedInitialSession.isRevoked(), "A sessão inicial deve estar revogada no banco");
        assertNotNull(reloadedInitialSession.getReplacedBySessionId(), "A sessão inicial deve apontar para a nova sessão");

        // 4.2 Somente UMA nova sessão derivada deve existir
        UUID derivedSessionId = reloadedInitialSession.getReplacedBySessionId();
        Optional<AuthSession> derivedSessionOpt = authSessionRepository.findById(derivedSessionId);
        assertTrue(derivedSessionOpt.isPresent(), "A sessão derivada deve existir");
        AuthSession derivedSession = derivedSessionOpt.get();

        assertFalse(derivedSession.isRevoked(), "A sessão derivada da requisição vencedora deve estar ativa e válida");
        assertTrue(derivedSession.isValid(), "A sessão derivada deve ser válida");
        assertEquals(createdUserId, derivedSession.getUserId());

        // 4.3 O token retornado pela requisição vencedora deve corresponder à sessão derivada
        AuthResult winningResult = successfulResults.getFirst();
        String winningTokenHash = tokenService.hashRefreshToken(winningResult.refreshToken());
        assertEquals(winningTokenHash, derivedSession.getTokenHash(), "O hash do token vencedor deve corresponder à sessão derivada no banco");
    }
}
