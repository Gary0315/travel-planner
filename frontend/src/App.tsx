import { useState, useEffect, Fragment } from 'react'
import type { FormEvent } from 'react'
import {
  GripVertical,
  X,
  Copy,
  Check,
  Sun,
  Moon,
  ChevronDown,
} from 'lucide-react'
import {
  DndContext,
  closestCenter,
  PointerSensor,
  KeyboardSensor,
  useSensor,
  useSensors,
  DragOverlay,
} from '@dnd-kit/core'
import type { DragEndEvent, DragStartEvent } from '@dnd-kit/core'
import {
  SortableContext,
  useSortable,
  verticalListSortingStrategy,
  arrayMove,
  sortableKeyboardCoordinates,
} from '@dnd-kit/sortable'
import { CSS } from '@dnd-kit/utilities'
import { useTheme } from './useTheme'
import { useIsDesktop } from './useIsDesktop'
import { useServerWakeup } from './useServerWakeup'
import TravelChecklist from './TravelChecklist'
import { TabNav } from './Tabs'
import type { TabKey } from './Tabs'
import { LocalChecklistRepo } from './checklistRepo'
import PhotoCredit from './components/PhotoCredit'
import HotelMini from './components/HotelMini'
import DetailPanel from './components/DetailPanel'
import HotelUnavailableNotice from './components/HotelUnavailableNotice'
import ErrorBoundary from './components/ErrorBoundary'
import type { Activity, DayPlan, TripPlan, ApiTripPlan, ExpandedImage } from './types'
import { getCategory } from './categoryStyle'
import { saveDeleteToken } from './itineraryTokens'
import { apiFetch } from './api'
import AuthButton from './auth/AuthButton'
import { useAuth } from './auth/useAuth'
import LoginModal from './auth/LoginModal'

// ─── Types ────────────────────────────────────────────────────────────────────
// 共用型別在 ./types。App.tsx 內部用「帶 id」的變體（拖曳排序需要穩定的 React key）。

type ActivityWithId = Activity & { id: string }
type AppDayPlan = Omit<DayPlan, 'activities'> & { activities: ActivityWithId[] }
type AppTripPlan = Omit<TripPlan, 'days'> & { days: AppDayPlan[] }

type TransportMode = 'walking' | 'driving' | 'transit'

// ─── Config ───────────────────────────────────────────────────────────────────

const SAMPLE_PROMPTS = [
  '台中兩天一夜美食散步之旅',
  '台南三天兩夜文青咖啡行',
  '宜蘭兩天一夜親子放鬆',
  '花蓮三天兩夜山海療癒',
]

const CHIP_BGS = [
  'bg-butter',
  'bg-sky-soft',
  'bg-mint',
  'bg-pink-soft',
]

/**
 * 生成請求的前端逾時。正式站走 Cloudflare，免費方案的 proxy 上限是 100 秒且不可調，
 * 逾時它會回 524 + 一頁 HTML（不是後端的 JSON），前端只能顯示一句無意義的失敗。
 * 這裡趕在它之前自己收掉，才能給出「伺服器可能剛睡醒」這種有用的話。
 * 正常路徑不該碰到它：ensureAwake 已經把冷啟動的時間吃掉，生成本身約 20–60 秒。
 */
const GENERATE_TIMEOUT_MS = 95_000

/**
 * 回應碼落在這裡、且 body 不是後端 JSON 時，視為「請求沒走到 app」的閘道錯誤
 * （CF 524 逾時 / Render 冷啟動中的 502、503）。注意 502 也是後端 AI 失敗的碼，
 * 所以一定要先確認解析不出 JSON 再套這組，否則會蓋掉 S3 的錯誤訊息。
 */
const GATEWAY_STATUSES = new Set([502, 503, 504, 521, 522, 523, 524])

// ─── Helpers ──────────────────────────────────────────────────────────────────

function addIds(plan: ApiTripPlan): AppTripPlan {
  return {
    title: plan.title,
    summary: plan.summary,
    hotelsUnavailable: plan.hotelsUnavailable ?? null,
    days: plan.days.map((d) => ({
      day: d.day,
      theme: d.theme,
      weather: d.weather,
      activities: d.activities.map((a) => ({ ...a, id: crypto.randomUUID() })),
      hotels: d.hotels ?? null,
    })),
  }
}

function parseMinutes(hhmm: string): number {
  const [h, m] = hhmm.trim().split(':').map(Number)
  return h * 60 + m
}

function formatMinutes(total: number): string {
  const h = Math.floor(total / 60) % 24
  const m = total % 60
  return `${String(h).padStart(2, '0')}:${String(m).padStart(2, '0')}`
}

function parseDuration(timeRange: string): number {
  const parts = timeRange.split('-')
  if (parts.length < 2) return 60
  const duration = parseMinutes(parts[1]) - parseMinutes(parts[0])
  return duration > 0 ? duration : 60
}

// transport 與 category 同樣是後端 contract 的 optional 欄位（只有 @Size 沒有 @NotBlank），
// AI 沒給就是 undefined。這裡若不擋，undefined.match() 會在「拖曳重排」時 throw ——
// 跟 getCategory 是同一個成因，只是要拖過才會現形，所以 2026-08-13 那次崩潰沒抓到它。
function parseTransportMinutes(transport?: string | null): number {
  const match = transport?.match(/(\d+)\s*分/)
  if (!match) {
    console.warn('[reassignTimes] 無法從 transport 欄位解析分鐘數，fallback 20 分鐘。transport =', transport)
    return 20
  }
  return parseInt(match[1], 10)
}

function reassignTimes(activities: ActivityWithId[], dayStart?: string): ActivityWithId[] {
  if (activities.length === 0) return activities
  // dayStart 由呼叫端傳入「重排前」的當天起始時間。
  // 若用 activities[0].time（重排後的第一個），把 12:10 的景點拖到最前面時
  // 整天會從 12:10 起算——這是錯的。固定用原始當天開始時刻才正確。
  const startStr = dayStart ?? activities[0].time.split('-')[0]
  const parsedStart = parseMinutes(startStr.trim())
  const START = Number.isFinite(parsedStart) ? parsedStart : 9 * 60
  let cursor = START
  return activities.map((activity, index) => {
    const duration = parseDuration(activity.time)
    const newTime = `${formatMinutes(cursor)} - ${formatMinutes(cursor + duration)}`
    const isLast = index === activities.length - 1
    const buffer = isLast ? 0 : parseTransportMinutes(activity.transport)
    cursor += duration + buffer
    return { ...activity, time: newTime }
  })
}

const mockPlan: ApiTripPlan = {
  title: '台中兩天一夜美食散步之旅',
  summary: '走進台中西區的文青角落，沿著草悟道與審計新村慢慢吃慢慢逛。',
  days: [
    {
      day: 1,
      theme: '西區散步日',
      weather: '☀️ 26°C',
      activities: [
        { time: '09:00 - 10:30', location: '草悟道', category: '景點', description: '沿線散步與咖啡店巡禮，體驗台中西區文青氛圍。', transport: '步行 8 分鐘', emoji: '🌳', imageUrl: '/mock-images/park.svg' },
        { time: '10:38 - 12:00', location: '審計新村', category: '景點', description: '探索特色小店與文創品牌，適合拍照打卡。', transport: '步行 10 分鐘', emoji: '🏘️', imageUrl: '/mock-images/home.svg' },
        { time: '12:10 - 13:40', location: '向上市場', category: '美食', description: '在地小吃午餐，推薦麵線、滷味與甜點。', transport: '步行 12 分鐘', emoji: '🍜', imageUrl: '/mock-images/food.svg' },
        { time: '13:52 - 15:00', location: '宮原眼科', category: '美食', description: '網美必訪冰淇淋老店，霜淇淋疊得跟塔一樣高。', transport: '步行 5 分鐘', emoji: '🍦', imageUrl: '/mock-images/ice.svg' },
        { time: '15:08 - 16:30', location: '雲隅文旅 Check-in', category: '住宿', description: '台中西區精品旅店，店內有溜滑梯，放下行李拍張照再出門。', transport: '步行 5 分鐘', emoji: '🛏️', hideMap: true, imageUrl: '/mock-images/hotel.svg' },
      ],
      hotels: [
        { name: '雲隅文旅', rating: 4.4, userRatingsTotal: 3210, address: '台中市中區 · 步行 5 分鐘', placeId: null, imageUrl: '/mock-images/orangeBed.svg' },
        { name: '漫旅 Stay 台中館', rating: 4.6, userRatingsTotal: 5602, address: '台中市西區 · 步行 5 分鐘', placeId: null, imageUrl: '/mock-images/pinkBed.svg' },
      ],
    },
    {
      day: 2,
      theme: '美術館 + 綠園道',
      weather: '⛅ 24°C',
      activities: [
        { time: '09:30 - 11:00', location: '國立臺灣美術館', category: '景點', description: '參觀常設展與當期特展，感受台中文化能量。', transport: '公車 15 分鐘', emoji: '🖼️', imageUrl: '/mock-images/picture.svg' },
        { time: '11:15 - 12:45', location: '勤美誠品綠園道', category: '美食', description: '書店與選品店逛街，安排輕食午餐。', transport: '步行 9 分鐘', emoji: '📚', imageUrl: '/mock-images/book.svg' },
        { time: '12:54 - 14:10', location: '一中街商圈', category: '美食', description: '甜點掃街，鮮芋仙、地瓜球、奶茶一次滿足。', transport: '計程車 12分鐘', emoji: '🧋', imageUrl: '/mock-images/bubbleTea.svg' },
      ],
    },
  ],
}

// ─── ShareModal ──────────────────────────────────────────────────────────────

function ShareModal({ url, onClose }: { url: string; onClose: () => void }) {
  const [copied, setCopied] = useState(false)

  const handleCopy = () => {
    navigator.clipboard.writeText(url).then(() => {
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    })
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-ink/40 p-4"
      onClick={onClose}
    >
      <div
        className="w-full max-w-md rounded-3xl border-2 border-ink bg-paper p-6 shadow-[4px_4px_0_var(--pop-shadow-color)]"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-center justify-between">
          <h3 className="flex items-center gap-2 text-base font-extrabold text-ink">
            <span className="text-lg">🎉</span> 行程已收藏！
          </h3>
          <button
            onClick={onClose}
            className="rounded-full border-2 border-ink bg-cream p-1.5 text-ink transition hover:bg-coral hover:text-white"
            aria-label="關閉"
          >
            <X className="h-4 w-4" />
          </button>
        </div>
        <p className="mb-3 text-sm text-muted">複製連結就可以分享給朋友啦 💌</p>
        <div className="flex items-center gap-2 rounded-2xl border-2 border-ink bg-cream px-3 py-2">
          <span className="flex-1 truncate text-sm text-ink">{url}</span>
          <button
            onClick={handleCopy}
            className="inline-flex shrink-0 items-center gap-1 rounded-full border-2 border-ink bg-coral px-3 py-1.5 text-xs font-extrabold text-white shadow-[2px_2px_0_var(--pop-shadow-color)] transition active:translate-x-px active:translate-y-px active:shadow-none"
          >
            {copied ? <Check className="h-3.5 w-3.5" /> : <Copy className="h-3.5 w-3.5" />}
            {copied ? '已複製' : '複製'}
          </button>
        </div>
        <a
          href={url}
          target="_blank"
          rel="noopener noreferrer"
          className="mt-3 flex w-full items-center justify-center gap-1.5 rounded-full border-2 border-ink bg-mint-soft px-4 py-2.5 text-[13px] font-extrabold text-ink shadow-[2px_2px_0_var(--pop-shadow-color)] transition hover:-translate-y-px hover:shadow-[3px_3px_0_var(--pop-shadow-color)]"
        >
          前往查看分享頁面 →
        </a>
      </div>
    </div>
  )
}

// ─── Timeline item (sortable) ────────────────────────────────────────────────

function TimelineItem({
  activity,
  isActive,
  isLast,
  onSelect,
  expandable = false,
  expanded = false,
}: {
  activity: ActivityWithId
  isActive: boolean
  isLast: boolean
  onSelect: () => void
  /** 手機版才有：卡片點下去會就地展開/收起細節，需要一個看得出來的指示 */
  expandable?: boolean
  expanded?: boolean
}) {
  const cat = getCategory(activity.category)
  const emoji = activity.emoji || cat.emoji

  const { attributes, listeners, setNodeRef, transform, transition, isDragging } = useSortable({
    id: activity.id,
  })

  const style = {
    transform: CSS.Transform.toString(transform),
    transition,
  }

  return (
    <div
      ref={setNodeRef}
      style={style}
      className={`flex gap-3 ${isDragging ? 'opacity-30' : ''}`}
    >
      {/* left rail: emoji + line */}
      <div className="flex shrink-0 flex-col items-center pt-1.5">
        <div
          className={`text-[22px] leading-none transition-transform duration-200 ${isActive ? 'scale-110' : ''}`}
        >
          {emoji}
        </div>
        {!isLast && (
          <div className="mt-1 -mb-1 w-0.5 flex-1 rounded-full bg-ink/20" />
        )}
      </div>
      {/* card */}
      <button
        type="button"
        onClick={onSelect}
        aria-expanded={expandable ? expanded : undefined}
        className={`mb-3 flex-1 cursor-pointer rounded-2xl border-2 border-ink bg-paper px-3.5 py-3 text-left transition-all duration-200 ${
          isActive
            ? '-translate-x-px -translate-y-px shadow-[3px_3px_0_var(--color-coral)]'
            : 'shadow-[2px_2px_0_var(--pop-shadow-color)]'
        }`}
      >
        <div className="mb-1 flex items-center justify-between gap-2">
          <span className="text-[12px] font-bold tabular-nums text-muted">{activity.time}</span>
          <div className="flex items-center gap-1">
            <span className={`rounded-full px-2.5 py-0.5 text-[10.5px] font-extrabold ${cat.badgeBg} ${cat.badgeFg}`}>
              {cat.label}
            </span>
            <span
              {...attributes}
              {...listeners}
              role="button"
              aria-label="拖曳排序"
              tabIndex={0}
              onClick={(e) => e.stopPropagation()}
              className="ml-0.5 inline-flex h-6 w-6 cursor-grab touch-none items-center justify-center rounded-full border-[1.5px] border-ink bg-cream text-ink/60 transition hover:text-ink active:cursor-grabbing"
            >
              <GripVertical className="h-3.5 w-3.5" />
            </span>
          </div>
        </div>
        <div className="flex items-center justify-between gap-2">
          <div className="text-[15px] font-extrabold tracking-tight text-ink">{activity.location}</div>
          {expandable && (
            <ChevronDown
              aria-hidden="true"
              className={`h-4 w-4 shrink-0 text-ink/40 transition-transform duration-200 ${expanded ? 'rotate-180' : ''}`}
            />
          )}
        </div>
        <div className="mt-0.5 text-[12.5px] leading-relaxed text-muted">{activity.description}</div>
        {activity.transport && (
          <div className="mt-1.5 text-[11px] font-bold text-coral-deep">→ {activity.transport}</div>
        )}
      </button>
    </div>
  )
}

function DragOverlayCard({ activity }: { activity: ActivityWithId }) {
  const cat = getCategory(activity.category)
  const emoji = activity.emoji || cat.emoji
  return (
    <div className="flex gap-3">
      <div className="flex shrink-0 flex-col items-center pt-1.5">
        <div className="text-[22px] leading-none">{emoji}</div>
      </div>
      <div className="flex-1 rotate-1 rounded-2xl border-2 border-ink bg-paper px-3.5 py-3 shadow-[4px_4px_0_var(--color-coral)]">
        <div className="mb-1 flex items-center justify-between gap-2">
          <span className="text-[12px] font-bold tabular-nums text-muted">{activity.time}</span>
          <span className={`rounded-full px-2.5 py-0.5 text-[10.5px] font-extrabold ${cat.badgeBg} ${cat.badgeFg}`}>
            {cat.label}
          </span>
        </div>
        <div className="text-[15px] font-extrabold tracking-tight text-ink">{activity.location}</div>
      </div>
    </div>
  )
}

// ─── Loading skeleton ────────────────────────────────────────────────────────

function LoadingSkeleton() {
  return (
    <section className="rounded-[26px] border-2 border-ink bg-paper p-8 text-center shadow-[4px_4px_0_var(--pop-shadow-color)]">
      <div className="roamie-bounce inline-block text-[32px]">🗺️</div>
      <div className="mt-1 text-sm font-extrabold text-ink">正在為你規劃路線⋯</div>
      <div className="mt-1 text-xs text-muted">挑選順路的景點與美食</div>
      <div className="mt-4 flex justify-center gap-1.5">
        {['🚶', '📍', '🍜', '🛏️'].map((e, i) => (
          <span
            key={i}
            className="roamie-pulse text-[22px]"
            style={{ animationDelay: `${i * 0.2}s`, opacity: 0.4 + i * 0.15 }}
          >
            {e}
          </span>
        ))}
      </div>
    </section>
  )
}

// ─── App ─────────────────────────────────────────────────────────────────────

const transportOptions: { value: TransportMode; label: string; emoji: string }[] = [
  { value: 'driving', label: '開車', emoji: '🚗' },
  { value: 'walking', label: '步行', emoji: '🚶' },
  { value: 'transit', label: '大眾', emoji: '🚇' },
]

function App() {
  const [userInput, setUserInput] = useState('')
  const [transportMode, setTransportMode] = useState<TransportMode>('driving')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [retryAfter, setRetryAfter] = useState(0)
  // 單一 useState 同時決定初始 plan / isMock：
  // 優先序 — ViewPage「編輯這個行程」傳來的 editTripPlan > 本機 lastTripPlan > mock。
  // 用單一 init function 避免兩個 useState 各自讀 sessionStorage 造成 race（editTripPlan 已被前者消費）。
  const [{ initialPlan, initialIsMock }] = useState(() => {
    try {
      const incoming = sessionStorage.getItem('editTripPlan')
      if (incoming) {
        sessionStorage.removeItem('editTripPlan')
        const parsed = JSON.parse(incoming) as ApiTripPlan
        return { initialPlan: addIds(parsed), initialIsMock: false }
      }
      const saved = sessionStorage.getItem('lastTripPlan')
      if (saved) {
        return { initialPlan: JSON.parse(saved) as AppTripPlan, initialIsMock: false }
      }
    } catch { /* ignore */ }
    return { initialPlan: addIds(mockPlan), initialIsMock: true }
  })
  const [tripPlan, setTripPlan] = useState<AppTripPlan>(initialPlan)
  const [activeDay, setActiveDay] = useState(1)
  const [activeIdx, setActiveIdx] = useState(0)
  // 手機版限定：細節面板就地插在 timeline 中間，選中的景點再點一次要能收起來，
  // 否則那張 4:3 大圖會一直卡在當天行程中央（親友回饋 2026-08-06）。
  // 桌機版細節在右側獨立欄位，不受這個狀態影響。
  const [detailCollapsed, setDetailCollapsed] = useState(false)
  const [isMock, setIsMock] = useState(initialIsMock)
  // 每次生成成功 +1，當作結果區 ErrorBoundary 的 key：上一份行程若把畫面畫炸了，
  // 生成新的一份時 key 改變 → boundary 整個重建 → 錯誤狀態自然清空，
  // 使用者不必重新整理就能離開錯誤卡片（省掉一個手動 reset 的 API）。
  const [genSeq, setGenSeq] = useState(0)
  const [saving, setSaving] = useState(false)
  // 把「這趟行程的分享 URL（=已儲存標記）」與「分享 modal 是否顯示」拆開：
  //   - shareUrl null → 還沒存過 → 按鈕顯示「💌 儲存並分享」
  //   - shareUrl 有值 → 已存過 → 按鈕顯示「✓ 已收藏」；點按鈕只是重開 modal，不再發儲存請求
  //   - showShareModal 只控制 modal 是否在畫面上，關掉不會清掉 shareUrl
  const [shareUrl, setShareUrl] = useState<string | null>(null)
  const [showShareModal, setShowShareModal] = useState(false)
  const [activeTab, setActiveTab] = useState<TabKey>('timeline')
  const [checklistRepo] = useState(() => new LocalChecklistRepo())
  const [expandedImage, setExpandedImage] = useState<ExpandedImage | null>(null)
  const [draggedActivity, setDraggedActivity] = useState<ActivityWithId | null>(null)
  const { theme, toggleTheme } = useTheme()
  const isDesktop = useIsDesktop()
  // S2 冷啟動閘門：health 未回應前鎖住生成按鈕；failed（90s 放棄）也解鎖，
  // 讓使用者可以手動再試——真的打失敗會走既有的錯誤訊息路徑。
  const { status: wakeupStatus, elapsedSeconds: wakeupSeconds, ensureAwake } = useServerWakeup()
  const serverReady = wakeupStatus === 'awake' || wakeupStatus === 'failed'
  const { state: authState, disabled: authDisabled } = useAuth()
  const isLoggedIn = authState.status === 'signedIn'
  const [loginModalOpen, setLoginModalOpen] = useState(false)

  useEffect(() => {
    if (!expandedImage) return
    const handleKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setExpandedImage(null) }
    window.addEventListener('keydown', handleKey)
    return () => window.removeEventListener('keydown', handleKey)
  }, [expandedImage])

  const sensors = useSensors(
    useSensor(PointerSensor, { activationConstraint: { distance: 8 } }),
    useSensor(KeyboardSensor, { coordinateGetter: sortableKeyboardCoordinates }),
  )

  const currentDay = tripPlan.days.find((d) => d.day === activeDay) ?? tripPlan.days[0]
  const safeIdx = currentDay ? Math.min(activeIdx, currentDay.activities.length - 1) : 0
  const activeActivity = currentDay?.activities[safeIdx]

  // 旅館查詢整段失敗時，只在「本來就該有住宿」的天顯示提示。
  // 最後一天與單日行程後端本來就不查旅館（GeminiItineraryService.enrichWithHotels），
  // 在那些天印提示等於製造假警報 —— 使用者會以為壞了，其實是設計如此。
  const lastDayNumber = tripPlan.days[tripPlan.days.length - 1]?.day
  const showHotelNotice =
    !!tripPlan.hotelsUnavailable &&
    !currentDay?.hotels?.length &&
    tripPlan.days.length >= 2 &&
    currentDay?.day !== lastDayNumber

  useEffect(() => {
    if (retryAfter <= 0) return
    const id = setTimeout(() => setRetryAfter((s) => Math.max(0, s - 1)), 1000)
    return () => clearTimeout(id)
  }, [retryAfter])

  const handleGenerate = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setError('')

    if (!userInput.trim()) {
      setError('請先輸入自然語言需求（例如：台中兩天一夜放鬆之旅）。')
      return
    }

    setLoading(true)
    setActiveIdx(0)
    setDetailCollapsed(false)

    try {
      // 頁面可能已經開很久，後端在這期間休眠了（載入時那輪 wakeup 的結論早就過期）。
      // 先把它叫醒再送 POST，否則這一個請求要同時扛冷啟動與 AI 生成 → Cloudflare 524。
      const awake = await ensureAwake()
      if (!awake) {
        throw new Error('伺服器一直沒醒來，請稍等幾分鐘後再試一次。')
      }

      const abortController = new AbortController()
      const timeoutId = setTimeout(() => abortController.abort(), GENERATE_TIMEOUT_MS)
      let response: Response
      try {
        response = await apiFetch('/api/itinerary/generate', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ message: userInput, transportMode }),
          signal: abortController.signal,
        })
      } finally {
        clearTimeout(timeoutId)
      }

      if (!response.ok) {
        // 解析不出 JSON = 這頁不是後端給的（CF 的 524 錯誤頁、Render 冷啟動中的 502/503）
        const errData = await response.json().catch(() => null) as { errorCode?: string; retryAfterSeconds?: number; message?: string } | null
        if (!errData) {
          if (GATEWAY_STATUSES.has(response.status)) {
            throw new Error('伺服器剛醒來還沒準備好，請稍等 30 秒後再試一次。')
          }
          throw new Error('行程生成失敗，請再試一次。')
        }
        if (response.status === 429) {
          // 全站每日額度用盡：要等的是「到明天」，秒數倒數沒有意義，
          // 所以不呼叫 setRetryAfter（不鎖按鈕），只換一句看得懂的話。
          if (errData.errorCode === 'DAILY_QUOTA_EXCEEDED') {
            throw new Error(errData.message ?? '今天的行程生成次數已達上限，請明天再試。')
          }
          const wait = errData.retryAfterSeconds ?? 60
          setRetryAfter(wait)
          throw new Error(`請求太頻繁，請等待 ${wait} 秒後再試。`)
        }
        if (errData.errorCode === 'AI_PARSE_ERROR') {
          throw new Error('AI 回應格式異常，換個方式描述需求後再試一次。')
        }
        throw new Error(errData.message ?? '行程生成失敗，請再試一次。')
      }

      const data = (await response.json()) as ApiTripPlan
      const plan = addIds(data)
      sessionStorage.setItem('lastTripPlan', JSON.stringify(plan))
      setTripPlan(plan)
      setIsMock(false)
      setGenSeq((n) => n + 1)
      setActiveDay(data.days[0]?.day ?? 1)
      setActiveIdx(0)
      // 新行程 ≠ 舊行程，先前的「已收藏」狀態作廢
      setShareUrl(null)
      setShowShareModal(false)
    } catch (err) {
      // 自己的 95 秒逾時先觸發（趕在 CF 的 100 秒之前），給得出有用的話
      if (err instanceof DOMException && err.name === 'AbortError') {
        setError('這次等太久了，伺服器可能剛睡醒還很慢。請稍等 30 秒後再試一次。')
      } else {
        setError(err instanceof Error ? err.message : '發生未知錯誤，請稍後再試。')
      }
    } finally {
      setLoading(false)
    }
  }

  const handleSave = async () => {
    setSaving(true)
    try {
      const payload = {
        title: tripPlan.title,
        summary: tripPlan.summary,
        days: tripPlan.days.map((d) => ({
          day: d.day,
          theme: d.theme,
          weather: d.weather,
          activities: d.activities.map(({ id: _id, ...rest }) => rest),
          hotels: d.hotels ?? null,
        })),
      }
      const response = await apiFetch('/api/itinerary/save', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(payload),
      })
      if (!response.ok) throw new Error('儲存失敗')
      const { shareId, deleteToken } = (await response.json()) as {
        shareId: string
        deleteToken?: string
      }
      setShareUrl(`${window.location.origin}/view/${shareId}`)
      setShowShareModal(true)
      // 把刪除 token 存進 localStorage，讓使用者之後從 /view/{shareId} 開回來時能看到「刪除分享」按鈕。
      // 行程本身的擁有者連結現在由後端寫進 itinerary.user_id（透過當前 session），不需前端再記。
      if (deleteToken) saveDeleteToken(shareId, deleteToken)
    } catch {
      setError('儲存行程失敗，請稍後再試。')
    } finally {
      setSaving(false)
    }
  }

  const handleReorder = (newActivities: ActivityWithId[]) => {
    setTripPlan((prev) => ({
      ...prev,
      days: prev.days.map((d) => (d.day === activeDay ? { ...d, activities: newActivities } : d)),
    }))
  }

  const handleDragStart = (event: DragStartEvent) => {
    if (!currentDay) return
    const found = currentDay.activities.find((a) => a.id === event.active.id)
    setDraggedActivity(found ?? null)
  }

  const handleDragEnd = (event: DragEndEvent) => {
    const { active, over } = event
    setDraggedActivity(null)
    if (!currentDay || !over || active.id === over.id) return
    const oldIdx = currentDay.activities.findIndex((a) => a.id === active.id)
    const newIdx = currentDay.activities.findIndex((a) => a.id === over.id)
    if (oldIdx < 0 || newIdx < 0) return
    // 在 arrayMove 之前先記錄當天起始時間，確保重排後仍以同一時刻為起點
    const dayStart = currentDay.activities[0].time.split('-')[0].trim()
    handleReorder(reassignTimes(arrayMove(currentDay.activities, oldIdx, newIdx), dayStart))
  }

  const handleDayChange = (day: number) => {
    setActiveDay(day)
    setActiveIdx(0)
    setDetailCollapsed(false)
  }

  const saved = !!shareUrl

  return (
    <div className="relative min-h-screen overflow-x-hidden bg-cream font-display text-ink">
      {/* Decorative blobs */}
      <div
        aria-hidden
        className="pointer-events-none fixed -right-10 -top-16 h-[200px] w-[200px] rounded-full bg-butter opacity-50 blur-[2px]"
      />
      <div
        aria-hidden
        className="pointer-events-none fixed left-[-80px] top-[200px] h-[240px] w-[240px] rounded-full bg-sky-soft opacity-35 blur-[2px]"
      />

      {/* Lightbox */}
      {expandedImage && (
        <div
          className="fixed inset-0 z-50 flex items-center justify-center bg-ink/80 p-4 backdrop-blur-sm"
          onClick={() => setExpandedImage(null)}
        >
          <div className="relative" onClick={(e) => e.stopPropagation()}>
            <img
              src={expandedImage.url}
              alt="放大圖片"
              className="max-h-[90vh] max-w-[92vw] rounded-2xl border-2 border-ink shadow-[6px_6px_0_var(--pop-shadow-color)]"
            />
            <PhotoCredit
              name={expandedImage.attribution}
              href={expandedImage.attributionUrl}
              variant="default"
            />
          </div>
          <button
            type="button"
            className="absolute right-5 top-5 rounded-full border-2 border-ink bg-paper p-2 text-ink transition hover:bg-coral hover:text-white"
            onClick={() => setExpandedImage(null)}
            aria-label="關閉"
          >
            <X className="h-5 w-5" />
          </button>
        </div>
      )}

      <main className="relative mx-auto flex w-full max-w-5xl flex-col gap-4 p-4 md:gap-5 md:px-9 md:py-7">
        {/* Header */}
        <header className="flex items-center justify-between">
          <div className="flex items-center gap-2.5">
            <div className="flex h-[38px] w-[38px] -rotate-6 items-center justify-center rounded-2xl border-2 border-ink bg-coral text-xl text-white shadow-[3px_3px_0_var(--pop-shadow-color)]">
              ✈
            </div>
            <div className="leading-tight">
              <div className="text-[19px] font-extrabold tracking-tight text-ink">Roamie</div>
              <div className="text-[10.5px] text-muted">your pocket-sized travel friend</div>
            </div>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="button"
              onClick={toggleTheme}
              aria-label={theme === 'dark' ? '切換為日間' : '切換為夜間'}
              className="inline-flex h-9 w-9 items-center justify-center rounded-full border-2 border-ink bg-paper text-ink transition hover:bg-butter active:translate-x-px active:translate-y-px"
            >
              {theme === 'dark' ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
            </button>
            <AuthButton />
          </div>
        </header>

        {/* Prompt card */}
        <section className="rounded-[26px] border-2 border-ink bg-paper p-4 shadow-[4px_4px_0_var(--pop-shadow-color)] md:p-6">
          <div className="mb-1 flex items-end gap-2">
            <h1 className="text-[22px] font-extrabold leading-tight tracking-tight text-ink md:text-[28px]">
              嘿！想去哪裡呢{' '}
              <span className="inline-block rotate-[15deg]">👋</span>
            </h1>
          </div>
          <p className="mb-3.5 text-[13px] text-muted">跟我說一句話就好 — 我會幫你打包成行程 ✨</p>

          {wakeupStatus === 'waking' && (
            <div role="status" className="mb-3.5 rounded-[18px] border-2 border-ink bg-butter px-4 py-3 text-[13px] font-bold text-ink shadow-[3px_3px_0_var(--pop-shadow-color)]">
              💤 伺服器喚醒中，請稍候約 30–60 秒（免費主機閒置會休眠）
              <span className="ml-1.5 tabular-nums font-semibold text-muted">已等待 {wakeupSeconds} 秒</span>
            </div>
          )}
          {wakeupStatus === 'failed' && (
            <div role="status" className="mb-3.5 rounded-[18px] border-2 border-ink bg-pink-soft px-4 py-3 text-[13px] font-bold text-ink shadow-[3px_3px_0_var(--pop-shadow-color)]">
              😴 伺服器一直沒醒來，請重新整理頁面再試；還是不行的話，稍等幾分鐘。
            </div>
          )}

          <form onSubmit={handleGenerate}>
            {/* 送出後整個輸入區鎖住：按鈕早就鎖了但輸入框沒有，改了字也不會影響
                已經送出的那次請求（值在按下當下就被 closure 捕獲），
                親友回饋（2026-08-06）指出這一點造成困惑 */}
            <div
              className={`rounded-[18px] border-[1.5px] border-dashed border-coral bg-cream px-4 py-3.5 transition-opacity ${
                loading ? 'opacity-55' : ''
              }`}
            >
              <textarea
                value={userInput}
                onChange={(e) => setUserInput(e.target.value)}
                rows={3}
                maxLength={2000}
                disabled={loading}
                placeholder="台中兩天一夜美食散步之旅"
                className="w-full resize-none border-none bg-transparent font-display text-[15px] font-medium text-ink outline-none placeholder:text-muted/60 disabled:cursor-not-allowed"
              />
              {userInput.length > 1800 && (
                <div className="mt-1 text-right text-[11px] text-muted">
                  {userInput.length} / 2000
                </div>
              )}
            </div>

            <div className="mt-3 flex flex-wrap gap-1.5">
              {SAMPLE_PROMPTS.map((s, i) => (
                <button
                  type="button"
                  key={s}
                  onClick={() => setUserInput(s)}
                  disabled={loading}
                  className={`rounded-full border-[1.5px] border-ink ${CHIP_BGS[i % CHIP_BGS.length]} px-2.5 py-1.5 text-[12px] font-bold text-ink transition active:translate-x-px active:translate-y-px disabled:cursor-not-allowed disabled:opacity-50`}
                >
                  ✨ {s}
                </button>
              ))}
            </div>

            <div className="mt-4 flex flex-col items-stretch justify-between gap-3 md:flex-row md:items-center">
              <div className="flex gap-1.5">
                {transportOptions.map(({ value, label, emoji }) => {
                  const isActive = transportMode === value
                  return (
                    <button
                      type="button"
                      key={value}
                      onClick={() => setTransportMode(value)}
                      disabled={loading}
                      className={`inline-flex items-center gap-1 rounded-full border-2 border-ink px-3.5 py-2 text-[13px] font-bold text-ink transition disabled:cursor-not-allowed disabled:opacity-50 ${
                        isActive
                          ? '-translate-x-px -translate-y-px bg-mint shadow-[2px_2px_0_var(--pop-shadow-color)]'
                          : 'bg-paper'
                      }`}
                    >
                      <span>{emoji}</span>
                      {label}
                    </button>
                  )
                })}
              </div>
              <button
                type="submit"
                disabled={loading || retryAfter > 0 || !serverReady}
                className="inline-flex items-center justify-center gap-1 rounded-full border-2 border-ink bg-coral px-5 py-3 text-[14px] font-extrabold text-white shadow-[3px_3px_0_var(--pop-shadow-color)] transition active:translate-x-px active:translate-y-px disabled:cursor-wait disabled:opacity-60"
                style={loading ? { transform: 'translate(1px,1px)', cursor: 'wait' } : undefined}
              >
                {wakeupStatus === 'waking' ? '💤 喚醒中…' : loading ? '🪄 規劃中…' : retryAfter > 0 ? `⏳ ${retryAfter} 秒後可再試` : '🎒 出發！'}
              </button>
            </div>
          </form>

          {error && (
            <p className="mt-3 text-[13px] font-semibold text-coral-deep">
              ⚠️ {error}
              {retryAfter > 0 && <span className="ml-1 tabular-nums">（{retryAfter} 秒）</span>}
            </p>
          )}
        </section>

        {/* 行程結果區包一層 ErrorBoundary：這裡畫的是 AI 回來的資料，形狀最不可控。
            刻意只包這塊、不包上面的輸入區——輸入區留著，使用者看到錯誤卡片時可以
            直接改需求再按一次出發，不必重新整理。key={genSeq} 讓新的一次生成
            自動把錯誤狀態清掉。（內部 JSX 維持原縮排，避免 170 行純縮排 diff
            蓋掉真正的改動） */}
        <ErrorBoundary key={genSeq} scope="results">
        {loading ? (
          <LoadingSkeleton />
        ) : (
          <>
            {/* Trip header chip — always visible */}
            <div className="flex flex-wrap items-center justify-between gap-2.5 rounded-[22px] border-2 border-ink bg-paper px-4 py-3 shadow-[3px_3px_0_var(--pop-shadow-color)]">
              <div className="min-w-0 flex-1">
                {/* 首屏在使用者生成之前載的是 mockPlan，但這行原本一律寫「YOUR TRIP」——
                    畫面上明明有一份完整行程、系統卻說「請先生成行程」，親友回饋
                    （2026-08-06）同時抓到這兩句的矛盾。示範保留，但要明講它是示範。 */}
                {isMock ? (
                  <span className="inline-flex items-center gap-1 rounded-full border-[1.5px] border-ink bg-butter px-2 py-0.5 text-[10.5px] font-extrabold text-ink">
                    👀 範例行程
                  </span>
                ) : (
                  <div className="text-[11px] font-bold tracking-[0.08em] text-coral-deep">YOUR TRIP ✦</div>
                )}
                {/* 行程標題是這張卡的主角，原本 17/21px 比上方招呼語（22/28px）還小，
                    視覺層級是反的（親友回饋 2026-08-06「字有點小」） */}
                <div className="mt-0.5 text-[19px] font-extrabold tracking-tight text-ink md:text-[24px]">
                  {tripPlan.title || '尚未生成'}
                </div>
                {tripPlan.summary && (
                  <div className="mt-1 text-[13px] leading-relaxed text-muted md:text-[13.5px]">
                    {tripPlan.summary}
                  </div>
                )}
              </div>
              <div className="flex flex-col items-end gap-1">
                <button
                  type="button"
                  onClick={saved ? () => setShowShareModal(true) : handleSave}
                  disabled={saving || isMock}
                  title={isMock ? '這是範例行程，生成你自己的之後就能分享' : saved ? '點擊重新顯示分享連結' : undefined}
                  className={`rounded-full border-2 border-ink px-4 py-2.5 text-[13px] font-extrabold transition active:translate-x-px active:translate-y-px disabled:cursor-not-allowed disabled:opacity-60 ${
                    saved ? 'bg-mint text-ink' : 'bg-ink text-paper'
                  }`}
                >
                  {saved
                    ? isLoggedIn ? '✓ 已收藏' : '✓ 已分享'
                    : saving
                      ? isLoggedIn ? '💌 儲存中…' : '💌 分享中…'
                      : isLoggedIn ? '💌 儲存並分享' : '💌 分享'}
                </button>
                {isMock ? (
                  // 不是錯誤、是引導，所以拿掉 ⚠️ 與警示色
                  <span className="text-right text-[11px] font-semibold text-muted">
                    先生成你自己的行程，就能分享囉 ✨
                  </span>
                ) : !isLoggedIn && !authDisabled && !saved ? (
                  <button
                    type="button"
                    onClick={() => setLoginModalOpen(true)}
                    className="text-[11px] font-semibold text-muted underline-offset-2 transition hover:text-coral-deep hover:underline"
                  >
                    登入即可儲存到我的行程 →
                  </button>
                ) : null}
              </div>
            </div>

            <TabNav active={activeTab} onChange={setActiveTab} />

            {activeTab === 'timeline' ? (
              <>
                {/* Day pills */}
                {tripPlan.days.length > 0 && (
                  <div className="flex flex-wrap gap-2">
                    {tripPlan.days.map((d) => {
                      const isActive = activeDay === d.day
                      return (
                        <button
                          type="button"
                          key={d.day}
                          onClick={() => handleDayChange(d.day)}
                          className={`rounded-full border-2 border-ink px-3.5 py-2 text-[13px] font-bold transition ${
                            isActive
                              ? '-translate-x-px -translate-y-px bg-coral text-white shadow-[2px_2px_0_var(--pop-shadow-color)]'
                              : 'bg-paper text-ink'
                          }`}
                        >
                          Day {d.day}{d.theme && ` · ${d.theme}`}
                          {d.weather && <span className="ml-1">{d.weather}</span>}
                        </button>
                      )
                    })}
                  </div>
                )}

                {/* Grid: timeline | sticky detail (desktop), single column (mobile) */}
                {currentDay && activeActivity && (
                  <div className={isDesktop ? 'grid grid-cols-[1.05fr_1fr] gap-5' : ''}>
                    {/* Left timeline */}
                    <div>
                      <DndContext
                        sensors={sensors}
                        collisionDetection={closestCenter}
                        onDragStart={handleDragStart}
                        onDragEnd={handleDragEnd}
                      >
                        <SortableContext
                          items={currentDay.activities.map((a) => a.id)}
                          strategy={verticalListSortingStrategy}
                        >
                          {currentDay.activities.map((a, idx) => {
                            const isActive = idx === safeIdx
                            const isLast = idx === currentDay.activities.length - 1
                            const isOpen = isActive && !detailCollapsed
                            return (
                              <Fragment key={a.id}>
                                <TimelineItem
                                  activity={a}
                                  isActive={isActive}
                                  isLast={isLast}
                                  expandable={!isDesktop}
                                  expanded={isOpen}
                                  onSelect={() => {
                                    // 點同一張 = 收合／展開；點別張 = 換選取並確保展開
                                    if (idx === safeIdx) setDetailCollapsed((c) => !c)
                                    else { setActiveIdx(idx); setDetailCollapsed(false) }
                                  }}
                                />
                                {!isDesktop && isOpen && (
                                  <div className="ml-[38px] mb-4">
                                    <DetailPanel activity={a} onExpand={setExpandedImage} isMock={isMock} showFavorite />
                                  </div>
                                )}
                              </Fragment>
                            )
                          })}
                        </SortableContext>
                        <DragOverlay>
                          {draggedActivity ? <DragOverlayCard activity={draggedActivity} /> : null}
                        </DragOverlay>
                      </DndContext>

                      {/* Mobile hotels */}
                      {!isDesktop && currentDay.hotels && currentDay.hotels.length > 0 && (
                        <div className="mt-2">
                          <div className="mb-2 flex items-center gap-1 text-[12px] font-extrabold text-mint-deep">
                            🛏 今晚住哪裡？
                          </div>
                          <div className="flex max-h-[280px] flex-col gap-2 overflow-y-auto pr-1">
                            {currentDay.hotels.map((h) => (
                              <HotelMini key={h.placeId ?? h.name} hotel={h} isMock={isMock} />
                            ))}
                          </div>
                        </div>
                      )}

                      {/* Mobile：住宿查詢失敗時給提示，而不是讓整塊無聲消失 */}
                      {!isDesktop && showHotelNotice && (
                        <div className="mt-2">
                          <div className="mb-2 flex items-center gap-1 text-[12px] font-extrabold text-mint-deep">
                            🛏 今晚住哪裡？
                          </div>
                          <HotelUnavailableNotice />
                        </div>
                      )}
                    </div>

                    {/* Right sticky detail (desktop only) */}
                    {isDesktop && (
                      <div className="self-start sticky top-5">
                        <DetailPanel
                          activity={activeActivity}
                          onExpand={setExpandedImage}
                          hotels={currentDay.hotels}
                          hotelsUnavailable={showHotelNotice}
                          isMock={isMock}
                          showFavorite
                        />
                      </div>
                    )}
                  </div>
                )}
              </>
            ) : (
              <TravelChecklist repo={checklistRepo} />
            )}
          </>
        )}
        </ErrorBoundary>
      </main>

      {showShareModal && shareUrl && (
        <ShareModal url={shareUrl} onClose={() => setShowShareModal(false)} />
      )}

      {loginModalOpen && <LoginModal onClose={() => setLoginModalOpen(false)} />}
    </div>
  )
}

export default App
