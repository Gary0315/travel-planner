import { useState } from 'react'
import { ZoomIn } from 'lucide-react'
import PhotoCredit from './PhotoCredit'
import { useInView, usePlacePhoto, FALLBACK_IMAGE } from '../usePlacePhoto'
import { getCategory } from '../categoryStyle'
import type { Activity, ExpandedImage } from '../types'

/**
 * 活動主圖（拍立得風格）— App.tsx 與 ViewPage.tsx 共用。
 * 自動 lazy-load 場景照片（usePlacePhoto），按下會回呼 onExpand 開啟 lightbox。
 *
 * activity.imageUrl 有值時走 mock 圖路徑；否則用 location 名稱去打 /api/places/photo。
 */
export default function Polaroid({
  activity,
  onExpand,
}: {
  activity: Activity
  onExpand?: (img: ExpandedImage) => void
}) {
  const [containerRef, inView] = useInView()
  const cat = getCategory(activity.category)
  const emoji = activity.emoji || cat.emoji
  const { url, attribution, attributionUrl } = usePlacePhoto(
    activity.imageUrl,
    inView ? activity.location : undefined,
  )
  const [failedUrl, setFailedUrl] = useState<string | null>(null)
  const [loadedUrl, setLoadedUrl] = useState<string | null>(null)
  const displayUrl = url && failedUrl === url ? FALLBACK_IMAGE : url
  // 骨架改成「疊在圖片上」而不是「取代圖片」。<img> 必須一開始就在 DOM 裡，
  // 否則瀏覽器根本不會開始下載，usePlacePhoto 的樂觀網址就白做了。
  // 用 loadedUrl 比對而非 boolean：換圖時條件自然重新成立、骨架自己回來，
  // 不必為了重置再開一個 effect（同本檔 failedUrl 的做法）。
  const showSkeleton = displayUrl != null && loadedUrl !== displayUrl

  return (
    <div
      ref={containerRef}
      className="-rotate-[1.5deg] rounded-md border-2 border-ink bg-paper p-2.5 shadow-[4px_4px_0_var(--pop-shadow-color)]"
    >
      <div className="relative aspect-[4/3] overflow-hidden rounded bg-cream">
        <button
          type="button"
          onClick={() =>
            displayUrl && onExpand?.({ url: displayUrl, attribution, attributionUrl })
          }
          className="group block h-full w-full cursor-zoom-in"
        >
          <img
            src={displayUrl ?? FALLBACK_IMAGE}
            alt={activity.location}
            loading="lazy"
            onLoad={() => setLoadedUrl(displayUrl)}
            onError={() => {
              if (url) setFailedUrl(url)
              // 連 fallback 都載不出來時，至少別讓骨架永遠轉下去
              setLoadedUrl(displayUrl)
            }}
            // 載好之前先隱形：animate-pulse 是在動 opacity，骨架淡到 0.5 時
            // 會把底下「瀏覽器還沒換掉的上一張圖」透出來，變成灰→舊圖→新圖的閃動。
            // 圖自己隱形，骨架底下就只剩容器的 bg-cream。
            className={`h-full w-full object-cover transition duration-300 group-hover:scale-105 ${
              showSkeleton ? 'opacity-0' : 'opacity-100'
            }`}
          />
          <span className="pointer-events-none absolute inset-0 flex items-center justify-center bg-ink/0 transition-colors duration-300 group-hover:bg-ink/25">
            <ZoomIn className="h-8 w-8 text-white opacity-0 drop-shadow-lg transition-opacity duration-300 group-hover:opacity-100" />
          </span>
        </button>
        {showSkeleton && (
          <div className="pointer-events-none absolute inset-0 animate-pulse bg-border-soft" />
        )}
        {/* 底色必須用 bg-paper 這個 token，不能寫死 bg-white：text-ink 在深色模式會翻成
            淺色(0.92)，配寫死的白底就是淺字印在淺底上、整個時間看不見（同 index.css
            深色模式那段註解講的失效模式，差別在這裡是繞過 token 系統而不是漏翻）。 */}
        <span className="absolute left-2.5 top-2.5 rounded-full border border-ink/10 bg-paper/95 px-2.5 py-1 text-[11px] font-bold text-ink">
          {activity.time}
        </span>
        <PhotoCredit name={attribution} href={attributionUrl} variant="default" />
      </div>
      <div className="-rotate-[0.5deg] pt-3 pb-1 text-center font-hand text-[22px] font-bold text-ink">
        {activity.location} <span className="text-lg">{emoji}</span>
      </div>
    </div>
  )
}
