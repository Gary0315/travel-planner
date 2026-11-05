package com.travel.app.attraction.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.app.attraction.dto.Activity;
import com.travel.app.attraction.dto.MyTripCardResponse;
import com.travel.app.attraction.dto.ShareIdResponse;
import com.travel.app.attraction.dto.TripPlan;
import com.travel.app.attraction.entity.ItineraryEntity;
import com.travel.app.attraction.repository.ItineraryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class ItinerarySaveServiceImpl implements ItinerarySaveService {

    private static final Logger log = LoggerFactory.getLogger(ItinerarySaveServiceImpl.class);
    private static final String DEFAULT_COVER_EMOJI = "🎒";

    private final ItineraryRepository repository;
    private final ObjectMapper objectMapper;

    public ItinerarySaveServiceImpl(ItineraryRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional
    public ShareIdResponse save(TripPlan tripPlan, Long userId) {
        try {
            String shareId = UUID.randomUUID().toString();
            String deleteToken = UUID.randomUUID().toString();
            String content = objectMapper.writeValueAsString(tripPlan);
            repository.save(new ItineraryEntity(shareId, content, sha256Hex(deleteToken), userId));
            return new ShareIdResponse(shareId, deleteToken);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to save itinerary", e);
        }
    }

    @Override
    @Transactional
    public TripPlan findByShareId(String shareId) {
        ItineraryEntity entity = repository.findByShareId(shareId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Itinerary not found"));
        // 每小時最多更新一次 lastAccessedAt，避免 hot share 每次 GET 都觸發 DB write
        if (entity.getLastAccessedAt().isBefore(LocalDateTime.now().minusHours(1))) {
            entity.touchAccessTime();
        }
        try {
            return objectMapper.readValue(entity.getContent(), TripPlan.class);
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to parse itinerary", e);
        }
    }

    @Override
    @Transactional
    public void deleteByShareId(String shareId, String deleteToken) {
        ItineraryEntity entity = repository.findByShareId(shareId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Itinerary not found"));

        // 舊資料沒 token hash → 不可刪除（避免他人猜 shareId 後嘗試刪除 legacy 行程）
        if (entity.getDeleteTokenHash() == null || entity.getDeleteTokenHash().isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Itinerary cannot be deleted");
        }

        // 比對 SHA-256 hash；不直接用明文比，DB 也只存 hash，防止 DB 外洩後 token 被濫用
        if (!entity.getDeleteTokenHash().equals(sha256Hex(deleteToken))) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Invalid delete token");
        }

        repository.delete(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<MyTripCardResponse> listMine(Long userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::toCardResponse)
                .toList();
    }

    /**
     * 把 ItineraryEntity 轉成卡片用的 metadata。
     * 解析 JSON 算 days / places / 封面 emoji；解析失敗回 fallback 避免整列表壞掉。
     */
    private MyTripCardResponse toCardResponse(ItineraryEntity entity) {
        try {
            TripPlan plan = objectMapper.readValue(entity.getContent(), TripPlan.class);
            int places = plan.days().stream()
                    .mapToInt(d -> d.activities() == null ? 0 : d.activities().size())
                    .sum();
            String coverEmoji = plan.days().stream()
                    .findFirst()
                    .filter(d -> d.activities() != null && !d.activities().isEmpty())
                    .map(d -> d.activities().get(0))
                    .map(Activity::emoji)
                    .filter(e -> e != null && !e.isBlank())
                    .orElse(DEFAULT_COVER_EMOJI);
            return new MyTripCardResponse(
                    entity.getShareId(),
                    plan.title(),
                    plan.summary(),
                    plan.days().size(),
                    places,
                    coverEmoji,
                    entity.getCreatedAt()
            );
        } catch (Exception e) {
            // legacy / 損壞資料：給最小可顯示卡片，不阻擋其他正常 row 渲染
            log.warn("Failed to parse trip content for shareId={}, returning fallback card", entity.getShareId(), e);
            return new MyTripCardResponse(
                    entity.getShareId(),
                    "（無法載入）",
                    null,
                    0,
                    0,
                    DEFAULT_COVER_EMOJI,
                    entity.getCreatedAt()
            );
        }
    }

    private static String sha256Hex(String input) {
        if (input == null) return "";
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 內建；理論上不會走到這
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
