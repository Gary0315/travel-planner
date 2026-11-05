package com.travel.app.shared.quota;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * {@code api_call_counter} 的存取。計數只走這裡的兩個寫入方法，刻意不用 {@code save()}：
 * 主鍵是自己指定的（名稱 + 期間），{@code save()} 會走 merge —— 先 SELECT 再決定 INSERT 或 UPDATE，
 * 並發時可能把別人剛加上去的次數覆寫回 0。
 *
 * <p><b>交易邊界</b>：兩個方法各自加 {@code @Transactional}，每次呼叫各自一個交易
 * （介面上宣告的查詢方法沒有預設交易，少了會在執行時丟 {@code TransactionRequiredException}）。
 * 呼叫端 {@link PersistentCallCounter} 本身<b>不能</b>包 {@code @Transactional}：INSERT 撞主鍵失敗時，
 * PostgreSQL 會把整個交易標成 aborted，同一交易裡後面的 UPDATE 全部失敗；
 * 各自一個交易，才能「插入失敗 → 換一個新交易重做 UPDATE」。
 */
public interface ApiCallCounterRepository extends JpaRepository<ApiCallCounterEntity, ApiCallCounterId> {

    /**
     * 未達上限就加一 —— 比較與加一在同一句 SQL 裡完成，並發也不會超過上限（理由見 {@link PersistentCallCounter}）。
     *
     * <p>綁定名稱用 {@code maxCount} 而不是 {@code limit}：LIMIT 是 HQL 的關鍵字。
     *
     * @return 1 = 已記上一次；0 = 這一列還不存在，或已達上限（呼叫端用 {@code existsById} 區分）
     */
    @Modifying
    @Transactional
    @Query("""
            UPDATE ApiCallCounterEntity c
               SET c.usedCount = c.usedCount + 1, c.updatedAt = :now
             WHERE c.id.counterName = :name
               AND c.id.periodKey = :period
               AND c.usedCount < :maxCount
            """)
    int incrementIfBelowLimit(@Param("name") String name,
                              @Param("period") String period,
                              @Param("maxCount") int limit,
                              @Param("now") LocalDateTime now);

    /**
     * 建立本期的第一列（次數 0）。撞主鍵時丟 {@code DataIntegrityViolationException} ——
     * 代表別的請求剛好先建好了，呼叫端忽略即可。
     *
     * <p>不用 PostgreSQL 的 {@code ON CONFLICT DO NOTHING}：本機與測試跑的是 H2，
     * 選兩邊都能跑的標準 INSERT，撞主鍵交給例外處理。
     */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO api_call_counter (counter_name, period_key, used_count, updated_at)
            VALUES (:name, :period, 0, :now)
            """, nativeQuery = true)
    int insertZeroRow(@Param("name") String name,
                      @Param("period") String period,
                      @Param("now") LocalDateTime now);
}
