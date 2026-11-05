import { Component, type ErrorInfo, type ReactNode } from 'react'
import { apiFetch } from '../api'

/**
 * 前端最後一道防線：接住 render 期間丟出的例外，讓畫面退成一張錯誤卡片而不是整片白，
 * 並把崩潰回報到後端 log。
 *
 * <p>為什麼需要它（2026-08-11 正式站實例）：使用者按下「出發」後畫面突然全白、時有時無。
 * 後端 log 顯示那個時間點的 `[itinerary-gen]` 全是 ok（3.6s / 5.9s 回應，無 fail、無 502），
 * 也就是**資料早就回來了，是前端把它畫出來的那一刻炸的**。在此之前專案沒有任何
 * ErrorBoundary，React 對未捕捉的 render 例外只有一種處置：卸載整棵元件樹 →
 * `<div id="root">` 變空 → 全白。白屏正是 PLAN 進度格子「錯誤不白屏」那格要防的東西，
 * S3 只擋住了「後端回錯誤」那條路徑，前端自己炸掉這條一直是破口。
 *
 * <p>**短代碼是 correlation id，不是錯誤分類**：畫面顯示 `UI-M4X7-K92A`，後端 log 記同一串，
 * 使用者只要把它念出來就能對到那一筆。刻意不編碼錯誤類別——類別事先根本不知道（真凶到現在
 * 還沒定位），硬分類的結果只會是九成落進 OTHER。分類靠 log 端的 scope / path / message
 * 事後聚合，那才是真正的分群依據。
 *
 * <p>**回報失敗時的降級**：代碼有個前提是回報真的送達，否則 log 裡沒那筆、那串就是廢字串
 * （斷網、被限流、後端在睡都可能）。所以卡片看狀態說話：送達了就顯示代碼，沒送達就退回顯示
 * 一行錯誤訊息——此時使用者手上那張截圖是唯一的證據。
 *
 * <p>「重新開始」為什麼要清 sessionStorage：生成成功後 App 會把行程寫進 `lastTripPlan`，
 * 而首屏初始化又優先讀它。若炸掉的成因就是那份資料的形狀，重新整理只會用同一份壞資料
 * 再炸一次——使用者會卡在永久白屏出不來。所以復原動作必須連暫存一起清掉。
 *
 * <p>**涵蓋範圍的限制**：React 的 ErrorBoundary 只接得住 render / lifecycle / constructor
 * 期間的例外。event handler 與 async（fetch 的 .then/catch）裡丟出的錯誤它接不到——
 * 但那些也不會造成白屏（元件樹不會被卸載），生成流程的錯誤本來就有 S3 的錯誤卡片在接。
 */
type Props = {
  children: ReactNode
  /** 出現在 log 與代碼旁，用來分辨是哪一層接到的（page = 最外層路由，results = 行程結果區） */
  scope?: string
}

type State = {
  error: Error | null
  code: string
  reported: 'pending' | 'ok' | 'failed'
}

/** `UI-` + base36 時間 + 4 碼隨機。短到能用講的、唸的、拍照傳的，又不會撞號 */
function makeCode(): string {
  const stamp = Date.now().toString(36).toUpperCase().slice(-5)
  const salt = Math.random().toString(36).toUpperCase().slice(2, 6)
  return `UI-${stamp}-${salt}`
}

/**
 * 從 React 的元件堆疊取最靠近錯誤的那個元件名。
 * production build 有 esbuild 的 keepNames（見 vite.config.ts）才讀得到真名，
 * 否則這裡會拿到壓縮後的 `t`、`a` 之類。
 */
function firstComponentName(stack?: string | null): string {
  if (!stack) return ''
  for (const line of stack.split('\n')) {
    const matched = line.trim().match(/^at\s+([A-Za-z0-9_$.]+)/)
    if (matched) return matched[1]
  }
  return ''
}

export default class ErrorBoundary extends Component<Props, State> {
  state: State = { error: null, code: '', reported: 'pending' }

  static getDerivedStateFromError(error: Error): State {
    return { error, code: makeCode(), reported: 'pending' }
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    const scope = this.props.scope ?? 'app'
    const { code } = this.state
    const component = firstComponentName(info.componentStack)

    // 前綴好 grep，跟後端 [itinerary-gen] 同精神。console 這行只有本機看得到，
    // 真正能讓開發者知道「有人炸了」的是下面那個 POST。
    console.error(`[ui-crash] code=${code} scope=${scope} message=${error.message}`, error, info.componentStack)

    // 只送白名單欄位：沒有一格裝得下使用者輸入的自然語言需求（那裡面可能有日期、
    // 出發地、家庭成員）。path 取 pathname 不取 search，避免夾帶 query 參數。
    apiFetch('/api/client-error', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        code,
        scope,
        path: window.location.pathname,
        message: error.message?.slice(0, 500) ?? '',
        component,
      }),
    })
      .then((res) => this.setState({ reported: res.ok ? 'ok' : 'failed' }))
      // 全部吞掉、不重試：回報自己失敗絕不能再丟出例外，否則就是崩潰回報造成崩潰的迴圈
      .catch(() => this.setState({ reported: 'failed' }))
  }

  /** 清掉可能有毒的暫存再回首頁，避免重新整理後用同一份壞資料再炸一次 */
  private handleRestart = () => {
    try {
      sessionStorage.removeItem('lastTripPlan')
      sessionStorage.removeItem('editTripPlan')
    } catch {
      /* 無痕模式等情境下 sessionStorage 可能不可用，清不掉也要能繼續走 */
    }
    window.location.href = '/'
  }

  render() {
    const { error, code, reported } = this.state
    if (!error) return this.props.children

    return (
      <section
        role="alert"
        // mx-auto/max-w 是給最外層那顆用的（整頁掛掉時沒有父容器撐版面）；
        // 結果區那顆的寬度本來就被父容器限住，這幾個 class 不會生效
        className="mx-auto my-5 max-w-2xl rounded-[22px] border-2 border-ink bg-paper px-5 py-6 shadow-[3px_3px_0_var(--pop-shadow-color)]"
      >
        <div className="text-[19px] font-extrabold tracking-tight text-ink">
          😵 這個畫面出了點問題
        </div>
        <p className="mt-2 text-[13px] leading-relaxed text-muted">
          不是你的操作有錯，是我們的畫面沒撐住。你的行程沒有送出去任何東西，
          按下面的按鈕就能重新開始。
        </p>

        <div className="mt-4 flex flex-wrap gap-2">
          <button
            type="button"
            onClick={this.handleRestart}
            className="rounded-full border-2 border-ink bg-ink px-4 py-2.5 text-[13px] font-extrabold text-paper transition active:translate-x-px active:translate-y-px"
          >
            🔄 重新開始
          </button>
        </div>

        {reported === 'failed' ? (
          // 回報沒送達 → log 裡沒有這筆，代碼是廢的。此時使用者手上這行字是唯一的線索，
          // 所以直接顯示，不收在 details 裡（收起來多數人不會展開）。
          <div className="mt-4 rounded-xl border-[1.5px] border-ink/30 bg-cream p-3">
            <div className="text-[11.5px] font-semibold text-muted">
              這次沒能把錯誤傳回給我們，回報問題時請把下面這行拍給我：
            </div>
            <div className="mt-1 break-words font-mono text-[11.5px] text-ink">
              {error.message || '(沒有錯誤訊息)'}
            </div>
          </div>
        ) : (
          <div className="mt-4 text-[12px] text-muted">
            錯誤代碼{' '}
            <code className="rounded-md border-[1.5px] border-ink/30 bg-cream px-1.5 py-0.5 font-mono text-[12px] font-bold text-ink">
              {code}
            </code>
            {reported === 'pending' ? (
              <span className="ml-1 opacity-70">傳送中…</span>
            ) : (
              <span className="ml-1">— 回報問題時把這串給我就好 ✨</span>
            )}
          </div>
        )}
      </section>
    )
  }
}
