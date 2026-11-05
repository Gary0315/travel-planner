import { createContext, useCallback, useEffect, useMemo, useState } from 'react'
import type { ReactNode } from 'react'
import { apiFetch } from '../api'

/**
 * Google 登入狀態。詳見 SPEC §19。
 *
 * 設計重點：
 * - 啟動時打 GET /api/auth/me 還原 session（後端 Spring Session JDBC 跨重啟仍有效）
 * - 接到 401 視為未登入（不算錯誤，匿名使用是合法狀態）
 * - 後端缺 client-id 回 503 時，整個登入系統 disabled，前端 hide 登入按鈕
 * - 沒設 VITE_GOOGLE_OAUTH_CLIENT_ID 時前端也走 disabled，AuthProvider 不會渲染 GIS
 */

export type CurrentUser = {
  id: number
  email: string
  name: string
  pictureUrl?: string | null
}

type AuthState =
  | { status: 'loading' }
  | { status: 'signedOut'; error?: string }
  | { status: 'signedIn'; user: CurrentUser }

export type AuthContextValue = {
  state: AuthState
  /** 前端設定缺 client id，或後端回 503 */
  disabled: boolean
  signInWithIdToken: (idToken: string) => Promise<void>
  signOut: () => Promise<void>
}

// hook 在 useAuth.ts。Context 物件需與 AuthProvider 同檔（useState/useCallback hook
// 共用），這違反 react-refresh/only-export-components 對「component 檔只能 export
// component」的偏好；本 export 唯一消費者是 useAuth.ts，影響 fast refresh 範圍很小。
// eslint-disable-next-line react-refresh/only-export-components
export const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children, disabled }: { children: ReactNode; disabled: boolean }) {
  const [state, setState] = useState<AuthState>(disabled ? { status: 'signedOut' } : { status: 'loading' })

  // 啟動還原 session
  useEffect(() => {
    if (disabled) return
    let cancelled = false
    apiFetch('/api/auth/me')
      .then(async (res) => {
        if (cancelled) return
        if (res.status === 401) {
          setState({ status: 'signedOut' })
          return
        }
        if (res.status === 503) {
          // 後端沒設 client-id：本 session 內視為 disabled
          setState({ status: 'signedOut' })
          return
        }
        if (!res.ok) {
          setState({ status: 'signedOut' })
          return
        }
        const user = (await res.json()) as CurrentUser
        setState({ status: 'signedIn', user })
      })
      .catch(() => {
        if (!cancelled) setState({ status: 'signedOut' })
      })
    return () => {
      cancelled = true
    }
  }, [disabled])

  const signInWithIdToken = useCallback(async (idToken: string) => {
    setState({ status: 'loading' })
    try {
      const res = await apiFetch('/api/auth/google', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ idToken }),
      })
      if (res.status === 503) {
        setState({ status: 'signedOut', error: '登入功能尚未啟用' })
        return
      }
      if (!res.ok) {
        const data = await res.json().catch(() => ({}))
        const msg = (data as { message?: string }).message ?? '登入失敗，請稍後再試'
        setState({ status: 'signedOut', error: msg })
        return
      }
      const user = (await res.json()) as CurrentUser
      setState({ status: 'signedIn', user })
    } catch {
      setState({ status: 'signedOut', error: '登入失敗，請稍後再試' })
    }
  }, [])

  const signOut = useCallback(async () => {
    try {
      await apiFetch('/api/auth/logout', { method: 'POST' })
    } catch {
      // 即使 server-side logout 失敗，前端仍清狀態
    }
    setState({ status: 'signedOut' })
  }, [])

  const value = useMemo<AuthContextValue>(
    () => ({ state, disabled, signInWithIdToken, signOut }),
    [state, disabled, signInWithIdToken, signOut],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}
