import { useEffect, useState } from 'react'
import Polaroid from './Polaroid'
import HotelMini from './HotelMini'
import HotelUnavailableNotice from './HotelUnavailableNotice'
import type { Activity, Hotel, ExpandedImage } from '../types'

/**
 * 行程細節面板：大圖（Polaroid）＋ 描述 ＋ 操作按鈕 ＋ 今晚住宿。
 * App.tsx 與 ViewPage.tsx 共用。
 *
 * App.tsx 額外用到 isMock（控制 HotelMini 是否可點）與 showFavorite（顯示「加入收藏」），
 * 以 optional prop 容納；ViewPage 不傳即可。`activity.hideMap` 由 Activity 型別已含的
 * optional 欄位驅動，正式行程不會帶這旗標。
 */
export default function DetailPanel({
  activity,
  hotels,
  hotelsUnavailable = false,
  onExpand,
  isMock = false,
  showFavorite = false,
}: {
  activity: Activity
  hotels?: Hotel[] | null
  // 住宿查詢整段失敗時顯示提示，取代「住宿區無聲消失」。
  // 由呼叫端判斷該不該顯示（最後一天本來就沒住宿，那天不該出現）；ViewPage 不傳。
  hotelsUnavailable?: boolean
  onExpand?: (img: ExpandedImage) => void
  isMock?: boolean
  showFavorite?: boolean
}) {
  const mapsUrl = `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(activity.location)}`

  // 「加入收藏」還沒實作。原本是一顆完全沒有 onClick 的按鈕，點了毫無反應——
  // 親友回饋（2026-08-06）把它讀成「大概要登入吧」，比沒有這顆按鈕更傷信任。
  // 在功能做好前，至少讓它明講自己還沒好。
  // 記「是哪個景點按的」而不是單純的 boolean：換景點時條件自然不成立、提示就消失，
  // 不必為了重置再開一個 effect（同 usePlacePhoto 以 key 比對的做法）。
  const [comingSoonFor, setComingSoonFor] = useState<string | null>(null)
  const comingSoon = comingSoonFor === activity.location

  useEffect(() => {
    if (!comingSoonFor) return
    const id = setTimeout(() => setComingSoonFor(null), 2600)
    return () => clearTimeout(id)
  }, [comingSoonFor])

  return (
    <div>
      <Polaroid activity={activity} onExpand={onExpand} />
      <div className="mt-3.5 rounded-2xl border-2 border-ink bg-paper p-3.5 shadow-[2px_2px_0_var(--pop-shadow-color)]">
        <p className="text-[12.5px] leading-relaxed text-ink">{activity.description}</p>
        {activity.transport && (
          <p className="mt-2 text-[11.5px] font-bold text-coral-deep">→ {activity.transport}</p>
        )}
        <div className="mt-2.5 flex flex-wrap gap-1.5">
          {activity.hideMap ? (
            <span className="cursor-not-allowed rounded-full border-[1.5px] border-ink/30 bg-sky-soft/40 px-3 py-1.5 text-[12px] font-extrabold text-ink/30">
              📍 看地圖
            </span>
          ) : (
            <a
              href={mapsUrl}
              target="_blank"
              rel="noopener noreferrer"
              className="rounded-full border-[1.5px] border-ink bg-sky-soft px-3 py-1.5 text-[12px] font-extrabold text-ink transition active:translate-x-px active:translate-y-px"
            >
              📍 看地圖
            </a>
          )}
          {showFavorite && (
            <button
              type="button"
              onClick={() => setComingSoonFor(activity.location)}
              className="rounded-full border-[1.5px] border-ink bg-butter px-3 py-1.5 text-[12px] font-extrabold text-ink transition active:translate-x-px active:translate-y-px"
            >
              💾 加入收藏
            </button>
          )}
        </div>
        {comingSoon && (
          <div
            role="status"
            className="roamie-toast-in mt-2.5 flex items-start gap-1.5 rounded-[14px] border-2 border-ink bg-butter px-3 py-2 text-[11.5px] font-bold leading-relaxed text-ink shadow-[2px_2px_0_var(--pop-shadow-color)]"
          >
            <span aria-hidden="true">🚧</span>
            <span>「加入收藏」還在趕工中，敬請期待！目前可以用上面的「💌 分享」把整趟行程存成連結 ✨</span>
          </div>
        )}
      </div>
      {hotels && hotels.length > 0 && (
        <div className="mt-4">
          <div className="mb-2 flex items-center gap-1 text-[12px] font-extrabold text-mint-deep">
            🛏 今晚住哪裡？
          </div>
          <div className="flex max-h-[280px] flex-col gap-2 overflow-y-auto pr-1">
            {hotels.map((h) => (
              <HotelMini key={h.placeId ?? h.name} hotel={h} isMock={isMock} />
            ))}
          </div>
        </div>
      )}
      {hotelsUnavailable && (
        <div className="mt-4">
          <div className="mb-2 flex items-center gap-1 text-[12px] font-extrabold text-mint-deep">
            🛏 今晚住哪裡？
          </div>
          <HotelUnavailableNotice />
        </div>
      )}
    </div>
  )
}
