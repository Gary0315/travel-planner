import { useEffect, useMemo, useState } from 'react'
import type { ReactElement } from 'react'
import { Link } from 'react-router-dom'
import { apiFetch } from './api'
import { useAuth } from './auth/useAuth'

// ─── Types ────────────────────────────────────────────────────────────────────

type TripStatus = 'upcoming' | 'ongoing' | 'done'
type FilterKey = 'all' | 'upcoming' | 'done'
type MemberId = 'me' | 'yian' | 'jay' | 'annie'

type Trip = {
  id: string
  status: TripStatus
  title: string
  summary: string
  dateLabel: string
  days: number
  places: number
  coverEmoji: string
  coverBg: string // CSS background value (gradient)
  members: MemberId[]
  wallet: { total: number; count: number }
}

// 對應後端 MyTripCardResponse
type MyTripCardResponse = {
  shareId: string
  title: string
  summary?: string | null
  days: number
  places: number
  coverEmoji: string
  savedAt: string // ISO timestamp
}

// ─── Tokens (對齊 my-trips.jsx 的 MT / TRIP_STATUS / MEMBERS_MT) ──────────────

const STATUS_CHIP: Record<TripStatus, { label: string; emoji: string; cls: string }> = {
  upcoming: { label: '即將出發', emoji: '✈️', cls: 'bg-coral text-white' },
  ongoing: { label: '進行中', emoji: '📍', cls: 'bg-mint text-ink' },
  done: { label: '已完成', emoji: '✦', cls: 'bg-butter text-ink' },
}

const MEMBER_STYLE: Record<MemberId, { name: string; initial: string; bg: string; fg: string }> = {
  me: { name: '我', initial: '我', bg: 'bg-coral', fg: 'text-white' },
  yian: { name: '怡安', initial: '怡', bg: 'bg-mint', fg: 'text-ink' },
  jay: { name: '小傑', initial: '傑', bg: 'bg-sky-soft', fg: 'text-ink' },
  annie: { name: 'Annie', initial: 'A', bg: 'bg-butter', fg: 'text-ink' },
}

// ─── Cover bg pool ───────────────────────────────────────────────────────────
// 為了避免每張卡長一樣，按 shareId 雜湊挑一個漸層（同個 shareId 永遠對到同一張封面）。

const COVER_BGS: string[] = [
  'linear-gradient(135deg, oklch(0.88 0.09 350), oklch(0.93 0.08 60))', // pink → peach
  'linear-gradient(135deg, oklch(0.92 0.08 350), oklch(0.95 0.05 60))', // pink → butter
  'linear-gradient(135deg, oklch(0.93 0.08 60), oklch(0.88 0.10 30))', // butter → peach
  'linear-gradient(135deg, oklch(0.97 0.04 75), oklch(0.90 0.10 90))', // cream → butter
  'linear-gradient(135deg, oklch(0.82 0.08 230), oklch(0.92 0.07 165))', // sky → mint
]

function pickCoverBg(seed: string): string {
  let h = 0
  for (const ch of seed) h = (h * 31 + ch.charCodeAt(0)) | 0
  return COVER_BGS[Math.abs(h) % COVER_BGS.length]
}

function formatSavedDate(iso: string): string {
  try {
    const d = new Date(iso)
    if (Number.isNaN(d.getTime())) return ''
    return `${d.getMonth() + 1}/${d.getDate()}`
  } catch {
    return ''
  }
}

// 後端 MyTripCardResponse → 前端卡片用的 view model。
// 沒有的欄位（status / members / wallet）先用合理 placeholder，
// 之後後端加上實際資料時這個 mapping function 直接擴充。
function apiToTrip(r: MyTripCardResponse): Trip {
  const saved = formatSavedDate(r.savedAt)
  return {
    id: r.shareId,
    status: 'upcoming',
    title: r.title,
    summary: r.summary ?? '',
    dateLabel: saved ? `已儲存於 ${saved}` : '已儲存',
    days: r.days,
    places: r.places,
    coverEmoji: r.coverEmoji || '🎒',
    coverBg: pickCoverBg(r.shareId),
    members: ['me'],
    wallet: { total: 0, count: 0 },
  }
}

// ─── Helpers ──────────────────────────────────────────────────────────────────

function nt(n: number): string {
  return 'NT$' + Math.round(n).toLocaleString()
}

// ─── Avatars ──────────────────────────────────────────────────────────────────

function MiniAvatar({ id, size = 22 }: { id: MemberId; size?: number }) {
  const m = MEMBER_STYLE[id]
  return (
    <span
      title={m.name}
      className={`inline-flex shrink-0 items-center justify-center rounded-full border-2 border-ink font-extrabold ${m.bg} ${m.fg}`}
      style={{ width: size, height: size, fontSize: Math.round(size * 0.42) }}
    >
      {m.initial}
    </span>
  )
}

function MemberStack({ ids, size = 22, max = 4 }: { ids: MemberId[]; size?: number; max?: number }) {
  const show = ids.slice(0, max)
  const more = ids.length - max
  return (
    <span className="inline-flex items-center">
      {show.map((id, i) => (
        <span
          key={id}
          className="relative"
          style={{ marginLeft: i === 0 ? 0 : -7, zIndex: show.length - i }}
        >
          <MiniAvatar id={id} size={size} />
        </span>
      ))}
      {more > 0 && (
        <span className="ml-1.5 text-[11px] font-bold text-muted">+{more}</span>
      )}
    </span>
  )
}

// ─── Trip card ────────────────────────────────────────────────────────────────

function TripCard({ trip }: { trip: Trip }) {
  const status = STATUS_CHIP[trip.status]
  return (
    <Link
      to={`/view/${trip.id}`}
      className="block overflow-hidden rounded-[22px] border-2 border-ink bg-paper text-ink no-underline shadow-[3px_3px_0_var(--pop-shadow-color)] transition-all duration-150 hover:-translate-x-0.5 hover:-translate-y-0.5 hover:shadow-[5px_5px_0_var(--pop-shadow-color)]"
    >
      {/* Cover：16:10、底部 2px ink 邊分隔 */}
      <div
        className="relative aspect-[16/10] border-b-2 border-ink"
        style={{ background: trip.coverBg }}
      >
        <div
          aria-hidden
          className="absolute inset-0 flex items-center justify-center text-[68px]"
          style={{ filter: 'drop-shadow(2px 3px 0 oklch(0.28 0.04 50 / 0.18))' }}
        >
          {trip.coverEmoji}
        </div>
        {/* 狀態 chip */}
        <span
          className={`absolute left-2.5 top-2.5 inline-flex items-center gap-1 rounded-full border-[1.5px] border-ink px-2.5 py-1 text-[11px] font-extrabold shadow-[2px_2px_0_var(--pop-shadow-color)] ${status.cls}`}
        >
          <span>{status.emoji}</span>
          {status.label}
        </span>
        {/* 天數 / 地點數 chip */}
        <span className="absolute right-2.5 top-2.5 inline-flex items-center rounded-full border-[1.5px] border-ink bg-paper/95 px-2.5 py-1 text-[11px] font-extrabold text-ink">
          {trip.days} 天 · {trip.places} 個地點
        </span>
      </div>

      {/* Body */}
      <div className="px-4 pb-4 pt-3.5">
        <div className="mb-0.5 text-[17px] font-extrabold leading-[1.25] tracking-tight">
          {trip.title}
        </div>
        <div className="mb-2 truncate text-[12.5px] leading-[1.5] text-muted">
          {trip.summary}
        </div>

        {/* 日期 chip */}
        <div className="mb-2.5 flex flex-wrap items-center gap-2">
          <span className="rounded-full border border-[oklch(0.85_0.08_35)] bg-[oklch(0.96_0.05_35)] px-2.5 py-0.5 text-[11.5px] font-bold text-coral-deep">
            📅 {trip.dateLabel}
          </span>
        </div>

        {/* Footer：成員 + 共同記帳 chip */}
        <div className="flex items-center justify-between gap-2 border-t-[1.5px] border-dashed border-[var(--color-border-soft)] pt-2.5">
          <div className="flex min-w-0 items-center gap-1.5">
            <MemberStack ids={trip.members} size={22} max={4} />
            <span className="text-[11.5px] font-semibold text-muted">
              {trip.members.length} 人同行
            </span>
          </div>
          {trip.wallet.count > 0 ? (
            <span
              title="共同記帳"
              className="inline-flex shrink-0 items-center gap-1 rounded-full border-[1.5px] border-ink bg-butter px-2.5 py-1 text-[11.5px] font-extrabold text-ink"
            >
              🧾 {nt(trip.wallet.total)}
              <span className="font-semibold opacity-60">· {trip.wallet.count}</span>
            </span>
          ) : (
            <span className="shrink-0 text-[11px] font-semibold text-muted">🧾 未開始記帳</span>
          )}
        </div>
      </div>
    </Link>
  )
}

// ─── States: empty / loading / error ─────────────────────────────────────────

type EmptyVariant = 'signInRequired' | 'noTrips' | 'filterEmpty' | 'loadFailed'

function EmptyState({ variant }: { variant: EmptyVariant }) {
  // signInRequired / loadFailed 用「回首頁」CTA；noTrips / filterEmpty 用「規劃新行程」CTA
  const copy = {
    signInRequired: {
      emoji: '🔒',
      title: '請先登入才能查看你的行程',
      subtitle: '登入後，這趟旅程的所有規劃、地點、共同記帳都會在這裡看到 ✨',
      cta: '回首頁登入',
      ctaEmoji: '↩',
    },
    noTrips: {
      emoji: '🧳',
      title: '還沒有任何行程',
      subtitle: '規劃一趟新行程，把回憶都收進這裡 ✨',
      cta: '規劃新行程',
      ctaEmoji: '🎒',
    },
    filterEmpty: {
      emoji: '🧳',
      title: '沒有符合條件的行程',
      subtitle: '切換上面的篩選，或從頭規劃一個新行程吧 ✨',
      cta: '規劃新行程',
      ctaEmoji: '🎒',
    },
    loadFailed: {
      emoji: '⚠️',
      title: '無法載入行程',
      subtitle: '網路或伺服器暫時有問題，請稍後再試。',
      cta: '回首頁',
      ctaEmoji: '↩',
    },
  }[variant]

  return (
    <div className="rounded-[22px] border-2 border-dashed border-[var(--color-border-soft)] bg-paper px-6 py-14 text-center">
      <div className="mb-2 text-[48px]">{copy.emoji}</div>
      <div className="mb-1 text-[17px] font-extrabold text-ink">{copy.title}</div>
      <div className="mb-4 text-[13px] text-muted">{copy.subtitle}</div>
      <Link
        to="/"
        className="inline-flex items-center gap-1.5 rounded-full border-2 border-ink bg-coral px-5 py-3 text-[14px] font-extrabold text-white shadow-[3px_3px_0_var(--pop-shadow-color)] transition active:translate-x-px active:translate-y-px"
      >
        {copy.ctaEmoji} {copy.cta}
      </Link>
    </div>
  )
}

function LoadingState() {
  return (
    <div className="rounded-[22px] border-2 border-dashed border-[var(--color-border-soft)] bg-paper px-6 py-14 text-center">
      <div className="roamie-bounce mb-2 text-[40px]">🧳</div>
      <div className="text-[13px] font-semibold text-muted">正在載入你的行程⋯</div>
    </div>
  )
}

// ─── Page ─────────────────────────────────────────────────────────────────────

export default function MyTripsPage() {
  const { state: authState } = useAuth()
  const [filter, setFilter] = useState<FilterKey>('all')
  const [trips, setTrips] = useState<Trip[] | null>(null)
  const [loadError, setLoadError] = useState(false)

  // 只在「已登入」時才打 /api/users/me/trips；未登入直接畫 signInRequired empty state。
  // 重置 loadError 放在 .then / .catch（不在 effect 同步呼叫 setState，避免 cascading renders）。
  useEffect(() => {
    if (authState.status !== 'signedIn') return
    let cancelled = false
    apiFetch('/api/users/me/trips')
      .then(async (res) => {
        if (cancelled) return
        if (!res.ok) throw new Error(`failed: ${res.status}`)
        const data = (await res.json()) as MyTripCardResponse[]
        setTrips(data.map(apiToTrip))
        setLoadError(false)
      })
      .catch(() => {
        if (!cancelled) {
          setLoadError(true)
          setTrips([])
        }
      })
    return () => {
      cancelled = true
    }
  }, [authState.status])

  const counts = useMemo(
    () => ({
      all: trips?.length ?? 0,
      upcoming: trips?.filter((t) => t.status === 'upcoming').length ?? 0,
      done: trips?.filter((t) => t.status === 'done').length ?? 0,
    }),
    [trips],
  )

  const filteredTrips = useMemo(() => {
    if (!trips) return []
    return filter === 'all' ? trips : trips.filter((t) => t.status === filter)
  }, [filter, trips])

  const filters: { id: FilterKey; label: string }[] = [
    { id: 'all', label: '全部' },
    { id: 'upcoming', label: '即將出發' },
    { id: 'done', label: '已完成' },
  ]

  const hasAnyTrip = (trips?.length ?? 0) > 0

  // 主畫面區塊：依 auth + 載入結果切換
  let body: ReactElement
  if (authState.status === 'loading') {
    body = <LoadingState />
  } else if (authState.status !== 'signedIn') {
    body = <EmptyState variant="signInRequired" />
  } else if (trips === null) {
    // 已登入但 fetch 還沒結束
    body = <LoadingState />
  } else if (loadError) {
    body = <EmptyState variant="loadFailed" />
  } else if (filteredTrips.length === 0) {
    body = <EmptyState variant={hasAnyTrip ? 'filterEmpty' : 'noTrips'} />
  } else {
    body = (
      <div className="grid grid-cols-1 gap-4 md:grid-cols-[repeat(auto-fill,minmax(340px,1fr))]">
        {filteredTrips.map((t) => (
          <TripCard key={t.id} trip={t} />
        ))}
      </div>
    )
  }

  // filter pills 只在「已登入 + 有資料」時才顯示
  const showFilters = authState.status === 'signedIn' && hasAnyTrip && !loadError

  return (
    <div className="relative min-h-screen overflow-x-hidden bg-cream font-display text-ink">
      {/* Decorative blobs */}
      <div
        aria-hidden
        className="pointer-events-none absolute -right-10 -top-16 h-[200px] w-[200px] rounded-full bg-butter opacity-45 blur-[2px]"
      />
      <div
        aria-hidden
        className="pointer-events-none absolute left-[-80px] top-[280px] h-[240px] w-[240px] rounded-full bg-sky-soft opacity-30 blur-[2px]"
      />

      <main className="relative mx-auto w-full max-w-6xl px-4 pb-28 pt-4 md:px-8 md:pb-10 md:pt-7">
        {/* Header */}
        <div className="mb-4 flex items-center justify-between gap-2.5 md:mb-5">
          <div className="flex min-w-0 items-center gap-2.5">
            <Link
              to="/"
              title="返回"
              aria-label="返回"
              className="inline-flex h-[34px] w-[34px] shrink-0 items-center justify-center rounded-xl border-2 border-ink bg-paper text-base font-extrabold text-ink shadow-[2px_2px_0_var(--pop-shadow-color)] transition active:translate-x-px active:translate-y-px md:h-[38px] md:w-[38px]"
            >
              ←
            </Link>
            <div className="min-w-0">
              <div className="text-[10.5px] font-bold tracking-[0.1em] text-coral-deep">
                MY TRIPS ✦
              </div>
              <h1 className="m-0 whitespace-nowrap text-[19px] font-extrabold tracking-tight md:text-[24px]">
                我的行程
              </h1>
            </div>
          </div>
          <Link
            to="/"
            className="hidden items-center gap-1.5 rounded-full border-2 border-ink bg-coral px-4 py-2.5 text-[14px] font-extrabold text-white shadow-[3px_3px_0_var(--pop-shadow-color)] transition active:translate-x-px active:translate-y-px md:inline-flex"
          >
            ＋ 規劃新行程
          </Link>
        </div>

        {showFilters && (
          <div className="mb-4 flex flex-wrap gap-1.5">
            {filters.map((f) => {
              const on = filter === f.id
              return (
                <button
                  key={f.id}
                  type="button"
                  onClick={() => setFilter(f.id)}
                  aria-pressed={on}
                  className={`inline-flex items-center gap-1.5 rounded-full border-2 border-ink px-3.5 py-2 text-[13px] font-extrabold transition ${
                    on
                      ? '-translate-x-px -translate-y-px bg-ink text-paper shadow-[2px_2px_0_var(--pop-shadow-color)]'
                      : 'bg-paper text-ink'
                  }`}
                >
                  {f.label}
                  <span
                    className={`rounded-full px-1.5 py-px text-[10.5px] font-extrabold ${
                      on ? 'bg-coral text-white' : 'bg-cream text-muted'
                    }`}
                  >
                    {counts[f.id]}
                  </span>
                </button>
              )
            })}
          </div>
        )}

        {body}
      </main>

      {/* Mobile FAB（桌機隱藏） */}
      <Link
        to="/"
        className="fixed bottom-5 right-5 z-20 inline-flex items-center gap-1.5 rounded-full border-2 border-ink bg-coral px-5 py-3.5 text-[15px] font-extrabold text-white shadow-[4px_4px_0_var(--pop-shadow-color)] transition active:translate-x-px active:translate-y-px md:hidden"
      >
        ＋ 規劃新行程
      </Link>
    </div>
  )
}
