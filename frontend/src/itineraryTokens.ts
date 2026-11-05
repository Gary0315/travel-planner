// 行程刪除 token 的本地保存。
//
// 後端 /save 回傳 deleteToken 給「擁有者」（DB 只存 SHA-256 hash，詳見 SPEC §16）。
// 前端用 localStorage 維護 { [shareId]: deleteToken } 對應表：
//   - App.tsx save 完成後寫入
//   - ViewPage.tsx 載入時讀取 → 有對應 token 才顯示「刪除分享」按鈕
//   - ViewPage.tsx 刪除成功後移除該筆
//
// 使用者換瀏覽器 / 清 localStorage 即永久失去刪除權，那份分享需等 90 天清理。

const STORAGE_KEY = 'itineraryTokens:v1'

function readAll(): Record<string, string> {
  try {
    const raw = localStorage.getItem(STORAGE_KEY)
    if (!raw) return {}
    const parsed = JSON.parse(raw)
    return typeof parsed === 'object' && parsed !== null ? parsed as Record<string, string> : {}
  } catch {
    return {}
  }
}

function writeAll(tokens: Record<string, string>): void {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(tokens))
  } catch {
    // 配額爆掉或 SSR 環境，安靜失敗
  }
}

export function saveDeleteToken(shareId: string, token: string): void {
  const tokens = readAll()
  tokens[shareId] = token
  writeAll(tokens)
}

export function getDeleteToken(shareId: string): string | null {
  return readAll()[shareId] ?? null
}

export function clearDeleteToken(shareId: string): void {
  const tokens = readAll()
  if (!(shareId in tokens)) return
  delete tokens[shareId]
  writeAll(tokens)
}
