/**
 * 住宿查詢整段失敗（Places 額度耗盡／授權失敗）時顯示的提示。
 *
 * 重點是最後那句「不是這附近沒有旅館」—— 在這之前，兩件完全不同的事在畫面上長得一模一樣：
 * 額度爆掉沒查成、與這附近真的沒有達標旅館，都只是住宿區整塊無聲消失（2026-08-24 查證）。
 *
 * 刻意<b>不</b>寫「稍後再試就會出現」：額度是每日制，當天重試無效；
 * 授權失敗（key 無效／API 未啟用）更是重試永遠沒用。承諾做不到的事比不提示更傷信任。
 *
 * 顯示時機由呼叫端決定 —— 最後一天與單日行程本來就不查旅館，那些天不該出現本提示。
 */
export default function HotelUnavailableNotice() {
  return (
    <div
      role="status"
      className="flex items-start gap-1.5 rounded-[14px] border-2 border-ink bg-butter px-3 py-2 text-[11.5px] font-bold leading-relaxed text-ink shadow-[2px_2px_0_var(--pop-shadow-color)]"
    >
      <span aria-hidden="true">🛏️</span>
      <span>住宿建議暫時查不到——是查詢服務的狀況，不是這附近沒有旅館。行程其他部分不受影響 ✨</span>
    </div>
  )
}
