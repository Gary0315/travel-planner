package com.travel.app.shared.config;

import com.travel.app.shared.quota.PersistentCallCounter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.util.Objects;

/**
 * 全站每日額度守門 —— 不分 IP 的單一計數器，只掛在會燒外部 API 額度的端點。
 *
 * <p>與 per-IP 的 {@link RateLimitInterceptor} 分工：per-IP 擋「同一個人狂點」，
 * 本攔截器擋「所有人加起來把 Gemini / Places 免費額度用光」。per-IP 靠
 * X-Forwarded-For 判定來源、可被偽造繞過；本計數器不看任何 header，繞不掉。
 *
 * <p>計數存資料庫（{@link PersistentCallCounter}，每日一列），Render 休眠醒來不歸零。
 * 換日時區與「資料庫數不了時放行」的理由見 {@link WebConfig#dailyQuotaInterceptor()}。
 */
public class DailyQuotaInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(DailyQuotaInterceptor.class);

    private final PersistentCallCounter counter;

    public DailyQuotaInterceptor(PersistentCallCounter counter) {
        this.counter = Objects.requireNonNull(counter, "counter");
    }

    @Override
    public boolean preHandle(HttpServletRequest request,
                             HttpServletResponse response,
                             Object handler) throws IOException {
        if (counter.tryAcquire()) {
            return true;
        }
        // 額度耗盡是需要人介入的事件（調高上限或查是否被濫用），用 WARN 讓它在 Render log 裡看得見。
        // 不記 IP / prompt —— 依 SPEC §11 log 政策，這裡沒有診斷上非要不可的個資。
        log.warn("每日額度已用盡（上限 {} 次/日），拒絕請求：{}", counter.limit(), request.getRequestURI());
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        // 刻意不帶 retryAfterSeconds：前端看到它會啟動秒數倒數，而這裡要等的是「到明天」，
        // 倒數幾萬秒沒有意義。改用 errorCode 讓前端走另一條文案分支。
        response.getWriter().write(
                "{\"message\": \"今天的行程生成次數已達上限，請明天再試\", \"errorCode\": \"DAILY_QUOTA_EXCEEDED\"}"
        );
        return false;
    }

    /** 給設定檢查與測試用：確認接上的是哪個計數器、上限多少。 */
    PersistentCallCounter counter() {
        return counter;
    }
}
