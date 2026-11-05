import { useEffect, useRef, useState } from 'react'
import { useAuth } from './useAuth'
import LoginModal from './LoginModal'
import UserMenu from './UserMenu'

/**
 * Header 右上的登入入口。視覺對齊 variation-b.jsx 的 AuthButtonB（HANDOFF.md §10.1）。
 *
 * 三種狀態：
 * - loading：渲染 null（避免閃動）
 * - signedOut：butter pill「🧳 登入 / 註冊」（手機簡寫成「🧳 登入」）→ 開 LoginModal
 * - signedIn：頭像膠囊 [首字圓] [姓名] [▾]（手機只顯示 [首字] [▾]）→ 開 UserMenu
 *
 * disabled（前端缺 VITE_GOOGLE_OAUTH_CLIENT_ID）：完全不渲染。
 */
export default function AuthButton() {
  const { state, disabled } = useAuth()
  const [modalOpen, setModalOpen] = useState(false)
  const [menuOpen, setMenuOpen] = useState(false)
  const buttonRef = useRef<HTMLButtonElement | null>(null)

  // 點外面關下拉
  useEffect(() => {
    if (!menuOpen) return
    const handler = (e: MouseEvent) => {
      if (buttonRef.current && !buttonRef.current.contains(e.target as Node)) {
        // UserMenu 自己也會擋傳到 window 的 click（stopPropagation），這裡只處理 button 以外
        const menuEl = document.getElementById('roamie-user-menu')
        if (menuEl && menuEl.contains(e.target as Node)) return
        setMenuOpen(false)
      }
    }
    // delay 一個 tick 避免和開啟同時觸發
    const t = setTimeout(() => window.addEventListener('click', handler), 0)
    return () => {
      clearTimeout(t)
      window.removeEventListener('click', handler)
    }
  }, [menuOpen])

  // Esc 關下拉
  useEffect(() => {
    if (!menuOpen) return
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') setMenuOpen(false)
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [menuOpen])

  if (disabled || state.status === 'loading') return null

  if (state.status === 'signedIn') {
    const initial = state.user.name.slice(0, 1)
    return (
      <div className="relative">
        <button
          ref={buttonRef}
          type="button"
          onClick={() => setMenuOpen((v) => !v)}
          className="inline-flex items-center gap-2 rounded-full border-2 border-ink bg-paper py-1 pl-1 pr-3 font-display shadow-[3px_3px_0_var(--pop-shadow-color)] transition active:translate-x-px active:translate-y-px"
          aria-haspopup="menu"
          aria-expanded={menuOpen}
        >
          <span className="inline-flex h-7 w-7 items-center justify-center rounded-full border-2 border-ink bg-mint text-[13px] font-extrabold text-ink">
            {initial}
          </span>
          {/* 手機 < 480px 只顯示頭像 + ▾，省空間 */}
        <span className="hidden text-[13px] font-extrabold text-ink min-[480px]:inline">{state.user.name}</span>
          <span className="text-[9px] text-muted">▾</span>
        </button>
        {menuOpen && <UserMenu user={state.user} onClose={() => setMenuOpen(false)} />}
      </div>
    )
  }

  // signedOut
  return (
    <>
      <button
        type="button"
        onClick={() => setModalOpen(true)}
        className="group inline-flex items-center gap-1.5 rounded-full border-2 border-ink bg-butter px-3.5 py-2 text-[13px] font-extrabold text-ink shadow-[3px_3px_0_var(--pop-shadow-color)] transition hover:-translate-x-px hover:-translate-y-px hover:shadow-[4px_4px_0_var(--pop-shadow-color)]"
        aria-haspopup="dialog"
      >
        <span className="text-[14px] leading-none">🧳</span>
        {/* 手機 < 480px 顯示「登入」、否則「登入 / 註冊」 */}
        <span className="hidden min-[480px]:inline">登入 / 註冊</span>
        <span className="inline min-[480px]:hidden">登入</span>
      </button>
      {modalOpen && <LoginModal onClose={() => setModalOpen(false)} />}
    </>
  )
}
