/**
 * 統一的 fetch 入口。負責兩件事：
 *
 * 1. **CSRF 雙 submit 防護**：對非 safe methods 自動把 `XSRF-TOKEN` cookie 的值寫進
 *    `X-XSRF-TOKEN` header，符合 Spring Security 6 的 CookieCsrfTokenRepository 約定。
 *    第一個 POST 之前若 cookie 還沒被後端寫進來（cold start），先發一個 GET 觸發
 *    Spring Security filter chain 把 cookie 寫上來再 retry。
 *
 * 2. **統一 credentials**：明確指定 `same-origin` 讓 session cookie 在 same-origin
 *    部署（dev 走 Vite proxy / prod 走單一容器）下會被瀏覽器自動附上。呼叫端要覆寫
 *    可以自己傳 credentials。
 *
 * 詳見 SPEC §19.7。
 */

const SAFE_METHODS = new Set(['GET', 'HEAD', 'OPTIONS', 'TRACE'])

/**
 * 從 document.cookie 撈指定名稱的值。
 * 不用 regex 是為了避開三類邊角：
 *  1. name 若含 regex 特殊字元（XSRF-TOKEN 不會，但這個 helper 是通用的）
 *  2. cookie value 含 `=`（base64 / JWT 都可能尾端有 =）
 *  3. 不同瀏覽器 join cookies 時的空白差異（標準是 "; "，部分情境只有 ";"）
 */
function readCookie(name: string): string | null {
  if (!document.cookie) return null
  for (const raw of document.cookie.split(';')) {
    const part = raw.trim()
    const eqIdx = part.indexOf('=')
    if (eqIdx === -1) continue
    if (part.substring(0, eqIdx) === name) {
      try {
        return decodeURIComponent(part.substring(eqIdx + 1))
      } catch {
        // malformed % escape — 寧可回 null 讓上層降級走 bootstrap retry，也不要噴例外
        return null
      }
    }
  }
  return null
}

let csrfBootstrap: Promise<void> | null = null

async function ensureCsrfCookie(): Promise<void> {
  if (readCookie('XSRF-TOKEN')) return
  if (!csrfBootstrap) {
    // 任何 GET 進 Spring Security filter chain 都會寫 XSRF-TOKEN cookie（SecurityConfig
    // 加了 CsrfCookieEagerFilter 強制 deferred token 在每個 request 都 resolve）。
    // /api/auth/me 即使回 401 也能達成目的；用 .catch 吞掉錯誤避免影響後續流程。
    csrfBootstrap = fetch('/api/auth/me', { credentials: 'same-origin' })
      .catch(() => undefined)
      .then(() => undefined)
  }
  await csrfBootstrap
  // 若還是拿不到（後端整個掛掉或 mock 沒回 cookie），讓後續流程自己處理 403
  if (!readCookie('XSRF-TOKEN')) {
    csrfBootstrap = null // 失敗就清掉，下次再試
  }
}

export async function apiFetch(input: RequestInfo | URL, init: RequestInit = {}): Promise<Response> {
  const method = (init.method ?? 'GET').toUpperCase()
  const headers = new Headers(init.headers)

  if (!SAFE_METHODS.has(method)) {
    await ensureCsrfCookie()
    const token = readCookie('XSRF-TOKEN')
    if (token) headers.set('X-XSRF-TOKEN', token)
  }

  return fetch(input, {
    ...init,
    credentials: init.credentials ?? 'same-origin',
    headers,
  })
}
