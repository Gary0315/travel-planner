package com.travel.app.shared.quota;

import org.springframework.dao.DataIntegrityViolationException;

import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 測試用：把 {@link ApiCallCounterRepository} 的 mock 接上一份記憶體裡的「資料表」，
 * 照 repository 三個方法的語意回應 —— UPDATE 只在列存在且未達上限時加一、INSERT 撞主鍵丟例外。
 *
 * <p>給不想起資料庫、但需要計數器真的會數的測試用（攔截器、Gemini service、{@code @WebMvcTest} 切片）。
 * 真實 SQL 的行為由 {@link PersistentCallCounterTest} 對 H2 驗，這裡只是讓上層測試有一個會數數的計數器。
 */
public final class InMemoryCounterRows {

    private final Map<ApiCallCounterId, Integer> rows = new HashMap<>();
    private final ApiCallCounterRepository repository;

    /** 自己建一個 mock。 */
    public InMemoryCounterRows() {
        this(mock(ApiCallCounterRepository.class));
    }

    /** 接在既有的 mock 上，例如 {@code @MockBean} 注入的那一個（每個測試前要重新接：{@code @MockBean} 每測重置）。 */
    public InMemoryCounterRows(ApiCallCounterRepository mockRepository) {
        this.repository = mockRepository;
        when(mockRepository.incrementIfBelowLimit(anyString(), anyString(), anyInt(), any())).thenAnswer(invocation -> {
            ApiCallCounterId id = new ApiCallCounterId(invocation.<String>getArgument(0), invocation.<String>getArgument(1));
            int maxCount = invocation.getArgument(2);
            synchronized (rows) {
                Integer used = rows.get(id);
                if (used == null || used >= maxCount) {
                    return 0;
                }
                rows.put(id, used + 1);
                return 1;
            }
        });
        when(mockRepository.existsById(any())).thenAnswer(invocation -> {
            synchronized (rows) {
                return rows.containsKey(invocation.<ApiCallCounterId>getArgument(0));
            }
        });
        when(mockRepository.insertZeroRow(anyString(), anyString(), any())).thenAnswer(invocation -> {
            ApiCallCounterId id = new ApiCallCounterId(invocation.<String>getArgument(0), invocation.<String>getArgument(1));
            synchronized (rows) {
                if (rows.putIfAbsent(id, 0) != null) {
                    throw new DataIntegrityViolationException("duplicate key " + id);
                }
                return 1;
            }
        });
    }

    public ApiCallCounterRepository repository() {
        return repository;
    }

    /** 某個計數器某一期已記上的次數；沒有這一列回 0。 */
    public int used(String counterName, String periodKey) {
        synchronized (rows) {
            return rows.getOrDefault(new ApiCallCounterId(counterName, periodKey), 0);
        }
    }

    public int rowCount() {
        synchronized (rows) {
            return rows.size();
        }
    }
}
