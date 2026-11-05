// Activity.category 對應的視覺樣式（badge 顏色、預設 emoji）。
// App.tsx 與 ViewPage.tsx 各帶一份近乎相同的對應表，這裡統一。

export type CategoryStyle = {
  label: string
  badgeBg: string
  badgeFg: string
  emoji: string
}

export const CATEGORY: Record<string, CategoryStyle> = {
  景點: { label: '景點', badgeBg: 'bg-cat-sight-bg', badgeFg: 'text-cat-sight-fg', emoji: '🗺️' },
  美食: { label: '美食', badgeBg: 'bg-cat-food-bg',  badgeFg: 'text-cat-food-fg',  emoji: '🍜' },
  住宿: { label: '住宿', badgeBg: 'bg-cat-stay-bg',  badgeFg: 'text-cat-stay-fg',  emoji: '🛏️' },
}

// 容錯：完全相符 → 直接取；否則看是否含「美食」/「住宿」字樣，最後 fallback 景點。
// 對應 SPEC §4 Activity.category 的「前端容錯」描述。
//
// 參數必須容忍 undefined／null：後端 Activity.category 只有 @Size 沒有 @NotBlank，
// 也就是「AI 沒給 category」是通得過 contract 驗證的合法回應。少了下面那道 guard，
// undefined.includes() 會直接 throw，整個結果區被 ErrorBoundary 卸載 → 白畫面。
// （2026-08-13 正式站實際踩到：UI-C04L7-Z8S0，component=Polaroid / TimelineItem）
export function getCategory(category?: string | null): CategoryStyle {
  if (!category) return CATEGORY['景點']
  if (CATEGORY[category]) return CATEGORY[category]
  if (category.includes('美食')) return CATEGORY['美食']
  if (category.includes('住宿')) return CATEGORY['住宿']
  return CATEGORY['景點']
}
