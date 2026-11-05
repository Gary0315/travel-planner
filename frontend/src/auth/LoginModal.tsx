import { useEffect, useState } from 'react'
import { GoogleLogin } from '@react-oauth/google'
import type { CredentialResponse } from '@react-oauth/google'
import { useAuth } from './useAuth'
import { useIsDesktop } from '../useIsDesktop'

/**
 * Login modal — 桌機置中 380px / 手機 bottom sheet。
 * 視覺對齊 variation-b.jsx 的 AuthOverlayB（HANDOFF.md §10.1）。
 *
 * 範圍限制（依使用者指示）：本版**只放 Google**；不放 Apple / OR 分隔線 / Email / magic link。
 *
 * Google 按鈕：用 @react-oauth/google 的 <GoogleLogin> 取得 ID Token（其他 hook 只能拿
 * access_token，與既有後端 /api/auth/google 期望的 ID Token 不相容）。Google 的官方按鈕
 * 樣式無法完全自訂，故外層額外包一層 pop-shadow 容器讓視覺貼近規格。
 */
export default function LoginModal({ onClose }: { onClose: () => void }) {
  const { signInWithIdToken } = useAuth()
  const isDesktop = useIsDesktop()
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)

  // Esc 關閉
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose()
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [onClose])

  // 鎖背景 scroll
  useEffect(() => {
    const original = document.body.style.overflow
    document.body.style.overflow = 'hidden'
    return () => {
      document.body.style.overflow = original
    }
  }, [])

  const handleGoogleCredential = async (response: CredentialResponse) => {
    if (!response.credential) {
      setError('Google 未回傳憑證，請重試')
      return
    }
    setError(null)
    setSubmitting(true)
    try {
      await signInWithIdToken(response.credential)
      onClose()
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div
      onClick={onClose}
      className="fixed inset-0 z-40 flex items-end justify-center bg-[rgba(40,30,20,0.45)] p-0 font-display md:items-center md:p-5"
      style={{ animation: 'roamie-auth-fade 0.18s ease-out' }}
      role="dialog"
      aria-modal="true"
      aria-labelledby="login-modal-title"
    >
      <div
        onClick={(e) => e.stopPropagation()}
        className={
          isDesktop
            ? 'relative w-full max-w-[380px] rounded-[26px] border-2 border-ink bg-paper p-7 shadow-[6px_6px_0_var(--pop-shadow-color)]'
            : 'relative w-full max-w-[420px] rounded-t-3xl border-2 border-b-0 border-ink bg-paper p-[22px]'
        }
        style={{
          animation: isDesktop
            ? 'roamie-auth-pop 0.25s cubic-bezier(0.2, 0.7, 0.3, 1)'
            : 'roamie-auth-slide-up 0.25s cubic-bezier(0.2, 0.7, 0.3, 1)',
        }}
      >
        {/* 手機：頂部拖動橫條 */}
        {!isDesktop && (
          <div className="mx-auto mb-3.5 h-[5px] w-12 rounded-full bg-[var(--color-border-soft)]" aria-hidden="true" />
        )}

        {/* 桌機：右上 × 關閉 */}
        {isDesktop && (
          <button
            type="button"
            onClick={onClose}
            aria-label="關閉登入視窗"
            className="absolute right-3.5 top-3.5 inline-flex h-[30px] w-[30px] items-center justify-center rounded-full border-2 border-ink bg-paper p-0 text-[14px] font-extrabold leading-none text-ink shadow-[2px_2px_0_var(--pop-shadow-color)]"
          >
            ×
          </button>
        )}

        {/* Sticker logo + eyebrow + H1 */}
        <div className={isDesktop ? 'mb-3.5 flex items-center gap-3' : 'mb-2.5 flex items-center gap-3'}>
          <div
            className={
              (isDesktop ? 'h-[42px] w-[42px] text-[22px]' : 'h-9 w-9 text-[18px]') +
              ' flex shrink-0 -rotate-6 items-center justify-center rounded-2xl border-2 border-ink bg-coral text-white shadow-[3px_3px_0_var(--pop-shadow-color)]'
            }
            aria-hidden="true"
          >
            ✈
          </div>
          <div>
            <div className="text-[11px] font-bold tracking-[0.1em] text-coral-deep">WELCOME BACK ✦</div>
            <div className="mt-0.5 text-[14px] font-extrabold text-ink">歡迎回來，旅人</div>
          </div>
        </div>

        {/* 主標 */}
        <div
          id="login-modal-title"
          className={
            (isDesktop ? 'mb-[18px] text-[20px]' : 'mb-3.5 text-[17px]') +
            ' font-extrabold leading-[1.25] tracking-[-0.02em] text-ink'
          }
        >
          登入後，即可管理你的行程!{' '}
          <span className="inline-block rotate-[10deg]" aria-hidden="true">
            📒
          </span>
        </div>

        {/* Google 按鈕：用我們的 pop-shadow 容器包 Google 官方按鈕（後者無法完全自訂） */}
        <div
          className={
            'mx-auto overflow-hidden rounded-full border-2 border-ink bg-paper shadow-[3px_3px_0_var(--pop-shadow-color)] ' +
            (submitting ? 'pointer-events-none opacity-60' : '')
          }
          style={{ width: 'fit-content' }}
        >
          <GoogleLogin
            onSuccess={handleGoogleCredential}
            onError={() => setError('Google 登入失敗，請重試')}
            theme="outline"
            shape="pill"
            text="continue_with"
            size="large"
            width="300"
            useOneTap={false}
          />
        </div>

        {submitting && (
          <div className="mt-3 text-center text-[12px] font-semibold text-muted">登入中⋯</div>
        )}

        {error && (
          <div className="mt-3 rounded-2xl border-2 border-coral-deep bg-paper px-3 py-2 text-center text-[12px] font-semibold text-coral-deep">
            {error}
          </div>
        )}

        <div className="mt-3.5 text-center text-[11px] leading-[1.5] text-muted">
          繼續即代表你同意我們的{' '}
          <a href="#" className="font-bold text-coral-deep">
            服務條款
          </a>{' '}
          和{' '}
          <a href="#" className="font-bold text-coral-deep">
            隱私政策
          </a>
        </div>
      </div>
    </div>
  )
}
