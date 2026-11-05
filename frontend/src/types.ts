// 行程資料的共用型別。原本 App.tsx 與 ViewPage.tsx 各自定義一份，
// imageUrl / hideMap / weather 等只活在前端 state 不會跨 /save 來回（詳見 SPEC §4），
// 用 optional 表達，兩處共用即可。

// time / location 後端有 @NotBlank 保證非空；description / transport / category 只有 @Size，
// AI 沒填就是 undefined。這裡標成 optional 是為了讓型別跟後端 contract 一致——
// 寫成 required 不會讓資料變完整，只會讓 tsc 幫忙背書一個不成立的假設，
// 然後在正式站變成白畫面（2026-08-13 UI-C04L7-Z8S0）。
export type Activity = {
  time: string
  location: string
  description?: string
  transport?: string
  category?: string
  emoji?: string
  // 以下為 mock / 前端 state 專用，不會被 /save 帶到後端
  imageUrl?: string
  hideMap?: boolean
}

export type Hotel = {
  name: string
  rating: number | null
  userRatingsTotal: number | null
  address: string | null
  placeId: string | null
  attribution?: string | null
  attributionUrl?: string | null
  // mock 專用
  imageUrl?: string
}

export type DayPlan = {
  day: number
  activities: Activity[]
  hotels?: Hotel[] | null
  theme?: string
  weather?: string
}

export type TripPlan = {
  title: string
  summary?: string
  days: DayPlan[]
  // 後端只在「本次生成時旅館查詢整段失敗」（Places 額度耗盡／授權失敗）才帶 true，
  // 正常時整個欄位不存在。它是生成當下的狀態、不是行程屬性，因此後端標了 READ_ONLY
  // 也不會存進 DB —— /view/:id 讀回來的行程永遠拿不到它，這是刻意的。
  hotelsUnavailable?: boolean | null
}

// API 回應；App.tsx 拿到後會 addIds() 轉成內部 ActivityWithId
export type ApiTripPlan = TripPlan

export type ExpandedImage = {
  url: string
  attribution?: string | null
  attributionUrl?: string | null
}
