package com.rewit.application.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewit.application.port.NotificationProvider;
import com.rewit.application.port.NotificationRepository;
import com.rewit.domain.model.Notification;
import com.rewit.domain.model.OutboxMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Testes Unitários - PushNotificationHandler (Step 27.3, Parte P)")
class PushNotificationHandlerTest {

    @Mock
    private NotificationRepository notificationRepository;

    @Mock
    private NotificationProvider notificationProvider;

    @Captor
    private ArgumentCaptor<Map<String, String>> metadataCaptor;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private PushNotificationHandler handler;

    private final UUID recipientId = UUID.randomUUID();
    private final UUID notificationId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        handler = new PushNotificationHandler(notificationRepository, notificationProvider, objectMapper);
    }

    private OutboxMessage pushMessage(String payload) {
        return new OutboxMessage("PUSH_NOTIFICATION", payload);
    }

    private Notification notification(String metadataJson) {
        return new Notification(notificationId, recipientId, "NEW_FOLLOWER",
                "Novo seguidor", "Você tem um novo seguidor.", "/api/v1/users/x", metadataJson);
    }

    @Nested
    @DisplayName("Roteamento por supports")
    class Supports {

        @Test
        @DisplayName("P.1: supports reconhece PUSH_NOTIFICATION")
        void shouldSupportPushNotification() {
            assertTrue(handler.supports("PUSH_NOTIFICATION"));
        }

        @Test
        @DisplayName("P.2: supports recusa outros tipos e null")
        void shouldRejectOtherMessageTypes() {
            assertFalse(handler.supports("PUSH_DELIVERY"));
            assertFalse(handler.supports("EMAIL"));
            assertFalse(handler.supports("push_notification"));
            assertFalse(handler.supports(null));
        }
    }

    @Nested
    @DisplayName("Caminho feliz e conteúdo encaminhado")
    class HappyPath {

        @Test
        @DisplayName("P.3: notificação existente resulta em provider chamado exatamente uma vez")
        void shouldCallProviderWhenNotificationExists() {
            when(notificationRepository.findById(notificationId))
                    .thenReturn(Optional.of(notification("{\"actorId\":\"" + UUID.randomUUID() + "\"}")));

            handler.handle(pushMessage("{\"notificationId\":\"" + notificationId + "\"}"));

            verify(notificationProvider, times(1)).sendPushNotification(any(), any(), any(), any());
        }

        @Test
        @DisplayName("P.9: título, corpo, destinatário e metadados correspondem à Notification persistida")
        void shouldForwardExactContentFromNotification() {
            String actorId = UUID.randomUUID().toString();
            String referenceId = UUID.randomUUID().toString();
            String metadataJson = "{\"actorId\":\"" + actorId + "\",\"referenceId\":\"" + referenceId + "\"}";
            when(notificationRepository.findById(notificationId))
                    .thenReturn(Optional.of(notification(metadataJson)));

            handler.handle(pushMessage("{\"notificationId\":\"" + notificationId + "\"}"));

            verify(notificationProvider).sendPushNotification(
                    eq(recipientId), eq("Novo seguidor"), eq("Você tem um novo seguidor."),
                    metadataCaptor.capture());
            Map<String, String> sentMetadata = metadataCaptor.getValue();
            assertEquals(2, sentMetadata.size());
            assertEquals(actorId, sentMetadata.get("actorId"));
            assertEquals(referenceId, sentMetadata.get("referenceId"));
        }

        @Test
        @DisplayName("P.11: payload com apenas notificationId basta — handler não exige nenhum outro dado")
        void shouldWorkWithMinimalPayloadOnly() {
            when(notificationRepository.findById(notificationId))
                    .thenReturn(Optional.of(notification(null)));

            String payload = "{\"notificationId\":\"" + notificationId + "\"}";
            assertFalse(payload.contains("Novo seguidor"));
            assertFalse(payload.contains(recipientId.toString()));

            handler.handle(pushMessage(payload));

            verify(notificationProvider).sendPushNotification(eq(recipientId), anyString(), anyString(), any());
        }

        @Test
        @DisplayName("P-extra: metadados ausentes na Notification resultam em mapa vazio para o provider")
        void shouldSendEmptyMetadataWhenNotificationHasNone() {
            when(notificationRepository.findById(notificationId))
                    .thenReturn(Optional.of(notification(null)));

            handler.handle(pushMessage("{\"notificationId\":\"" + notificationId + "\"}"));

            verify(notificationProvider).sendPushNotification(any(), any(), any(), metadataCaptor.capture());
            assertTrue(metadataCaptor.getValue().isEmpty());
        }
    }

    @Nested
    @DisplayName("Falhas permanentes decididas pelo handler")
    class PermanentFailures {

        @Test
        @DisplayName("P.4: notificação inexistente é falha PERMANENTE — provider nunca é chamado")
        void shouldFailPermanentlyWhenNotificationDoesNotExist() {
            when(notificationRepository.findById(notificationId)).thenReturn(Optional.empty());

            OutboxPermanentException ex = assertThrows(OutboxPermanentException.class,
                    () -> handler.handle(pushMessage("{\"notificationId\":\"" + notificationId + "\"}")));

            assertTrue(ex.getMessage().contains(notificationId.toString()));
            verify(notificationProvider, never()).sendPushNotification(any(), any(), any(), any());
        }

        @Test
        @DisplayName("P.7: payload sem notificationId é falha PERMANENTE")
        void shouldFailPermanentlyWhenPayloadHasNoNotificationId() {
            OutboxPermanentException ex = assertThrows(OutboxPermanentException.class,
                    () -> handler.handle(pushMessage("{\"other\":\"valor\"}")));

            assertTrue(ex.getMessage().contains("notificationId"));
            verifyNoInteractions(notificationRepository, notificationProvider);
        }

        @Test
        @DisplayName("P.8: payload malformado (JSON inválido) é falha PERMANENTE")
        void shouldFailPermanentlyWhenPayloadIsMalformed() {
            assertThrows(OutboxPermanentException.class,
                    () -> handler.handle(pushMessage("{nao é json")));

            verifyNoInteractions(notificationRepository, notificationProvider);
        }

        @Test
        @DisplayName("P-extra: payload vazio e notificationId não-UUID são falhas PERMANENTES")
        void shouldFailPermanentlyOnEmptyPayloadAndNonUuidId() {
            assertThrows(OutboxPermanentException.class, () -> handler.handle(pushMessage("")));
            assertThrows(OutboxPermanentException.class,
                    () -> handler.handle(pushMessage("{\"notificationId\":\"nao-um-uuid\"}")));
        }

        @Test
        @DisplayName("P-extra: metadados da Notification ilegíveis são falha PERMANENTE — retry não repara conteúdo")
        void shouldFailPermanentlyWhenNotificationMetadataIsMalformed() {
            when(notificationRepository.findById(notificationId))
                    .thenReturn(Optional.of(notification("{metadados quebrados")));

            assertThrows(OutboxPermanentException.class,
                    () -> handler.handle(pushMessage("{\"notificationId\":\"" + notificationId + "\"}")));

            verify(notificationProvider, never()).sendPushNotification(any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("Exceções do provider propagam sem captura")
    class ProviderExceptionPropagation {

        @Test
        @DisplayName("P.5: provider lançando transiente propaga como transiente — handler não converte em permanente")
        void shouldPropagateTransientProviderExceptionAsTransient() {
            when(notificationRepository.findById(notificationId))
                    .thenReturn(Optional.of(notification(null)));
            OutboxTransientException transientFailure = new OutboxTransientException("provider indisponível");
            doThrow(transientFailure).when(notificationProvider)
                    .sendPushNotification(any(), any(), any(), any());

            OutboxTransientException thrown = assertThrows(OutboxTransientException.class,
                    () -> handler.handle(pushMessage("{\"notificationId\":\"" + notificationId + "\"}")));

            assertSame(transientFailure, thrown, "Exceção transiente deve propagar intacta para o classificador");
        }

        @Test
        @DisplayName("P.6: provider lançando permanente propaga como permanente")
        void shouldPropagatePermanentProviderExceptionAsPermanent() {
            when(notificationRepository.findById(notificationId))
                    .thenReturn(Optional.of(notification(null)));
            OutboxPermanentException permanentFailure = new OutboxPermanentException("destinatário inválido");
            doThrow(permanentFailure).when(notificationProvider)
                    .sendPushNotification(any(), any(), any(), any());

            OutboxPermanentException thrown = assertThrows(OutboxPermanentException.class,
                    () -> handler.handle(pushMessage("{\"notificationId\":\"" + notificationId + "\"}")));

            assertSame(permanentFailure, thrown, "Exceção permanente deve propagar intacta para o classificador");
        }
    }

    @Nested
    @DisplayName("Anonimato")
    class Anonymity {

        @Test
        @DisplayName("P.10: actorId null na Notification permanece null nos metadados enviados ao provider")
        void shouldPreserveNullActorIdInMetadata() {
            String reviewId = UUID.randomUUID().toString();
            when(notificationRepository.findById(notificationId))
                    .thenReturn(Optional.of(notification("{\"actorId\":null,\"referenceId\":\"" + reviewId + "\"}")));

            handler.handle(pushMessage("{\"notificationId\":\"" + notificationId + "\"}"));

            verify(notificationProvider).sendPushNotification(any(), any(), any(), metadataCaptor.capture());
            Map<String, String> sentMetadata = metadataCaptor.getValue();
            assertTrue(sentMetadata.containsKey("actorId"), "Chave actorId preservada");
            assertNull(sentMetadata.get("actorId"), "actorId deve permanecer null (anonimato)");
            assertEquals(reviewId, sentMetadata.get("referenceId"));
        }

        @Test
        @DisplayName("P-extra: handler não injeta identidade — nenhum ID de usuário aparece nos metadados enviados")
        void shouldNeverInjectUserIdentityIntoMetadata() {
            when(notificationRepository.findById(notificationId))
                    .thenReturn(Optional.of(notification("{\"actorId\":null,\"referenceId\":\"" + UUID.randomUUID() + "\"}")));

            handler.handle(pushMessage("{\"notificationId\":\"" + notificationId + "\"}"));

            verify(notificationProvider).sendPushNotification(eq(recipientId), any(), any(), metadataCaptor.capture());
            String allMetadataValues = String.join(",", metadataCaptor.getValue().values());
            assertFalse(allMetadataValues.contains(recipientId.toString()),
                    "recipientId não pode vazar para os metadados");
            assertFalse(metadataCaptor.getValue().containsValue(notificationId.toString()),
                    "notificationId não pode virar identidade de ator");
        }
    }
}
