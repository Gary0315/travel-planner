import { Link } from 'react-router-dom'
import { useAuth } from './useAuth'
import type { CurrentUser } from './AuthContext'

/**
 * 已登入態的下拉選單。視覺對齊 variation-b.jsx 的 AuthButtonB 內部 menu。
 *
 * 目前項目（順序對齊 HANDOFF.md §10.1 表格）：
 *   💌 我的行程 → /trips
 *   ↩  登出（coral-deep，dashed border 區隔）
 *
 * 「💾 收藏景點 / ✓ 行前清單 / ⚙ 帳戶設定」等項目待對應功能上線後再插在「我的行程」與「登出」之間。
 *
 * 關閉邏輯：由父層 AuthButton 監聽 click outside / Esc 並呼叫 onClose。
 */
export default function UserMenu({ user, onClose }: { user: CurrentUser; onClose: () => void }) {
  const { signOut } = useAuth()

  const handleLogout = async () => {
    await signOut()
    onClose()
  }

  return (
    <div
      id="roamie-user-menu"
      role="menu"
      onClick={(e) => e.stopPropagation()}
      className="absolute right-0 top-[calc(100%+8px)] z-30 min-w-[220px] rounded-[18px] border-2 border-ink bg-paper p-1.5 font-display shadow-[4px_4px_0_var(--pop-shadow-color)]"
    >
      {/* 個人資訊 header */}
      <div className="border-b-[1.5px] border-dashed border-[var(--color-border-soft)] px-3 pb-2 pt-2.5">
        <div className="truncate text-[13px] font-extrabold text-ink">{user.name} 👋</div>
        <div className="mt-0.5 truncate text-[11px] text-muted">{user.email}</div>
      </div>

      {/* 我的行程 */}
      <Link
        to="/trips"
        role="menuitem"
        onClick={onClose}
        className="mt-1 flex w-full items-center gap-2.5 rounded-xl px-3 py-2 text-left text-[13px] font-bold text-ink no-underline hover:bg-cream"
      >
        <span className="w-[18px] text-center text-[14px]" aria-hidden="true">
          💌
        </span>
        <span>我的行程</span>
      </Link>

      {/* 登出（coral-deep + dashed border 區隔 — 對齊 HANDOFF.md §10.1） */}
      <button
        type="button"
        onClick={handleLogout}
        role="menuitem"
        className="mt-1 flex w-full items-center gap-2.5 rounded-xl border-0 border-t-[1.5px] border-dashed border-[var(--color-border-soft)] bg-transparent px-3 py-2 pt-2.5 text-left text-[13px] font-bold text-coral-deep hover:bg-cream"
      >
        <span className="w-[18px] text-center text-[14px]" aria-hidden="true">
          ↩
        </span>
        <span>登出</span>
      </button>
    </div>
  )
}
