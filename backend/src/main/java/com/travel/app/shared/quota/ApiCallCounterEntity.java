package com.travel.app.shared.quota;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 對外 API 的呼叫次數：一列 = 一個計數器在一個期間內已記上的次數。
 *
 * <p>刻意做成通用表、不綁 Places：Places 四個每月計數、Gemini 每日計數與全站每日額度都存在這裡，
 * 差別只在 counter_name 與 period_key 的值。
 *
 * <p>欄位名全部用 {@code @Column(name = ...)} 明寫，刻意避開 H2 2.x 的保留字
 * （month、year、key、value、user、day 都是）—— 撞到時本機一啟動就建表失敗。
 *
 * <p>只有 getter、沒有公開建構子：這張表只准經由 {@link ApiCallCounterRepository} 的兩個寫入方法變動，
 * 不留「new 一個再 save()」的路（save 為什麼會出事，見該介面的說明）。
 */
@Entity
@Table(name = "api_call_counter")
public class ApiCallCounterEntity {

    @EmbeddedId
    private ApiCallCounterId id;

    @Column(name = "used_count", nullable = false)
    private int usedCount;

    // 計數器重置時區的當地時間，與 period_key 同一個時區 —— 一列的 updated_at 一定落在它自己的期間內
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ApiCallCounterEntity() {
        // 給 JPA 用
    }

    public ApiCallCounterId getId() { return id; }
    public int getUsedCount() { return usedCount; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
