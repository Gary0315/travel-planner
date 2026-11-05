import { useCallback, useEffect, useRef, useState } from 'react'
import { apiFetch } from './api'

/**
 * 冷啟動喚醒偵測（PLAN S2）：輪詢 GET /api/health，判斷 Render 免費方案的
 * 後端是不是還在 cold start（閒置約 15 分鐘會休眠，喚醒需 30–60 秒）。
 *
 * 兩個進入點，共用同一套輪詢：
 *   1. 掛載時自動探一輪 —— 開頁當下後端沒醒的情況。
 *   2. `ensureAwake()` —— 送出耗時請求（生成行程）前的閘門。頁面可能已經開很久、
 *      後端在這期間睡著了，載入時那輪的 'awake' 早就過期。不先喚醒就直接送 POST，
 *      那一個請求要同時扛「冷啟動 30–60s + AI 生成 20–60s」，會撞爆 Cloudflare
 *      免費方案的 100 秒 proxy 上限拿到 524（回傳的是 CF 的 HTML 錯誤頁，
 *      前端連後端的錯誤訊息都拿不到）。改成先用 health 把後端叫醒——單次探測
 *      10 秒自己 abort 重連，永遠碰不到那 100 秒——醒了才送真正的請求。
 *
 * 狀態機：
 *   pending → 探測中且未超過寬限期。熱機時 health 幾百 ms 就回，
 *             這段先不顯示橫幅，避免每次探測都閃一下「喚醒中」。
 *   waking  → 寬限期過了還沒回應 = 大概率在冷啟動。顯示橫幅 + 鎖生成按鈕。
 *   awake   → health 回 200，一切正常。
 *   failed  → 超過放棄時限仍無回應（伺服器真的掛了或網路不通）。橫幅改顯示失敗文案
 *             並解鎖按鈕——讓使用者可以手動再試，失敗會走既有的錯誤訊息路徑。
 */
export type WakeupStatus = 'pending' | 'waking' | 'awake' | 'failed'

const GRACE_MS = 1_500 // 超過這個時間沒回應才顯示橫幅
const ATTEMPT_TIMEOUT_MS = 10_000 // 單次探測逾時；冷啟動時請求可能被 Render 吊住，砍掉重連
const RETRY_DELAY_MS = 2_000 // 失敗後隔多久再探
const GIVE_UP_MS = 90_000 // 冷啟動實測 30–60s；90s 還沒醒視為異常，不再無限等

const sleep = (ms: number) => new Promise((resolve) => setTimeout(resolve, ms))

export function useServerWakeup(): {
  status: WakeupStatus
  elapsedSeconds: number
  ensureAwake: () => Promise<boolean>
} {
  const [status, setStatus] = useState<WakeupStatus>('pending')
  const [elapsedSeconds, setElapsedSeconds] = useState(0)

  // 進行中的那一輪。掛載探測與 ensureAwake 共用，避免同時跑兩輪輪詢重複打 health。
  const inFlightRef = useRef<Promise<boolean> | null>(null)
  // unmount 時 +1 讓執行中的那輪自己失效（StrictMode 的 mount→unmount→mount 也靠它）
  const generationRef = useRef(0)

  const runWakeupCycle = useCallback((): Promise<boolean> => {
    if (inFlightRef.current) return inFlightRef.current

    const myGen = generationRef.current
    const isCurrent = () => generationRef.current === myGen
    const startedAt = Date.now()

    // 每輪都從頭開始：ensureAwake 那輪若後端已睡，橫幅要重新從 0 秒數起
    setElapsedSeconds(0)
    setStatus('pending')

    const graceTimer = setTimeout(() => {
      if (isCurrent()) setStatus((s) => (s === 'pending' ? 'waking' : s))
    }, GRACE_MS)

    // 橫幅上的「已等待 N 秒」。settle 時清掉，避免醒來後 App 還在每秒重 render
    const elapsedTimer = setInterval(() => {
      if (isCurrent()) setElapsedSeconds(Math.round((Date.now() - startedAt) / 1000))
    }, 1_000)

    const settle = (next: WakeupStatus) => {
      clearTimeout(graceTimer)
      clearInterval(elapsedTimer)
      if (isCurrent()) setStatus(next)
    }

    const cycle = (async () => {
      while (isCurrent()) {
        const controller = new AbortController()
        const abortTimer = setTimeout(() => controller.abort(), ATTEMPT_TIMEOUT_MS)
        try {
          const res = await apiFetch('/api/health', { signal: controller.signal })
          if (res.ok) {
            settle('awake')
            return true
          }
          // 非 2xx（冷啟動中 Render 可能回 502/503）→ 當作還沒醒，繼續探
        } catch {
          // abort 或連線失敗 → 還沒醒，繼續探
        } finally {
          clearTimeout(abortTimer)
        }
        if (Date.now() - startedAt >= GIVE_UP_MS) {
          settle('failed')
          return false
        }
        await sleep(RETRY_DELAY_MS)
      }
      // 這一輪已被作廢（unmount）：清掉 timer，不要動 state
      clearTimeout(graceTimer)
      clearInterval(elapsedTimer)
      return false
    })()

    inFlightRef.current = cycle
    // 只有「自己還是當前這輪」才把 ref 清掉。無條件清會有一個 StrictMode 專屬的洞：
    // mount → unmount → mount 之後，已作廢的第一輪還在 await（最久到 10 秒的探測逾時），
    // 它比第二輪晚結束，收尾時會把還在跑的第二輪從 ref 上抹掉 → 之後 ensureAwake()
    // 看到 ref 是空的，就會再開一輪，變成兩輪並行重複打 health。
    void cycle.finally(() => {
      if (inFlightRef.current === cycle) inFlightRef.current = null
    })

    return cycle
  }, [])

  /**
   * 送出耗時請求前呼叫。回傳 true = 後端已回應 health，可以安全送出；
   * false = 等到放棄時限仍沒醒，呼叫端應直接顯示錯誤，不要浪費一次請求。
   * 已在進行中的輪詢會被共用，不會疊加。
   */
  const ensureAwake = useCallback((): Promise<boolean> => runWakeupCycle(), [runWakeupCycle])

  useEffect(() => {
    void runWakeupCycle()
    return () => {
      generationRef.current += 1 // 作廢執行中的那輪
      inFlightRef.current = null // 下次 mount / ensureAwake 要能開新的一輪
    }
  }, [runWakeupCycle])

  return { status, elapsedSeconds, ensureAwake }
}
