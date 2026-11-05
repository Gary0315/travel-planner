import { useEffect, useRef, useState } from 'react'
import { apiFetch } from './api'

// 景點 / 飯店縮圖載入。
// 統一 App.tsx 與 ViewPage.tsx 兩處重複的實作；行為與 App.tsx 原版一致（含 placeId 欄位）。
// ViewPage 用不到 placeId 直接忽略即可。

export const FALLBACK_IMAGE = '/mock-images/picture.svg'

/**
 * 後端圖片 proxy 的網址規則，與 `PlaceServiceImpl.buildProxyUrl()` 一致。
 *
 * 前端自己算得出來，就不必等 `/api/places/photo` 回應才知道要去哪抓圖 ——
 * 那個 metadata 請求實測約 700ms（2026-08-26），而在它回來之前 `<img>` 連一個 byte
 * 都還沒開始下載。等於為了一行署名文字，把整張圖往後推了一整個網路往返。
 */
function binaryUrlFor(keyword: string, width?: number): string {
  const base = `/api/places/photo/binary?name=${encodeURIComponent(keyword)}`
  return width ? `${base}&w=${width}` : base
}

/**
 * `HotelMini` 的 72px 縮圖用。省略 width 時後端預設 600（給 `Polaroid` 與放大的 lightbox）。
 *
 * **只能填後端白名單裡的值**（`PlaceController.ALLOWED_WIDTHS`）—— 每個相異寬度都是一次
 * 獨立的 Google Place Photos 計費呼叫，白名單外的值後端會回 400。
 */
export const THUMB_WIDTH = 200

/** 同一個地點的不同尺寸是兩張不同的圖，模組快取必須分開存，否則會互相污染。 */
function cacheKeyFor(keyword: string, width?: number): string {
  return width ? `${keyword}@${width}` : keyword
}

export type ThumbnailState = {
  loading: boolean
  url: string | null
  placeId: string | null
  attribution: string | null
  attributionUrl: string | null
}

type CachedThumbnail = {
  url: string
  placeId: string | null
  attribution: string | null
  attributionUrl: string | null
}

// 模組層級快取：同一個 keyword 不重打 API（即使切換頁面）。Map<keyword, cached>
const thumbnailCache = new globalThis.Map<string, CachedThumbnail>()

const EMPTY_DATA: ThumbnailState = {
  loading: false,
  url: FALLBACK_IMAGE,
  placeId: null,
  attribution: null,
  attributionUrl: null,
}

type StateWithKey = { key: string | undefined; data: ThumbnailState }

function computeInitial(k: string | undefined, width?: number): ThumbnailState {
  if (!k) return EMPTY_DATA
  const cached = thumbnailCache.get(cacheKeyFor(k, width))
  if (cached) return { loading: false, ...cached }
  // 樂觀網址：立刻給出圖片位址讓 <img> 開始下載，metadata（署名、placeId）平行取、晚點補上。
  // `loading` 改成只代表「metadata 還在路上」，不再代表「圖還不能顯示」——
  // 消費端若拿它來決定要不要渲染 <img>，就會把這個優化整個抵消掉（圖不在 DOM 裡不會下載）。
  return {
    loading: true,
    url: binaryUrlFor(k, width),
    placeId: null,
    attribution: null,
    attributionUrl: null,
  }
}

/**
 * 用 IntersectionObserver 偵測元素是否進入畫面，搭配 usePlacePhoto 做 lazy 載圖。
 * 一旦進入畫面就 disconnect，不會再切回 false。
 */
export function useInView(rootMargin = '100px') {
  const ref = useRef<HTMLDivElement>(null)
  const [inView, setInView] = useState(false)
  useEffect(() => {
    const el = ref.current
    if (!el) return
    const observer = new IntersectionObserver(
      ([entry]) => { if (entry.isIntersecting) { setInView(true); observer.disconnect() } },
      { rootMargin },
    )
    observer.observe(el)
    return () => { observer.disconnect() }
  }, [rootMargin])
  return [ref, inView] as const
}

/**
 * 取得景點 / 飯店縮圖。
 *
 * @param imageUrl 靜態圖（mock 資料用）— 有值時直接回傳，不打 API
 * @param keyword 地點 / 飯店名稱 — 沒有靜態圖時呼叫 /api/places/photo
 * @param width 向後端索取的圖片寬度；省略則用後端預設 600。只能填後端白名單裡的值（見 `THUMB_WIDTH`）
 *
 * 行為要點：
 *  - **圖片網址立刻回傳**（`binaryUrlFor`），不等 `/api/places/photo`。署名與 placeId 平行取回後補上
 *  - 模組層級 `thumbnailCache` 命中時不重打 API
 *  - 切 keyword 時清狀態（race-safe：fetch 回來時對比 key 才更新）
 *  - `/api/places/photo` 失敗時 → EMPTY_DATA（fallback 圖）。**刻意保守**：拿不到署名就不顯示
 *    Places 照片，維持 SPEC §13.6 的 ToS 合規，代價是圖可能已顯示又被換成 fallback
 *  - 地點沒有照片時，`<img>` 會對 `/binary` 收到 404 —— 由消費端既有的 `onError` 接住換 fallback
 *    （`Polaroid.tsx` / `HotelMini.tsx` 都已有），metadata 回來時也會再確認一次
 */
export function usePlacePhoto(
  imageUrl: string | undefined,
  keyword: string | undefined,
  width?: number,
): ThumbnailState {
  // 快取與 race 比對一律用「keyword + 尺寸」的複合鍵：同一個地點可能同時被大圖與縮圖使用，
  // 只用 keyword 會讓先查到的那個尺寸餵給另一個。
  const cacheKey = keyword === undefined ? undefined : cacheKeyFor(keyword, width)

  const [state, setState] = useState<StateWithKey>(() => ({
    key: cacheKey,
    data: computeInitial(imageUrl ? undefined : keyword, width),
  }))

  if (!imageUrl && state.key !== cacheKey) {
    setState({ key: cacheKey, data: computeInitial(keyword, width) })
  }

  useEffect(() => {
    if (imageUrl || !keyword || !cacheKey || thumbnailCache.has(cacheKey)) return
    const controller = new AbortController()

    apiFetch(`/api/places/photo?name=${encodeURIComponent(keyword)}`, { signal: controller.signal })
      .then((res) => res.json())
      .then(
        (data: {
          imageUrl: string
          placeId: string | null
          attribution?: string | null
          attributionUrl?: string | null
        }) => {
          const cached: CachedThumbnail = {
            // 只有後端說「這個地點沒照片」時才換掉樂觀網址；有照片就沿用自己算的那個。
            // 不直接採用 data.imageUrl 是因為兩邊百分比編碼規則有細微差異
            // （Spring 的 UriUtils.encode 會編 !'()* ，encodeURIComponent 不會），
            // 字串一旦不同，React 會視為換圖，瀏覽器就白白把同一張重抓一次。
            url:
              !data.imageUrl || data.imageUrl === FALLBACK_IMAGE
                ? FALLBACK_IMAGE
                : binaryUrlFor(keyword, width),
            placeId: data.placeId ?? null,
            attribution: data.attribution ?? null,
            attributionUrl: data.attributionUrl ?? null,
          }
          thumbnailCache.set(cacheKey, cached)
          setState((prev) =>
            prev.key === cacheKey
              ? { key: cacheKey, data: { loading: false, ...cached } }
              : prev,
          )
        },
      )
      .catch((err) => {
        if (err.name === 'AbortError') return
        setState((prev) =>
          prev.key === cacheKey ? { key: cacheKey, data: EMPTY_DATA } : prev,
        )
      })

    return () => { controller.abort() }
  }, [imageUrl, keyword, width, cacheKey])

  if (imageUrl) {
    return { loading: false, url: imageUrl, placeId: null, attribution: null, attributionUrl: null }
  }
  return state.data
}
