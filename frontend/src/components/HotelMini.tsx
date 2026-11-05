import { useState } from 'react'
import { Star } from 'lucide-react'
import PhotoCredit from './PhotoCredit'
import { useInView, usePlacePhoto, FALLBACK_IMAGE, THUMB_WIDTH } from '../usePlacePhoto'
import type { Hotel } from '../types'

/**
 * 飯店推薦小卡 — App.tsx 與 ViewPage.tsx 共用。
 *
 * isMock=true 時不渲染為可點擊連結（mock 飯店無真實 placeId，點下去會跳到 Google Maps
 * 隨機搜尋結果，體驗很差）。預設 false（適用 ViewPage 與 App 的正式 hotel）。
 *
 * 縮圖來源優先序：hotel.imageUrl（mock）> 後端 attribution（Nearby Search 直接給的）
 * > usePlacePhoto(hotel.name) 回來的；無圖時走 FALLBACK_IMAGE。
 */
export default function HotelMini({
  hotel,
  isMock = false,
}: {
  hotel: Hotel
  isMock?: boolean
}) {
  const [containerRef, inView] = useInView()
  // 縮圖在畫面上只有 72px，卻曾經一律抓 600px 的圖回來——而傳輸量正是圖片載入的主要成本
  // （SPEC §14.9）。改索取 200px：2x DPR 之下仍有餘裕，位元組少一大截。
  const { url, attribution: hookAttr, attributionUrl: hookAttrUrl } = usePlacePhoto(
    hotel.imageUrl,
    inView ? hotel.name : undefined,
    THUMB_WIDTH,
  )
  const [failedUrl, setFailedUrl] = useState<string | null>(null)
  const [loadedUrl, setLoadedUrl] = useState<string | null>(null)
  const displayUrl = url && failedUrl === url ? FALLBACK_IMAGE : (url ?? FALLBACK_IMAGE)
  const mapsUrl = hotel.placeId
    ? `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(hotel.name)}&query_place_id=${hotel.placeId}`
    : `https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(hotel.name)}`

  // 飯店縮圖優先用 hotel 自帶的 attribution（Nearby Search 直接給的），
  // 沒有時退回 usePlacePhoto（用 name 再打 Find Place 拿來的）。
  const attribution = hotel.attribution ?? hookAttr
  const attributionUrl = hotel.attributionUrl ?? hookAttrUrl

  const inner = (
    <>
      <div className="w-[72px] shrink-0 self-stretch min-h-[72px] overflow-hidden rounded-xl border-[1.5px] border-ink bg-cream">
        <img
          src={displayUrl}
          alt={hotel.name}
          loading="lazy"
          onLoad={() => setLoadedUrl(displayUrl)}
          onError={() => {
            if (url) setFailedUrl(url)
            setLoadedUrl(displayUrl)
          }}
          // 載好之前隱形，露出容器的 bg-cream。切換日期時飯店整批換掉，
          // 而瀏覽器換 src 時預設會繼續顯示舊圖 —— 那會讓卡片短暫掛著
          // 上一間飯店的照片，比空白更誤導人。
          className={`h-full w-full object-cover transition-opacity duration-300 ${
            loadedUrl === displayUrl ? 'opacity-100' : 'opacity-0'
          }`}
        />
      </div>
      <div className="min-w-0 flex-1">
        <div className="truncate text-[13px] font-extrabold text-ink">{hotel.name}</div>
        {hotel.rating != null && (
          <div className="mt-0.5 flex items-center gap-1 text-[11px] font-semibold text-muted">
            <Star className="h-3 w-3 fill-butter text-butter" />
            {hotel.rating.toFixed(1)}
            {hotel.userRatingsTotal != null && (
              <span className="opacity-70">· {hotel.userRatingsTotal.toLocaleString()}</span>
            )}
          </div>
        )}
        {hotel.address && (
          <div className="mt-0.5 truncate text-[10.5px] text-muted">{hotel.address}</div>
        )}
        {attribution && (
          <div className="mt-0.5">
            <PhotoCredit name={attribution} href={attributionUrl} variant="inline" />
          </div>
        )}
      </div>
    </>
  )

  if (isMock) {
    return (
      <div
        ref={containerRef}
        className="flex gap-2.5 rounded-2xl border-2 border-ink bg-paper p-2 shadow-[2px_2px_0_var(--pop-shadow-color)]"
      >
        {inner}
      </div>
    )
  }

  // 用 div + role=link 而非 <a>，因為內部 PhotoCredit 也是 <a>，
  // 巢狀 <a> 會被瀏覽器拆掉導致行為不可預期。
  const openMaps = () => window.open(mapsUrl, '_blank', 'noopener,noreferrer')
  return (
    <div
      ref={containerRef}
      role="link"
      tabIndex={0}
      onClick={openMaps}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          openMaps()
        }
      }}
      className="flex cursor-pointer gap-2.5 rounded-2xl border-2 border-ink bg-paper p-2 shadow-[2px_2px_0_var(--pop-shadow-color)] transition hover:-translate-x-px hover:-translate-y-px hover:shadow-[3px_3px_0_var(--pop-shadow-color)]"
    >
      {inner}
    </div>
  )
}
