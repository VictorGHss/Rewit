package com.rewit.domain.model;

import com.rewit.common.exception.BusinessException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Domínio: Horários de Funcionamento (F2.1)")
class PlaceOpeningHoursDomainTest {

    @Nested
    @DisplayName("OpeningHoursInterval")
    class OpeningHoursIntervalTest {

        @Test
        @DisplayName("Deve instanciar intervalo válido [openTime, closeTime)")
        void shouldCreateValidInterval() {
            OpeningHoursInterval interval = new OpeningHoursInterval(
                    LocalTime.of(8, 0),
                    LocalTime.of(12, 0)
            );
            assertEquals(LocalTime.of(8, 0), interval.getOpenTime());
            assertEquals(LocalTime.of(12, 0), interval.getCloseTime());
        }

        @Test
        @DisplayName("Deve rejeitar quando openTime for nulo ou closeTime for nulo")
        void shouldRejectNullTimes() {
            BusinessException ex1 = assertThrows(BusinessException.class,
                    () -> new OpeningHoursInterval(null, LocalTime.of(12, 0)));
            assertEquals("INVALID_OPENING_HOURS_INTERVAL", ex1.getErrorCode());

            BusinessException ex2 = assertThrows(BusinessException.class,
                    () -> new OpeningHoursInterval(LocalTime.of(8, 0), null));
            assertEquals("INVALID_OPENING_HOURS_INTERVAL", ex2.getErrorCode());
        }

        @Test
        @DisplayName("Deve rejeitar quando openTime >= closeTime")
        void shouldRejectInvertedOrEqualTimes() {
            BusinessException ex1 = assertThrows(BusinessException.class,
                    () -> new OpeningHoursInterval(LocalTime.of(12, 0), LocalTime.of(8, 0)));
            assertEquals("INVALID_OPENING_HOURS_INTERVAL", ex1.getErrorCode());

            BusinessException ex2 = assertThrows(BusinessException.class,
                    () -> new OpeningHoursInterval(LocalTime.of(10, 0), LocalTime.of(10, 0)));
            assertEquals("INVALID_OPENING_HOURS_INTERVAL", ex2.getErrorCode());
        }

        @Test
        @DisplayName("Deve detectar sobreposição de intervalos corretamente")
        void shouldDetectOverlapCorrectly() {
            OpeningHoursInterval morning = new OpeningHoursInterval(LocalTime.of(8, 0), LocalTime.of(12, 0));
            OpeningHoursInterval afternoon = new OpeningHoursInterval(LocalTime.of(13, 0), LocalTime.of(18, 0));
            OpeningHoursInterval adjacent = new OpeningHoursInterval(LocalTime.of(12, 0), LocalTime.of(14, 0));
            OpeningHoursInterval overlapping = new OpeningHoursInterval(LocalTime.of(11, 0), LocalTime.of(13, 0));
            OpeningHoursInterval enclosed = new OpeningHoursInterval(LocalTime.of(9, 0), LocalTime.of(11, 0));

            // Intervalos separados não se sobrepõem
            assertFalse(morning.overlapsWith(afternoon));
            assertFalse(afternoon.overlapsWith(morning));

            // Intervalos adjacentes não se sobrepõem no modelo semiaberto [open, close)
            assertFalse(morning.overlapsWith(adjacent));
            assertFalse(adjacent.overlapsWith(morning));

            // Intervalos com interseção se sobrepõem
            assertTrue(morning.overlapsWith(overlapping));
            assertTrue(overlapping.overlapsWith(morning));

            // Intervalo contido se sobrepõe
            assertTrue(morning.overlapsWith(enclosed));
            assertTrue(enclosed.overlapsWith(morning));

            // Intervalo com ele mesmo se sobrepõe
            assertTrue(morning.overlapsWith(morning));

            // Nulo não se sobrepõe
            assertFalse(morning.overlapsWith(null));
        }
    }

    @Nested
    @DisplayName("PlaceDaySchedule")
    class PlaceDayScheduleTest {

        @Test
        @DisplayName("Deve instanciar dia fechado sem intervalos")
        void shouldCreateClosedDay() {
            PlaceDaySchedule day = PlaceDaySchedule.closed(7);
            assertEquals(7, day.getDayOfWeek());
            assertTrue(day.isClosed());
            assertTrue(day.getIntervals().isEmpty());
        }

        @Test
        @DisplayName("Deve rejeitar dia fechado contendo intervalos")
        void shouldRejectClosedDayWithIntervals() {
            List<OpeningHoursInterval> intervals = List.of(
                    new OpeningHoursInterval(LocalTime.of(8, 0), LocalTime.of(12, 0))
            );
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> new PlaceDaySchedule(7, true, intervals));
            assertEquals("INVALID_OPENING_HOURS", ex.getErrorCode());
        }

        @Test
        @DisplayName("Deve rejeitar dia aberto sem intervalos")
        void shouldRejectOpenDayWithoutIntervals() {
            BusinessException ex1 = assertThrows(BusinessException.class,
                    () -> new PlaceDaySchedule(1, false, List.of()));
            assertEquals("INVALID_OPENING_HOURS", ex1.getErrorCode());

            BusinessException ex2 = assertThrows(BusinessException.class,
                    () -> new PlaceDaySchedule(1, false, null));
            assertEquals("INVALID_OPENING_HOURS", ex2.getErrorCode());
        }

        @Test
        @DisplayName("Deve rejeitar dia da semana fora da faixa 1..7")
        void shouldRejectInvalidDayOfWeek() {
            BusinessException ex0 = assertThrows(BusinessException.class,
                    () -> PlaceDaySchedule.closed(0));
            assertEquals("INVALID_DAY_OF_WEEK", ex0.getErrorCode());

            BusinessException ex8 = assertThrows(BusinessException.class,
                    () -> PlaceDaySchedule.closed(8));
            assertEquals("INVALID_DAY_OF_WEEK", ex8.getErrorCode());
        }

        @Test
        @DisplayName("Deve rejeitar múltiplos intervalos sobrepostos no mesmo dia")
        void shouldRejectOverlappingIntervalsInDay() {
            List<OpeningHoursInterval> intervals = List.of(
                    new OpeningHoursInterval(LocalTime.of(8, 0), LocalTime.of(12, 0)),
                    new OpeningHoursInterval(LocalTime.of(11, 0), LocalTime.of(15, 0))
            );
            BusinessException ex = assertThrows(BusinessException.class,
                    () -> PlaceDaySchedule.open(1, intervals));
            assertEquals("OPENING_HOURS_OVERLAPPING_INTERVALS", ex.getErrorCode());
        }

        @Test
        @DisplayName("Deve aceitar múltiplos intervalos não sobrepostos e ordená-los")
        void shouldAcceptAndSortMultipleIntervals() {
            OpeningHoursInterval lunch = new OpeningHoursInterval(LocalTime.of(11, 30), LocalTime.of(14, 0));
            OpeningHoursInterval dinner = new OpeningHoursInterval(LocalTime.of(18, 0), LocalTime.of(22, 30));
            OpeningHoursInterval breakfast = new OpeningHoursInterval(LocalTime.of(7, 0), LocalTime.of(9, 0));

            PlaceDaySchedule day = PlaceDaySchedule.open(1, List.of(dinner, lunch, breakfast));
            assertFalse(day.isClosed());
            assertEquals(3, day.getIntervals().size());
            assertEquals(breakfast, day.getIntervals().get(0));
            assertEquals(lunch, day.getIntervals().get(1));
            assertEquals(dinner, day.getIntervals().get(2));
        }
    }

    @Nested
    @DisplayName("PlaceOpeningHoursSchedule")
    class PlaceOpeningHoursScheduleTest {

        private final UUID placeId = UUID.randomUUID();
        private final UUID businessAccountId = UUID.randomUUID();
        private final UUID actorUserId = UUID.randomUUID();

        @Test
        @DisplayName("Deve criar grade semanal completa preenchendo dias ausentes como fechados")
        void shouldFillMissingDaysAsClosed() {
            PlaceDaySchedule monday = PlaceDaySchedule.open(1, List.of(
                    new OpeningHoursInterval(LocalTime.of(8, 0), LocalTime.of(18, 0))
            ));

            PlaceOpeningHoursSchedule schedule = PlaceOpeningHoursSchedule.create(
                    placeId,
                    businessAccountId,
                    actorUserId,
                    List.of(monday)
            );

            assertEquals(placeId, schedule.getPlaceId());
            assertEquals(businessAccountId, schedule.getBusinessAccountId());
            assertEquals(actorUserId, schedule.getUpdatedByUserId());
            assertNotNull(schedule.getCreatedAt());
            assertNotNull(schedule.getUpdatedAt());

            List<PlaceDaySchedule> weekly = schedule.getWeeklySchedule();
            assertEquals(7, weekly.size());

            // Segunda-feira (1) aberta
            assertFalse(schedule.isClosedOn(1));
            assertEquals(1, schedule.getDay(1).getIntervals().size());

            // Demais dias (2 a 7) fechados
            for (int d = 2; d <= 7; d++) {
                assertTrue(schedule.isClosedOn(d));
                assertTrue(schedule.getDay(d).isClosed());
                assertTrue(schedule.getDay(d).getIntervals().isEmpty());
            }
        }

        @Test
        @DisplayName("Deve rejeitar dias duplicados na requisição")
        void shouldRejectDuplicateDays() {
            PlaceDaySchedule monday1 = PlaceDaySchedule.open(1, List.of(
                    new OpeningHoursInterval(LocalTime.of(8, 0), LocalTime.of(12, 0))
            ));
            PlaceDaySchedule monday2 = PlaceDaySchedule.open(1, List.of(
                    new OpeningHoursInterval(LocalTime.of(14, 0), LocalTime.of(18, 0))
            ));

            BusinessException ex = assertThrows(BusinessException.class,
                    () -> PlaceOpeningHoursSchedule.create(placeId, businessAccountId, actorUserId, List.of(monday1, monday2)));
            assertEquals("DUPLICATE_DAY_OF_WEEK", ex.getErrorCode());
        }

        @Test
        @DisplayName("Deve exigir identificadores obrigatórios")
        void shouldRequireMandatoryIds() {
            BusinessException ex1 = assertThrows(BusinessException.class,
                    () -> PlaceOpeningHoursSchedule.create(null, businessAccountId, actorUserId, List.of()));
            assertEquals("MISSING_PLACE_ID", ex1.getErrorCode());

            BusinessException ex2 = assertThrows(BusinessException.class,
                    () -> PlaceOpeningHoursSchedule.create(placeId, null, actorUserId, List.of()));
            assertEquals("MISSING_BUSINESS_ACCOUNT_ID", ex2.getErrorCode());

            BusinessException ex3 = assertThrows(BusinessException.class,
                    () -> PlaceOpeningHoursSchedule.create(placeId, businessAccountId, null, List.of()));
            assertEquals("MISSING_ACTOR_USER_ID", ex3.getErrorCode());
        }
    }
}

