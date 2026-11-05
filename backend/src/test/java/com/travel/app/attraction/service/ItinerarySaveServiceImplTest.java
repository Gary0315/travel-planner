package com.travel.app.attraction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.app.attraction.dto.ShareIdResponse;
import com.travel.app.attraction.entity.ItineraryEntity;
import com.travel.app.attraction.repository.ItineraryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ItinerarySaveServiceImplTest {

    private static final String VALID_TRIP_PLAN_JSON = """
            {
              "title": "Test Trip",
              "summary": null,
              "days": [
                {
                  "day": 1,
                  "theme": null,
                  "activities": [
                    {
                      "time": "09:00 - 10:00",
                      "location": "Loc",
                      "description": "d",
                      "transport": "步行 5 分鐘",
                      "category": "景點",
                      "emoji": "🏛️"
                    }
                  ],
                  "hotels": null
                }
              ]
            }
            """;

    private ItineraryRepository repository;
    private ItinerarySaveServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(ItineraryRepository.class);
        service = new ItinerarySaveServiceImpl(repository, new ObjectMapper());
    }

    @Nested
    class FindByShareId {

        @Test
        void throws404WhenShareIdNotFound() {
            when(repository.findByShareId("missing")).thenReturn(Optional.empty());

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.findByShareId("missing"));

            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        }

        @Test
        void touchesAccessTimeWhenLastAccessedOverOneHourAgo() {
            ItineraryEntity entity = mock(ItineraryEntity.class);
            when(entity.getLastAccessedAt()).thenReturn(LocalDateTime.now().minusHours(2));
            when(entity.getContent()).thenReturn(VALID_TRIP_PLAN_JSON);
            when(repository.findByShareId("abc")).thenReturn(Optional.of(entity));

            service.findByShareId("abc");

            verify(entity).touchAccessTime();
        }

        @Test
        void touchesAccessTimeAtBoundaryJustOverOneHour() {
            ItineraryEntity entity = mock(ItineraryEntity.class);
            when(entity.getLastAccessedAt()).thenReturn(LocalDateTime.now().minusHours(1).minusMinutes(1));
            when(entity.getContent()).thenReturn(VALID_TRIP_PLAN_JSON);
            when(repository.findByShareId("abc")).thenReturn(Optional.of(entity));

            service.findByShareId("abc");

            verify(entity).touchAccessTime();
        }

        @Test
        void doesNotTouchWhenRecentlyAccessed() {
            ItineraryEntity entity = mock(ItineraryEntity.class);
            when(entity.getLastAccessedAt()).thenReturn(LocalDateTime.now().minusMinutes(30));
            when(entity.getContent()).thenReturn(VALID_TRIP_PLAN_JSON);
            when(repository.findByShareId("abc")).thenReturn(Optional.of(entity));

            service.findByShareId("abc");

            verify(entity, never()).touchAccessTime();
        }

        @Test
        void doesNotTouchWhenAccessedJustNow() {
            ItineraryEntity entity = mock(ItineraryEntity.class);
            when(entity.getLastAccessedAt()).thenReturn(LocalDateTime.now());
            when(entity.getContent()).thenReturn(VALID_TRIP_PLAN_JSON);
            when(repository.findByShareId("abc")).thenReturn(Optional.of(entity));

            service.findByShareId("abc");

            verify(entity, never()).touchAccessTime();
        }

        @Test
        void throws500WhenContentCannotBeParsed() {
            ItineraryEntity entity = mock(ItineraryEntity.class);
            when(entity.getLastAccessedAt()).thenReturn(LocalDateTime.now());
            when(entity.getContent()).thenReturn("{ not valid json");
            when(repository.findByShareId("abc")).thenReturn(Optional.of(entity));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.findByShareId("abc"));

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, ex.getStatusCode());
        }

        @Test
        void returnsDeserializedTripPlan() {
            ItineraryEntity entity = mock(ItineraryEntity.class);
            when(entity.getLastAccessedAt()).thenReturn(LocalDateTime.now());
            when(entity.getContent()).thenReturn(VALID_TRIP_PLAN_JSON);
            when(repository.findByShareId("abc")).thenReturn(Optional.of(entity));

            var plan = service.findByShareId("abc");

            assertEquals("Test Trip", plan.title());
            assertEquals(1, plan.days().size());
            assertEquals("Loc", plan.days().get(0).activities().get(0).location());
        }
    }

    @Nested
    class Save {

        @Test
        void returnsShareIdAndDeleteTokenForAnonymousSave() {
            var plan = new com.travel.app.attraction.dto.TripPlan("T", null, java.util.List.of());

            ShareIdResponse result = service.save(plan, null);

            // Both are UUIDs（36 字元 + 4 個破折號）
            assertEquals(36, result.shareId().length());
            assertEquals(36, result.deleteToken().length());
            assertNotNull(result.deleteToken());
            // shareId 與 deleteToken 必須不同
            assertEquals(false, result.shareId().equals(result.deleteToken()));
            verify(repository).save(any(ItineraryEntity.class));
        }

        @Test
        void passesUserIdToEntityWhenProvided() {
            var captor = org.mockito.ArgumentCaptor.forClass(ItineraryEntity.class);
            var plan = new com.travel.app.attraction.dto.TripPlan("T", null, java.util.List.of());

            service.save(plan, 42L);

            verify(repository).save(captor.capture());
            assertEquals(42L, captor.getValue().getUserId());
        }

        @Test
        void passesNullUserIdWhenAnonymous() {
            var captor = org.mockito.ArgumentCaptor.forClass(ItineraryEntity.class);
            var plan = new com.travel.app.attraction.dto.TripPlan("T", null, java.util.List.of());

            service.save(plan, null);

            verify(repository).save(captor.capture());
            assertEquals(null, captor.getValue().getUserId());
        }
    }

    @Nested
    class DeleteByShareId {

        @Test
        void throws404WhenShareIdNotFound() {
            when(repository.findByShareId("missing")).thenReturn(Optional.empty());

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.deleteByShareId("missing", "any-token"));

            assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
            verify(repository, never()).delete(any(ItineraryEntity.class));
        }

        @Test
        void throws403WhenTokenIsInvalid() {
            ItineraryEntity entity = mock(ItineraryEntity.class);
            when(entity.getDeleteTokenHash()).thenReturn(sha256("correct-token"));
            when(repository.findByShareId("abc")).thenReturn(Optional.of(entity));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.deleteByShareId("abc", "wrong-token"));

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
            verify(repository, never()).delete(any(ItineraryEntity.class));
        }

        @Test
        void throws403WhenLegacyEntityHasNoTokenHash() {
            ItineraryEntity entity = mock(ItineraryEntity.class);
            when(entity.getDeleteTokenHash()).thenReturn(null);
            when(repository.findByShareId("abc")).thenReturn(Optional.of(entity));

            ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                    () -> service.deleteByShareId("abc", "any-token"));

            assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
            verify(repository, never()).delete(any(ItineraryEntity.class));
        }

        @Test
        void throws403WhenLegacyEntityHasBlankTokenHash() {
            ItineraryEntity entity = mock(ItineraryEntity.class);
            when(entity.getDeleteTokenHash()).thenReturn("   ");
            when(repository.findByShareId("abc")).thenReturn(Optional.of(entity));

            assertThrows(ResponseStatusException.class,
                    () -> service.deleteByShareId("abc", "any-token"));
            verify(repository, never()).delete(any(ItineraryEntity.class));
        }

        @Test
        void deletesWhenTokenMatches() {
            ItineraryEntity entity = mock(ItineraryEntity.class);
            when(entity.getDeleteTokenHash()).thenReturn(sha256("correct-token"));
            when(repository.findByShareId("abc")).thenReturn(Optional.of(entity));

            service.deleteByShareId("abc", "correct-token");

            verify(repository).delete(entity);
        }
    }

    @Nested
    class ListMine {

        @Test
        void returnsEmptyListWhenUserHasNoTrips() {
            when(repository.findByUserIdOrderByCreatedAtDesc(99L)).thenReturn(java.util.List.of());

            var result = service.listMine(99L);

            assertEquals(0, result.size());
        }

        @Test
        void mapsEntityToCardResponseWithMetadata() {
            ItineraryEntity entity = mock(ItineraryEntity.class);
            when(entity.getShareId()).thenReturn("share-1");
            when(entity.getContent()).thenReturn(VALID_TRIP_PLAN_JSON);
            when(entity.getCreatedAt()).thenReturn(LocalDateTime.of(2025, 1, 1, 12, 0));
            when(repository.findByUserIdOrderByCreatedAtDesc(7L)).thenReturn(java.util.List.of(entity));

            var result = service.listMine(7L);

            assertEquals(1, result.size());
            var card = result.get(0);
            assertEquals("share-1", card.shareId());
            assertEquals("Test Trip", card.title());
            assertEquals(1, card.days());
            assertEquals(1, card.places()); // VALID_TRIP_PLAN_JSON 只有 1 個 activity
            assertEquals("🏛️", card.coverEmoji()); // 來自第一個 activity 的 emoji
        }

        @Test
        void fallsBackWhenJsonContentCannotBeParsed() {
            ItineraryEntity entity = mock(ItineraryEntity.class);
            when(entity.getShareId()).thenReturn("share-broken");
            when(entity.getContent()).thenReturn("{ not valid json");
            when(entity.getCreatedAt()).thenReturn(LocalDateTime.of(2025, 1, 1, 12, 0));
            when(repository.findByUserIdOrderByCreatedAtDesc(8L)).thenReturn(java.util.List.of(entity));

            var result = service.listMine(8L);

            // 不該丟例外；給 fallback 卡片讓其他正常 row 仍可顯示
            assertEquals(1, result.size());
            assertEquals("share-broken", result.get(0).shareId());
            assertEquals("（無法載入）", result.get(0).title());
            assertEquals(0, result.get(0).days());
            assertEquals("🎒", result.get(0).coverEmoji());
        }
    }

    @Nested
    class SaveAndDeleteRoundTrip {

        /**
         * 確保 save 回傳的明文 deleteToken 在 deleteByShareId 端能正確驗證通過。
         * 用 ArgumentCaptor 抓出 save 寫入的 entity（含 hash），手動回灌到 findByShareId mock。
         */
        @Test
        void tokenFromSaveCanDeleteSameEntity() {
            var captor = org.mockito.ArgumentCaptor.forClass(ItineraryEntity.class);
            var plan = new com.travel.app.attraction.dto.TripPlan("T", null, java.util.List.of());

            ShareIdResponse result = service.save(plan, null);

            verify(repository).save(captor.capture());
            ItineraryEntity saved = captor.getValue();
            // mock 一個有同樣 hash 的 entity 給 deleteByShareId 用
            ItineraryEntity returnedForDelete = mock(ItineraryEntity.class);
            when(returnedForDelete.getDeleteTokenHash()).thenReturn(saved.getDeleteTokenHash());
            when(repository.findByShareId(result.shareId())).thenReturn(Optional.of(returnedForDelete));

            // 不應拋例外
            service.deleteByShareId(result.shareId(), result.deleteToken());

            verify(repository).delete(returnedForDelete);
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
