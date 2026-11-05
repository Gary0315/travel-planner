import { useState, useEffect, useMemo, Fragment } from 'react'
import { useParams, Link, useNavigate } from 'react-router-dom'
import { ArrowLeft, Sun, Moon, X, Trash2, Pencil } from 'lucide-react'
import { useTheme } from './useTheme'
import { useIsDesktop } from './useIsDesktop'
import TravelChecklist from './TravelChecklist'
import { TabNav } from './Tabs'
import type { TabKey } from './Tabs'
import { LocalChecklistRepo } from './checklistRepo'
import PhotoCredit from './components/PhotoCredit'
import HotelMini from './components/HotelMini'
import DetailPanel from './components/DetailPanel'
import type { Activity, TripPlan, ExpandedImage } from './types'
import { getCategory } from './categoryStyle'
import { getDeleteToken, clearDeleteToken } from './itineraryTokens'
import { apiFetch } from './api'
import AuthButton from './auth/AuthButton'

// ─── Timeline item ───────────────────────────────────────────────────────────

function TimelineItem({
  activity,
  isActive,
  isLast,
  onSelect,
}: {
  activity: Activity
  isActive: boolean
  isLast: boolean
  onSelect: () => void
}) {
  const cat = getCategory(activity.category)
  const emoji = activity.emoji || cat.emoji
  return (
    <div className="flex gap-3">
      <div className="flex shrink-0 flex-col items-center pt-1.5">
        <div className={`text-[22px] leading-none transition-transform duration-200 ${isActive ? 'scale-110' : ''}`}>
          {emoji}
        </div>
        {!isLast && <div className="mt-1 -mb-1 w-0.5 flex-1 rounded-full bg-ink/20" />}
      </div>
      <button
        type="button"
        onClick={onSelect}
        className={`mb-3 flex-1 cursor-pointer rounded-2xl border-2 border-ink bg-paper px-3.5 py-3 text-left transition-all duration-200 ${
          isActive
            ? '-translate-x-px -translate-y-px shadow-[3px_3px_0_var(--color-coral)]'
            : 'shadow-[2px_2px_0_var(--pop-shadow-color)]'
        }`}
      >
        <div className="mb-1 flex items-center justify-between gap-2">
          <span className="text-[12px] font-bold tabular-nums text-muted">{activity.time}</span>
          <span className={`rounded-full px-2.5 py-0.5 text-[10.5px] font-extrabold ${cat.badgeBg} ${cat.badgeFg}`}>
            {cat.label}
          </span>
        </div>
        <div className="text-[15px] font-extrabold tracking-tight text-ink">{activity.location}</div>
        <div className="mt-0.5 text-[12.5px] leading-relaxed text-muted">{activity.description}</div>
        {activity.transport && (
          <div className="mt-1.5 text-[11px] font-bold text-coral-deep">→ {activity.transport}</div>
        )}
      </button>
    </div>
  )
}

// ─── ViewPage ────────────────────────────────────────────────────────────────

export default function ViewPage() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()
  const [tripPlan, setTripPlan] = useState<TripPlan | null>(null)
  const [loading, setLoading] = useState(true)
  const [notFound, setNotFound] = useState(false)
  const [error, setError] = useState('')
  const [activeDay, setActiveDay] = useState(1)
  const [activeIdx, setActiveIdx] = useState(0)
  const [activeTab, setActiveTab] = useState<TabKey>('timeline')
  const [expandedImage, setExpandedImage] = useState<ExpandedImage | null>(null)
  const [deleting, setDeleting] = useState(false)
  const { theme, toggleTheme } = useTheme()
  const isDesktop = useIsDesktop()
  // 每位觀看者各自的本地清單，預設 10 項全未勾；以 shareId 為 key 避免不同行程互相干擾
  const checklistRepo = useMemo(() => (id ? new LocalChecklistRepo(`travelChecklist:view:${id}`) : null), [id])

  // 若使用者本機 localStorage 有對應此 shareId 的 deleteToken（save 當下後端塞給他的），
  // 代表他是這份分享的擁有者 → 顯示「刪除分享」按鈕。
  const deleteToken = useMemo(() => (id ? getDeleteToken(id) : null), [id])

  useEffect(() => {
    if (!id) return
    apiFetch(`/api/itinerary/${id}`)
      .then((res) => {
        if (res.status === 404) {
          setNotFound(true)
          return null
        }
        if (!res.ok) throw new Error('載入失敗，請稍後再試。')
        return res.json() as Promise<TripPlan>
      })
      .then((data) => {
        if (!data) return
        setTripPlan(data)
        setActiveDay(data.days[0]?.day ?? 1)
      })
      .catch((err) => setError(err instanceof Error ? err.message : '載入失敗'))
      .finally(() => setLoading(false))
  }, [id])

  const handleEdit = () => {
    if (!tripPlan) return
    // 把目前的 TripPlan 塞進 sessionStorage，App.tsx 啟動時會撿起來當成編輯起點，
    // 存檔後會產生新的 shareId，原本的分享連結不動。
    sessionStorage.setItem('editTripPlan', JSON.stringify(tripPlan))
    navigate('/')
  }

  const handleDelete = async () => {
    if (!id || !deleteToken) return
    if (!window.confirm('確定要永久刪除這份分享連結嗎？刪除後其他人就再也看不到了。')) return
    setDeleting(true)
    try {
      const res = await apiFetch(`/api/itinerary/${id}`, {
        method: 'DELETE',
        headers: { 'X-Delete-Token': deleteToken },
      })
      if (!res.ok && res.status !== 204) throw new Error('刪除失敗')
      // 清掉 local token，避免下次點進來還顯示刪除按鈕
      clearDeleteToken(id)
      navigate('/')
    } catch {
      window.alert('刪除失敗，請稍後再試。')
      setDeleting(false)
    }
  }

  useEffect(() => {
    if (!expandedImage) return
    const handleKey = (e: KeyboardEvent) => { if (e.key === 'Escape') setExpandedImage(null) }
    window.addEventListener('keydown', handleKey)
    return () => window.removeEventListener('keydown', handleKey)
  }, [expandedImage])

  const currentDay = tripPlan?.days.find((d) => d.day === activeDay) ?? tripPlan?.days[0]
  const safeIdx = currentDay ? Math.min(activeIdx, currentDay.activities.length - 1) : 0
  const activeActivity = currentDay?.activities[safeIdx]

  const handleDayChange = (day: number) => {
    setActiveDay(day)
    setActiveIdx(0)
  }

  return (
    <div className="relative min-h-screen overflow-x-hidden bg-cream font-display text-ink">
      <div aria-hidden className="pointer-events-none fixed -right-10 -top-16 h-[200px] w-[200px] rounded-full bg-butter opacity-50 blur-[2px]" />
      <div aria-hidden className="pointer-events-none fixed left-[-80px] top-[200px] h-[240px] w-[240px] rounded-full bg-sky-soft opacity-35 blur-[2px]" />

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
              <div className="text-[10.5px] text-muted">分享行程預覽</div>
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
            <Link
              to="/"
              className="inline-flex items-center gap-1 rounded-full border-2 border-ink bg-paper px-3.5 py-2 text-[13px] font-bold text-ink"
            >
              <ArrowLeft className="h-4 w-4" />
              自己規劃
            </Link>
            <AuthButton />
          </div>
        </header>

        {loading ? (
          <section className="rounded-[26px] border-2 border-ink bg-paper p-8 text-center shadow-[4px_4px_0_var(--pop-shadow-color)]">
            <div className="roamie-bounce inline-block text-[32px]">🗺️</div>
            <div className="mt-1 text-sm font-extrabold text-ink">載入中⋯</div>
          </section>
        ) : notFound ? (
          <section className="rounded-[26px] border-2 border-ink bg-paper px-6 py-10 text-center shadow-[5px_5px_0_var(--pop-shadow-color)]">
            <div className="mb-3 inline-flex items-end gap-1 text-[56px] leading-none">
              <span className="roamie-bounce inline-block">🗺️</span>
              <span className="roamie-bounce inline-block" style={{ animationDelay: '0.15s' }}>💨</span>
            </div>
            <h2 className="text-[18px] font-extrabold tracking-tight text-ink md:text-[20px]">
              這趟旅程已經出發了，找不到了
            </h2>
            <p className="mx-auto mt-2 max-w-sm text-[13px] leading-relaxed text-muted">
              這個分享連結可能已經被擁有者刪除，或超過 90 天沒人來看而被系統收進回憶箱了。
            </p>
            <div className="mt-6 flex items-center justify-center gap-2">
              <Link
                to="/"
                className="inline-flex items-center gap-1.5 rounded-full border-2 border-ink bg-coral px-5 py-2.5 text-[13px] font-extrabold text-white shadow-[3px_3px_0_var(--pop-shadow-color)] transition active:translate-x-px active:translate-y-px"
              >
                <ArrowLeft className="h-4 w-4" />
                返回首頁
              </Link>
            </div>
          </section>
        ) : error ? (
          <section className="rounded-[26px] border-2 border-coral-deep bg-paper p-6 shadow-[4px_4px_0_var(--color-coral)]">
            <p className="text-sm font-extrabold text-coral-deep">⚠️ {error}</p>
          </section>
        ) : tripPlan ? (
          <>
            {/* Trip header chip — always visible */}
            <div className="flex flex-wrap items-center justify-between gap-2.5 rounded-[22px] border-2 border-ink bg-paper px-4 py-3 shadow-[3px_3px_0_var(--pop-shadow-color)]">
              <div className="min-w-0 flex-1">
                <div className="text-[11px] font-bold tracking-[0.08em] text-coral-deep">SHARED TRIP ✦</div>
                <div className="mt-px text-[17px] font-extrabold tracking-tight text-ink md:text-[21px]">
                  {tripPlan.title}
                </div>
                {tripPlan.summary && (
                  <div className="mt-0.5 text-[12.5px] text-muted">{tripPlan.summary}</div>
                )}
              </div>
              <div className="flex flex-wrap items-center gap-2">
                <button
                  type="button"
                  onClick={handleEdit}
                  className="inline-flex items-center gap-1 rounded-full border-2 border-ink bg-paper px-3.5 py-2 text-[12.5px] font-extrabold text-ink transition active:translate-x-px active:translate-y-px"
                >
                  <Pencil className="h-3.5 w-3.5" />
                  編輯這個行程
                </button>
                {deleteToken && (
                  <button
                    type="button"
                    onClick={handleDelete}
                    disabled={deleting}
                    className="inline-flex items-center gap-1 rounded-full border-2 border-coral-deep bg-paper px-3.5 py-2 text-[12.5px] font-extrabold text-coral-deep transition active:translate-x-px active:translate-y-px disabled:cursor-not-allowed disabled:opacity-60"
                  >
                    <Trash2 className="h-3.5 w-3.5" />
                    {deleting ? '刪除中⋯' : '刪除分享'}
                  </button>
                )}
              </div>
            </div>

            <TabNav active={activeTab} onChange={setActiveTab} />

            {activeTab === 'timeline' ? (
              <>
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

                {currentDay && activeActivity && (
                  <div className={isDesktop ? 'grid grid-cols-[1.05fr_1fr] gap-5' : ''}>
                    <div>
                      {currentDay.activities.map((a, idx) => {
                        const isActive = idx === safeIdx
                        const isLast = idx === currentDay.activities.length - 1
                        return (
                          <Fragment key={idx}>
                            <TimelineItem
                              activity={a}
                              isActive={isActive}
                              isLast={isLast}
                              onSelect={() => setActiveIdx(idx)}
                            />
                            {!isDesktop && isActive && (
                              <div className="ml-[38px] mb-4">
                                <DetailPanel activity={a} onExpand={setExpandedImage} />
                              </div>
                            )}
                          </Fragment>
                        )
                      })}

                      {!isDesktop && currentDay.hotels && currentDay.hotels.length > 0 && (
                        <div className="mt-2">
                          <div className="mb-2 flex items-center gap-1 text-[12px] font-extrabold text-mint-deep">
                            🛏 今晚住哪裡？
                          </div>
                          <div className="flex max-h-[280px] flex-col gap-2 overflow-y-auto pr-1">
                            {currentDay.hotels.map((h) => (
                              <HotelMini key={h.placeId ?? h.name} hotel={h} />
                            ))}
                          </div>
                        </div>
                      )}
                    </div>

                    {isDesktop && (
                      <div className="self-start sticky top-5">
                        <DetailPanel
                          activity={activeActivity}
                          hotels={currentDay.hotels}
                          onExpand={setExpandedImage}
                        />
                      </div>
                    )}
                  </div>
                )}
              </>
            ) : checklistRepo ? (
              <TravelChecklist repo={checklistRepo} />
            ) : null}
          </>
        ) : null}
      </main>
    </div>
  )
}
