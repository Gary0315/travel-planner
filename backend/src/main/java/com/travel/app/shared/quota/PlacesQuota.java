package com.travel.app.shared.quota;

import java.util.EnumMap;
import java.util.Map;

/**
 * Places 四個呼叫點的每月上限。兩個 service 只注入這一個 bean，用 {@link PlacesCallPoint} 指明自己是哪個呼叫點。
 *
 * <p>呼叫端要把檢查放在真正發出 HTTP 的前一刻：快取命中不該消耗額度；而只要發出去就算一次，
 * 不論 Google 回什麼（{@code ZERO_RESULTS} 也計費，SPEC §11.2）。
 */
public class PlacesQuota {

    private final Map<PlacesCallPoint, PersistentCallCounter> counters;

    /**
     * @throws IllegalArgumentException 任何一個呼叫點沒有計數器。日後新增呼叫點卻忘了接設定時，
     *         讓服務在啟動時就失敗，而不是等到執行到那一行才發現沒人在數
     */
    public PlacesQuota(Map<PlacesCallPoint, PersistentCallCounter> counters) {
        EnumMap<PlacesCallPoint, PersistentCallCounter> copy = new EnumMap<>(PlacesCallPoint.class);
        copy.putAll(counters);
        for (PlacesCallPoint point : PlacesCallPoint.values()) {
            if (copy.get(point) == null) {
                throw new IllegalArgumentException("Places 呼叫點 " + point + " 沒有對應的每月計數器");
            }
        }
        this.counters = copy;
    }

    /** 要對 {@code point} 發出一次呼叫之前先呼叫；false 表示不應發出（語意同 {@link PersistentCallCounter#tryAcquire()}）。 */
    public boolean tryAcquire(PlacesCallPoint point) {
        return counters.get(point).tryAcquire();
    }

    /** 讀取某個呼叫點的計數器（名稱、上限），給設定檢查與測試用。 */
    public PersistentCallCounter counter(PlacesCallPoint point) {
        return counters.get(point);
    }
}
