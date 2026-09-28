package com.rewit.infrastructure.persistence;

import com.rewit.application.port.PlaceExternalReferenceRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceExternalReference;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração de Persistência e Concorrência de PlaceExternalReference (Step 9.2)")
class PlaceExternalReferencePersistenceIntegrationTest {

    @Autowired
    private PlaceExternalReferenceRepository referenceRepository;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private com.rewit.infrastructure.persistence.repository.PlaceJpaRepository placeJpaRepository;

    private Place createAndPersistPlace() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Place place = new Place(
                null,
                "Restaurante Concorrência " + suffix,
                "restaurante-concorrencia-" + suffix,
                "RESTAURANTE",
                "Descrição de teste",
                "Rua Teste, 100",
                "100",
                "Bairro",
                "Curitiba",
                "PR",
                "BR",
                -25.4300,
                -49.2700,
                50,
                "USER",
                false,
                null,
                "ACTIVE"
        );
        return placeRepository.save(place);
    }

    @Test
    @DisplayName("1. Salvar e recuperar referência externa por provedor e externalId")
    void shouldPersistAndRetrieveExternalReference() {
        Place place = createAndPersistPlace();
        String externalId = "ChIJ_" + UUID.randomUUID();

        PlaceExternalReference ref = new PlaceExternalReference(
                null,
                place.getId(),
                "GOOGLE",
                externalId,
                null
        );

        PlaceExternalReference saved = referenceRepository.save(ref);

        assertNotNull(saved.getId());
        assertEquals(place.getId(), saved.getPlaceId());
        assertEquals("GOOGLE", saved.getProvider());
        assertEquals(externalId, saved.getExternalId());
        assertNull(saved.getMetadataJson());
        assertNotNull(saved.getCreatedAt());

        Optional<PlaceExternalReference> found = referenceRepository.findByProviderAndExternalId("GOOGLE", externalId);
        assertTrue(found.isPresent());
        assertEquals(saved.getId(), found.get().getId());
        assertEquals(place.getId(), found.get().getPlaceId());

        List<PlaceExternalReference> byPlace = referenceRepository.findByPlaceId(place.getId());
        assertEquals(1, byPlace.size());
        assertEquals(saved.getId(), byPlace.get(0).getId());
    }

    @Test
    @DisplayName("2. Rejeitar duplicidade sequencial de mesmo provider e externalId (constraint uq_place_ext_ref)")
    void shouldRejectDuplicateProviderAndExternalIdSequentially() {
        Place place1 = createAndPersistPlace();
        Place place2 = createAndPersistPlace();
        String sharedExternalId = "ChIJ_SHARED_" + UUID.randomUUID();

        PlaceExternalReference ref1 = new PlaceExternalReference(
                null,
                place1.getId(),
                "GOOGLE",
                sharedExternalId,
                null
        );
        referenceRepository.save(ref1);

        PlaceExternalReference ref2 = new PlaceExternalReference(
                null,
                place2.getId(),
                "google", // minúsculo deve normalizar para GOOGLE
                sharedExternalId,
                null
        );

        assertThrows(DataIntegrityViolationException.class, () -> {
            referenceRepository.save(ref2);
        }, "A constraint uq_place_ext_ref deve impedir gravação duplicada do mesmo external_id para o mesmo provider");
    }

    @Test
    @DisplayName("3. Concorrência Real Multithread: garantir que exatamente 1 referência vence sob concorrência")
    void shouldEnforceUniquenessUnderConcurrentThreads() throws Exception {
        Place place1 = createAndPersistPlace();
        Place place2 = createAndPersistPlace();
        String concurrentExternalId = "ChIJ_CONCURRENT_" + UUID.randomUUID();

        int numberOfThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(numberOfThreads);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failureCount = new AtomicInteger(0);

        Callable<Void> task1 = () -> {
            startSignal.await();
            try {
                PlaceExternalReference ref = new PlaceExternalReference(
                        null,
                        place1.getId(),
                        "GOOGLE",
                        concurrentExternalId,
                        null
                );
                referenceRepository.save(ref);
                successCount.incrementAndGet();
            } catch (Exception e) {
                failureCount.incrementAndGet();
            } finally {
                doneSignal.countDown();
            }
            return null;
        };

        Callable<Void> task2 = () -> {
            startSignal.await();
            try {
                PlaceExternalReference ref = new PlaceExternalReference(
                        null,
                        place2.getId(),
                        "GOOGLE",
                        concurrentExternalId,
                        null
                );
                referenceRepository.save(ref);
                successCount.incrementAndGet();
            } catch (Exception e) {
                failureCount.incrementAndGet();
            } finally {
                doneSignal.countDown();
            }
            return null;
        };

        executor.submit(task1);
        executor.submit(task2);

        // Dispara as duas threads simultaneamente
        startSignal.countDown();

        boolean finishedInTime = doneSignal.await(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(finishedInTime, "A execução das threads concorrentes deve terminar em até 5 segundos");
        assertEquals(1, successCount.get(), "Exatamente UMA inserção concorrente deve ter sucesso");
        assertEquals(1, failureCount.get(), "Exatamente UMA inserção concorrente deve ser barrada pela constraint");

        // Validação no banco: exatamente 1 registro existe para o identificador
        Optional<PlaceExternalReference> found = referenceRepository.findByProviderAndExternalId("GOOGLE", concurrentExternalId);
        assertTrue(found.isPresent());
    }

    @Test
    @DisplayName("4. Cascata de remoção: excluir Place deve remover automaticamente referências externas (ON DELETE CASCADE)")
    void shouldCascadeDeleteWhenPlaceIsRemoved() {
        Place place = createAndPersistPlace();
        String externalId = "ChIJ_CASCADE_" + UUID.randomUUID();

        PlaceExternalReference ref = new PlaceExternalReference(
                null,
                place.getId(),
                "GOOGLE",
                externalId,
                null
        );
        referenceRepository.save(ref);

        assertTrue(referenceRepository.existsByProviderAndExternalId("GOOGLE", externalId));

        // Remove o local
        placeJpaRepository.deleteById(place.getId());

        // A referência deve ter sido excluída pela FK com ON DELETE CASCADE
        assertFalse(referenceRepository.existsByProviderAndExternalId("GOOGLE", externalId));
        assertTrue(referenceRepository.findByPlaceId(place.getId()).isEmpty());
    }

    @Test
    @DisplayName("5. Zero-Store: garantir persistência de referência com metadata nulo e sem dados de conteúdo da Google")
    void shouldAllowNullMetadataForZeroStoreCompliance() {
        Place place = createAndPersistPlace();
        String externalId = "ChIJ_ZERO_STORE_" + UUID.randomUUID();

        PlaceExternalReference ref = new PlaceExternalReference(
                null,
                place.getId(),
                "GOOGLE",
                externalId,
                null // Zero-store: nenhum dado ou payload Google no metadata
        );

        PlaceExternalReference saved = referenceRepository.save(ref);
        assertNull(saved.getMetadataJson(), "Metadata deve permanecer null no MVP para garantir conformidade Zero-Store");

        Optional<PlaceExternalReference> found = referenceRepository.findByProviderAndExternalId("GOOGLE", externalId);
        assertTrue(found.isPresent());
        assertNull(found.get().getMetadataJson());
    }

    @Test
    @DisplayName("6. Verificação de existência com existsByProviderAndExternalId")
    void shouldVerifyExistsByProviderAndExternalId() {
        Place place = createAndPersistPlace();
        String externalId = "ChIJ_EXISTS_" + UUID.randomUUID();

        assertFalse(referenceRepository.existsByProviderAndExternalId("GOOGLE", externalId));

        PlaceExternalReference ref = new PlaceExternalReference(
                null,
                place.getId(),
                "GOOGLE",
                externalId,
                null
        );
        referenceRepository.save(ref);

        assertTrue(referenceRepository.existsByProviderAndExternalId("GOOGLE", externalId));
        assertTrue(referenceRepository.existsByProviderAndExternalId("google", "  " + externalId + "  "), "Busca deve ser case-insensitive e trim-safe");
    }

    @Test
    @DisplayName("7. Remoção direta de referência via deleteById")
    void shouldDeleteReferenceDirectly() {
        Place place = createAndPersistPlace();
        String externalId = "ChIJ_DEL_" + UUID.randomUUID();

        PlaceExternalReference ref = new PlaceExternalReference(
                null,
                place.getId(),
                "GOOGLE",
                externalId,
                null
        );
        PlaceExternalReference saved = referenceRepository.save(ref);
        assertTrue(referenceRepository.existsByProviderAndExternalId("GOOGLE", externalId));

        referenceRepository.deleteById(saved.getId());
        assertFalse(referenceRepository.existsByProviderAndExternalId("GOOGLE", externalId));
    }

    @Test
    @DisplayName("8. Migration V6: Aceitar externalId com mais de 255 caracteres (Google Place IDs sem limite de tamanho)")
    void shouldPersistAndRetrieveUnboundedExternalIdLongerThan255Chars() {
        Place place = createAndPersistPlace();
        // Gera um identificador de 350 caracteres para testar a remoção do limite VARCHAR(255)
        String longExternalId = "ChIJ_UNBOUNDED_" + "a".repeat(300) + "_" + UUID.randomUUID();
        assertTrue(longExternalId.length() > 255, "Identificador de teste deve exceder 255 caracteres");

        PlaceExternalReference ref = new PlaceExternalReference(
                null,
                place.getId(),
                "GOOGLE",
                longExternalId,
                null
        );

        PlaceExternalReference saved = referenceRepository.save(ref);
        assertNotNull(saved.getId());
        assertEquals(longExternalId, saved.getExternalId(), "ExternalId longo deve ser preservado integralmente");

        Optional<PlaceExternalReference> found = referenceRepository.findByProviderAndExternalId("GOOGLE", longExternalId);
        assertTrue(found.isPresent());
        assertEquals(longExternalId, found.get().getExternalId());
    }

    @Test
    @DisplayName("9. Migration V6: Unicidade com externalId longo continua assegurada pela constraint uq_place_ext_ref")
    void shouldRejectDuplicateLongExternalId() {
        Place place1 = createAndPersistPlace();
        Place place2 = createAndPersistPlace();
        String sharedLongId = "ChIJ_LONG_SHARED_" + "x".repeat(300) + "_" + UUID.randomUUID();

        PlaceExternalReference ref1 = new PlaceExternalReference(
                null,
                place1.getId(),
                "GOOGLE",
                sharedLongId,
                null
        );
        referenceRepository.save(ref1);

        PlaceExternalReference ref2 = new PlaceExternalReference(
                null,
                place2.getId(),
                "GOOGLE",
                sharedLongId,
                null
        );

        assertThrows(DataIntegrityViolationException.class, () -> {
            referenceRepository.save(ref2);
        }, "Constraint uq_place_ext_ref deve impedir duplicatas mesmo com IDs maiores que 255 caracteres");
    }
}
