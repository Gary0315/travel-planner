package com.travel.app.shared.quota;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

/**
 * {@link ApiCallCounterEntity} 的複合主鍵：計數器名稱 + 期間。
 *
 * <p>寫成一般 class（不用 record）、實作 {@link Serializable}、自己定義 equals/hashCode、
 * 留一個 protected 無參數建構子給 JPA —— 這是 JPA 對主鍵類別的要求，也是 Hibernate 6.6 上最保守、確定可行的寫法。
 */
@Embeddable
public class ApiCallCounterId implements Serializable {

    private static final long serialVersionUID = 1L;

    // 例如 places.hotel-nearby。改名等於換一個新的計數器，舊名稱累積的次數不會跟過來
    @Column(name = "counter_name", length = 64, nullable = false)
    private String counterName;

    // 每月計數是 2026-10，每日計數是 2026-10-14：字典序就是時間順序，人工查表也看得懂
    @Column(name = "period_key", length = 16, nullable = false)
    private String periodKey;

    protected ApiCallCounterId() {
        // 給 JPA 用
    }

    public ApiCallCounterId(String counterName, String periodKey) {
        this.counterName = counterName;
        this.periodKey = periodKey;
    }

    public String getCounterName() { return counterName; }
    public String getPeriodKey() { return periodKey; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ApiCallCounterId other)) return false;
        return Objects.equals(counterName, other.counterName)
                && Objects.equals(periodKey, other.periodKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(counterName, periodKey);
    }

    @Override
    public String toString() {
        return counterName + "@" + periodKey;
    }
}
