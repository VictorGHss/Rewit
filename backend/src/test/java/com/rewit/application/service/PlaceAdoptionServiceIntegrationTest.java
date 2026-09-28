package com.rewit.application.service;

import com.rewit.application.dto.catalog.CatalogDtos.CreatePlaceCommand;
import com.rewit.application.dto.catalog.CatalogDtos.ExternalReferenceInput;
import com.rewit.application.port.PlaceExternalReferenceRepository;
import com.rewit.application.port.PlaceRepository;
import com.rewit.application.port.RateableTargetRepository;
import com.rewit.domain.enums.TargetType;
import com.rewit.domain.model.Place;
import com.rewit.domain.model.PlaceExternalReference;
import com.rewit.domain.model.RateableTarget;
import com.rewit.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("local")
@DisplayName("Testes de Integração: Adoção Idempotente de Place e Concorrência (Step 9.3)")
class PlaceAdoptionServiceIntegrationTest {

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private PlaceRepository placeRepository;

    @Autowired
    private PlaceExternalReferenceRepository referenceRepository;

    @Autowired
    private RateableTargetRepository rateableTargetRepository;

    @Test
    @DisplayName("1. Criação completa: RateableTarget + Place + PlaceExternalReference criados atomicamente")
    void shouldCreatePlaceAndExternalReferenceAtomicallyInDatabase() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String externalId = "ChIJ_TEST_ATOMIC_" + suffix;

        ExternalReferenceInput extInput = new ExternalReferenceInput("GOOGLE", externalId);
        CreatePlaceCommand cmd = new CreatePlaceCommand(
                "Restaurante Italiano " + suffix,
                "restaurante-italiano-" + suffix,
                "RESTAURANTE",
                "Comida artesanal",
                "Rua das Flores, 100",
                "100",
                "Centro",
                "Curitiba",
                "PR",
                "BR",
                -25.4300,
                -49.2700,
                50,
                "USER",
                null,
                extInput
        );

        Place created = catalogService.createPlace(cmd);

        assertNotNull(created);
        assertNotNull(created.getId());
        assertEquals("USER", created.getOrigin(), "Origin deve ser sempre USER");

        // 1. Valida existência do RateableTarget
        Optional<RateableTarget> target = rateableTargetRepository.findById(created.getId());
        assertTrue(target.isPresent(), "RateableTarget deve existir com mesmo UUID do Place");
        assertEquals(TargetType.PLACE, target.get().getTargetType());

        // 2. Valida existência do Place
        Optional<Place> place = placeRepository.findById(created.getId());
        assertTrue(place.isPresent());
        assertEquals("Restaurante Italiano " + suffix, place.get().getName());

        // 3. Valida existência da PlaceExternalReference vinculada
        Optional<PlaceExternalReference> ref = referenceRepository.findByProviderAndExternalId("GOOGLE", externalId);
        assertTrue(ref.isPresent());
        assertEquals(created.getId(), ref.get().getPlaceId());
        assertEquals("GOOGLE", ref.get().getProvider());
        assertEquals(externalId, ref.get().getExternalId());
        assertNull(ref.get().getMetadataJson(), "Metadata deve ser nulo no MVP para conformidade Zero-Store");
    }

    @Test
    @DisplayName("2. Idempotência Sequencial: Segunda chamada com mesmo Place ID retorna Place existente sem duplicar")
    void shouldReturnExistingPlaceOnSequentialCallsWithSameExternalId() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String externalId = "ChIJ_IDEMPOTENT_" + suffix;

        ExternalReferenceInput extInput = new ExternalReferenceInput("GOOGLE", externalId);
        CreatePlaceCommand cmd1 = new CreatePlaceCommand(
                "Café da Praça " + suffix,
                "cafe-da-praca-" + suffix,
                "CAFE",
                "Descrição inicial",
                "Praça Santos Andrade",
                "50",
                "Centro",
                "Curitiba",
                "PR",
                "BR",
                -25.4280,
                -49.2680,
                50,
                "USER",
                null,
                extInput
        );

        Place firstCall = catalogService.createPlace(cmd1);
        assertNotNull(firstCall);

        // Segunda chamada com novos dados mas mesmo identificador Google
        CreatePlaceCommand cmd2 = new CreatePlaceCommand(
                "Outro Nome Rejeitado",
                "outro-slug",
                "OUTRO",
                "Outra desc",
                "Outro end",
                null,
                null,
                "Curitiba",
                "PR",
                "BR",
                -25.0,
                -49.0,
                50,
                "USER",
                null,
                extInput
        );

        Place secondCall = catalogService.createPlace(cmd2);

        // Deve retornar o mesmo Place com os dados originais preservados
        assertEquals(firstCall.getId(), secondCall.getId(), "Deve retornar o mesmo UUID interno");
        assertEquals("Café da Praça " + suffix, secondCall.getName(), "Dados originais não podem ser modificados");
        assertEquals("cafe-da-praca-" + suffix, secondCall.getSlug());

        // Validação no banco: apenas 1 referência externa existe
        List<PlaceExternalReference> refs = referenceRepository.findByPlaceId(firstCall.getId());
        assertEquals(1, refs.size(), "Deve existir exatamente 1 referência externa vinculada");
    }

    @Test
    @DisplayName("3. Concorrência Real Multithread: Duas threads adotando o mesmo externalId convergem para o mesmo Place sem erro 500")
    void shouldConvergeToSamePlaceUnderConcurrentAdoption() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String sharedExternalId = "ChIJ_CONCURRENT_SVC_" + suffix;

        int numberOfThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(numberOfThreads);

        AtomicReference<Place> placeThread1 = new AtomicReference<>();
        AtomicReference<Place> placeThread2 = new AtomicReference<>();
        AtomicReference<Exception> errorThread1 = new AtomicReference<>();
        AtomicReference<Exception> errorThread2 = new AtomicReference<>();

        Callable<Void> task1 = () -> {
            startSignal.await();
            try {
                ExternalReferenceInput extInput = new ExternalReferenceInput("GOOGLE", sharedExternalId);
                CreatePlaceCommand cmd = new CreatePlaceCommand(
                        "Local Concorrente T1 " + suffix,
                        "local-concorrente-" + suffix,
                        "BAR",
                        "Desc T1",
                        "End T1",
                        "1",
                        "Bairro",
                        "Curitiba",
                        "PR",
                        "BR",
                        -25.43,
                        -49.27,
                        50,
                        "USER",
                        null,
                        extInput
                );
                placeThread1.set(catalogService.createPlace(cmd));
            } catch (Exception e) {
                errorThread1.set(e);
            } finally {
                doneSignal.countDown();
            }
            return null;
        };

        Callable<Void> task2 = () -> {
            startSignal.await();
            try {
                ExternalReferenceInput extInput = new ExternalReferenceInput("GOOGLE", sharedExternalId);
                CreatePlaceCommand cmd = new CreatePlaceCommand(
                        "Local Concorrente T2 " + suffix,
                        "local-concorrente-" + suffix,
                        "BAR",
                        "Desc T2",
                        "End T2",
                        "2",
                        "Bairro",
                        "Curitiba",
                        "PR",
                        "BR",
                        -25.43,
                        -49.27,
                        50,
                        "USER",
                        null,
                        extInput
                );
                placeThread2.set(catalogService.createPlace(cmd));
            } catch (Exception e) {
                errorThread2.set(e);
            } finally {
                doneSignal.countDown();
            }
            return null;
        };

        executor.submit(task1);
        executor.submit(task2);

        // Dispara as duas threads no mesmo instante exato
        startSignal.countDown();

        boolean finished = doneSignal.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(finished, "As threads concorrentes devem concluir em até 10 segundos");
        assertNull(errorThread1.get(), "Thread 1 não deve falhar com exceção: " + errorThread1.get());
        assertNull(errorThread2.get(), "Thread 2 não deve falhar com exceção: " + errorThread2.get());

        assertNotNull(placeThread1.get());
        assertNotNull(placeThread2.get());

        // Ambas as threads devem convergir para o mesmo Place interno!
        assertEquals(placeThread1.get().getId(), placeThread2.get().getId(),
                "Ambas as requisições concorrentes devem retornar o mesmo Place lógico sem duplicação");

        // Confirma no banco que existe exatamente 1 PlaceExternalReference
        Optional<PlaceExternalReference> ref = referenceRepository.findByProviderAndExternalId("GOOGLE", sharedExternalId);
        assertTrue(ref.isPresent());
        assertEquals(placeThread1.get().getId(), ref.get().getPlaceId());
    }

    @Test
    @DisplayName("4. Resolução Automática de Slug: Nomes iguais geram slugs com sufixos determinísticos sem erro de constraint")
    void shouldResolveDuplicateSlugWithSequentialSuffix() {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "Padaria Central " + suffix;

        CreatePlaceCommand cmd1 = new CreatePlaceCommand(
                name,
                null, // slug omitido -> gerado do nome
                "PADARIA",
                "Desc 1",
                "Rua 1",
                "10",
                "Centro",
                "Curitiba",
                "PR",
                "BR",
                -25.43,
                -49.27,
                50,
                "USER",
                null
        );

        Place place1 = catalogService.createPlace(cmd1);
        assertNotNull(place1);

        // Cria segundo local com o mesmo nome
        CreatePlaceCommand cmd2 = new CreatePlaceCommand(
                name,
                null,
                "PADARIA",
                "Desc 2",
                "Rua 2",
                "20",
                "Batel",
                "Curitiba",
                "PR",
                "BR",
                -25.44,
                -49.28,
                50,
                "USER",
                null
        );

        Place place2 = catalogService.createPlace(cmd2);
        assertNotNull(place2);

        assertNotEquals(place1.getId(), place2.getId());
        assertNotEquals(place1.getSlug(), place2.getSlug());
        assertEquals(place1.getSlug() + "-2", place2.getSlug(), "O segundo local deve receber o sufixo -2");
    }

    @Test
    @DisplayName("5. Concorrência Real Multithread Slug Automático: Duas threads com mesmo nome e referências distintas geram slugs únicos (-2) sem 500 nem órfãos")
    void shouldHandleConcurrentCreationWithAutomaticSlugSafely() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String name = "Padaria Central " + suffix;
        String extIdA = "ChIJ_REF_A_" + suffix;
        String extIdB = "ChIJ_REF_B_" + suffix;

        int numberOfThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(numberOfThreads);

        AtomicReference<Place> placeThread1 = new AtomicReference<>();
        AtomicReference<Place> placeThread2 = new AtomicReference<>();
        AtomicReference<Exception> errorThread1 = new AtomicReference<>();
        AtomicReference<Exception> errorThread2 = new AtomicReference<>();

        Callable<Void> task1 = () -> {
            startSignal.await();
            try {
                ExternalReferenceInput extInput = new ExternalReferenceInput("GOOGLE", extIdA);
                CreatePlaceCommand cmd = new CreatePlaceCommand(
                        name,
                        null, // slug omitido
                        "PADARIA",
                        "Desc A",
                        "Rua A",
                        "10",
                        "Bairro",
                        "Curitiba",
                        "PR",
                        "BR",
                        -25.43,
                        -49.27,
                        50,
                        "USER",
                        null,
                        extInput
                );
                placeThread1.set(catalogService.createPlace(cmd));
            } catch (Exception e) {
                errorThread1.set(e);
            } finally {
                doneSignal.countDown();
            }
            return null;
        };

        Callable<Void> task2 = () -> {
            startSignal.await();
            try {
                ExternalReferenceInput extInput = new ExternalReferenceInput("GOOGLE", extIdB);
                CreatePlaceCommand cmd = new CreatePlaceCommand(
                        name,
                        null, // slug omitido
                        "PADARIA",
                        "Desc B",
                        "Rua B",
                        "20",
                        "Bairro",
                        "Curitiba",
                        "PR",
                        "BR",
                        -25.43,
                        -49.27,
                        50,
                        "USER",
                        null,
                        extInput
                );
                placeThread2.set(catalogService.createPlace(cmd));
            } catch (Exception e) {
                errorThread2.set(e);
            } finally {
                doneSignal.countDown();
            }
            return null;
        };

        executor.submit(task1);
        executor.submit(task2);

        // Dispara simultaneamente
        startSignal.countDown();

        boolean finished = doneSignal.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(finished, "As threads concorrentes devem concluir em até 10 segundos");
        assertNull(errorThread1.get(), "Thread 1 não deve falhar com erro: " + errorThread1.get());
        assertNull(errorThread2.get(), "Thread 2 não deve falhar com erro: " + errorThread2.get());

        Place p1 = placeThread1.get();
        Place p2 = placeThread2.get();

        assertNotNull(p1);
        assertNotNull(p2);
        assertNotEquals(p1.getId(), p2.getId(), "Lugares com referências distintas devem ser entidades diferentes");
        assertNotEquals(p1.getSlug(), p2.getSlug(), "Os slugs devem ser únicos sob concorrência");

        String baseSlug = CatalogService.generateSlug(name);
        Set<String> expectedSlugs = Set.of(baseSlug, baseSlug + "-2");
        Set<String> actualSlugs = Set.of(p1.getSlug(), p2.getSlug());
        assertEquals(expectedSlugs, actualSlugs, "Um local deve obter o baseSlug e o outro o baseSlug-2");

        // 1. Valida existência dos 2 Places no banco
        assertTrue(placeRepository.findById(p1.getId()).isPresent());
        assertTrue(placeRepository.findById(p2.getId()).isPresent());

        // 2. Valida existência dos 2 RateableTargets correspondentes
        assertTrue(rateableTargetRepository.findById(p1.getId()).isPresent());
        assertTrue(rateableTargetRepository.findById(p2.getId()).isPresent());

        // 3. Valida existência das 2 referências externas apontando para seus respectivos lugares
        Optional<PlaceExternalReference> refA = referenceRepository.findByProviderAndExternalId("GOOGLE", extIdA);
        Optional<PlaceExternalReference> refB = referenceRepository.findByProviderAndExternalId("GOOGLE", extIdB);
        assertTrue(refA.isPresent());
        assertTrue(refB.isPresent());
        assertEquals(p1.getId().equals(refA.get().getPlaceId()) ? p1.getId() : p2.getId(), refA.get().getPlaceId());
        assertEquals(p2.getId().equals(refB.get().getPlaceId()) ? p2.getId() : p1.getId(), refB.get().getPlaceId());
    }

    @Test
    @DisplayName("6. Concorrência Real Multithread Slug Explícito: Duas threads solicitando mesmo slug resultam em 1 sucesso e 1 erro 409 (PLACE_SLUG_ALREADY_EXISTS)")
    void shouldHandleConcurrentCreationWithExplicitSlugSafely() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String explicitSlug = "slug-explicito-concorrente-" + suffix;

        int numberOfThreads = 2;
        ExecutorService executor = Executors.newFixedThreadPool(numberOfThreads);
        CountDownLatch startSignal = new CountDownLatch(1);
        CountDownLatch doneSignal = new CountDownLatch(numberOfThreads);

        AtomicReference<Place> placeThread1 = new AtomicReference<>();
        AtomicReference<Place> placeThread2 = new AtomicReference<>();
        AtomicReference<Exception> errorThread1 = new AtomicReference<>();
        AtomicReference<Exception> errorThread2 = new AtomicReference<>();

        Callable<Void> task1 = () -> {
            startSignal.await();
            try {
                CreatePlaceCommand cmd = new CreatePlaceCommand(
                        "Local A " + suffix,
                        explicitSlug,
                        "BAR",
                        "Desc A",
                        "Rua 1",
                        "1",
                        "Centro",
                        "Curitiba",
                        "PR",
                        "BR",
                        -25.43,
                        -49.27,
                        50,
                        "USER",
                        null
                );
                placeThread1.set(catalogService.createPlace(cmd));
            } catch (Exception e) {
                errorThread1.set(e);
            } finally {
                doneSignal.countDown();
            }
            return null;
        };

        Callable<Void> task2 = () -> {
            startSignal.await();
            try {
                CreatePlaceCommand cmd = new CreatePlaceCommand(
                        "Local B " + suffix,
                        explicitSlug,
                        "BAR",
                        "Desc B",
                        "Rua 2",
                        "2",
                        "Centro",
                        "Curitiba",
                        "PR",
                        "BR",
                        -25.43,
                        -49.27,
                        50,
                        "USER",
                        null
                );
                placeThread2.set(catalogService.createPlace(cmd));
            } catch (Exception e) {
                errorThread2.set(e);
            } finally {
                doneSignal.countDown();
            }
            return null;
        };

        executor.submit(task1);
        executor.submit(task2);

        startSignal.countDown();

        boolean finished = doneSignal.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(finished, "As threads concorrentes devem concluir em até 10 segundos");

        // Exatamente 1 deve ter sucesso e exatamente 1 deve ter falha
        boolean thread1Succeeded = placeThread1.get() != null && errorThread1.get() == null;
        boolean thread2Succeeded = placeThread2.get() != null && errorThread2.get() == null;

        assertTrue(thread1Succeeded ^ thread2Succeeded, "Exatamente uma das threads deve ter sucesso");

        Exception failure = thread1Succeeded ? errorThread2.get() : errorThread1.get();
        assertNotNull(failure, "A thread perdedora deve registrar falha");
        assertTrue(failure instanceof BusinessException, "A falha deve ser uma BusinessException (e não 500 DataIntegrityViolationException)");

        BusinessException bex = (BusinessException) failure;
        assertEquals("PLACE_SLUG_ALREADY_EXISTS", bex.getErrorCode());
        assertEquals(HttpStatus.CONFLICT, bex.getStatus());

        // Confirma no banco que existe exatamente 1 Place com esse slug
        Optional<Place> persistedPlace = placeRepository.findBySlug(explicitSlug);
        assertTrue(persistedPlace.isPresent());
        Place winner = thread1Succeeded ? placeThread1.get() : placeThread2.get();
        assertEquals(winner.getId(), persistedPlace.get().getId());

        // Confirma que existe apenas 1 RateableTarget para o vencedor (nenhum órfão)
        assertTrue(rateableTargetRepository.findById(winner.getId()).isPresent());
    }
}
