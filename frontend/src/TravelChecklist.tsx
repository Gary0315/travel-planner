import { useEffect, useRef, useState } from 'react'
import type { FormEvent } from 'react'
import { Loader2 } from 'lucide-react'
import { DEFAULT_CHECKLIST_ITEMS } from './checklistRepo'
import type { ChecklistItem, ChecklistRepository } from './checklistRepo'

export default function TravelChecklist({ repo }: { repo: ChecklistRepository }) {
  const [items, setItems] = useState<ChecklistItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [newContent, setNewContent] = useState('')
  const [adding, setAdding] = useState(false)
  const [restoring, setRestoring] = useState(false)
  const repoRef = useRef(repo)

  useEffect(() => {
    repoRef.current = repo
    let cancelled = false
    setLoading(true)
    setError(null)
    repo
      .list()
      .then((data) => {
        if (cancelled || repoRef.current !== repo) return
        setItems(data)
      })
      .catch((err) => {
        if (cancelled) return
        setError(err instanceof Error ? err.message : '載入失敗')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [repo])

  const handleToggle = async (item: ChecklistItem) => {
    const optimistic = { ...item, checked: !item.checked }
    setItems((prev) => prev.map((i) => (i.id === item.id ? optimistic : i)))
    try {
      const updated = await repo.toggle(item.id)
      setItems((prev) => prev.map((i) => (i.id === updated.id ? updated : i)))
    } catch (err) {
      setItems((prev) => prev.map((i) => (i.id === item.id ? item : i)))
      setError(err instanceof Error ? err.message : '更新失敗')
    }
  }

  const handleAdd = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    const content = newContent.trim()
    if (!content) return
    setAdding(true)
    setError(null)
    const tempId = `tmp-${crypto.randomUUID()}`
    const optimistic: ChecklistItem = {
      id: tempId,
      content,
      checked: false,
      defaultItem: false,
    }
    setItems((prev) => [...prev, optimistic])
    setNewContent('')
    try {
      const saved = await repo.add(content)
      setItems((prev) => prev.map((i) => (i.id === tempId ? saved : i)))
    } catch (err) {
      setItems((prev) => prev.filter((i) => i.id !== tempId))
      setError(err instanceof Error ? err.message : '新增失敗')
    } finally {
      setAdding(false)
    }
  }

  const handleDelete = async (item: ChecklistItem) => {
    const snapshot = items
    setItems((prev) => prev.filter((i) => i.id !== item.id))
    try {
      await repo.remove(item.id)
    } catch (err) {
      setItems(snapshot)
      setError(err instanceof Error ? err.message : '刪除失敗')
    }
  }

  const handleRestoreDefaults = async () => {
    setRestoring(true)
    setError(null)
    const snapshot = items
    try {
      setItems(await repo.restoreDefaults())
    } catch (err) {
      setItems(snapshot)
      setError(err instanceof Error ? err.message : '補回預設項目失敗')
    } finally {
      setRestoring(false)
    }
  }

  const completed = items.filter((i) => i.checked).length
  const total = items.length
  const progress = total > 0 ? (completed / total) * 100 : 0

  // 只在真的有東西可補時才顯示按鈕——預設全在的人看到它只會困惑，
  // 而按了沒反應的按鈕比沒有按鈕更傷信任（同 F1-10 那顆死掉的「加入收藏」）
  const missingDefaults = DEFAULT_CHECKLIST_ITEMS.filter(
    (content) => !items.some((i) => i.content.trim() === content),
  ).length

  return (
    <div className="rounded-[22px] border-2 border-ink bg-paper p-4 shadow-[3px_3px_0_var(--pop-shadow-color)] md:p-6">
      {/* Header */}
      <div className="mb-3.5 flex flex-wrap items-end justify-between gap-2.5">
        <div className="min-w-0">
          <div className="text-[11px] font-bold tracking-[0.08em] text-mint-deep">PACK YOUR BAG ✓</div>
          <div className="mt-0.5 text-[18px] font-extrabold tracking-tight text-ink md:text-[22px]">行前清單</div>
          <div className="mt-0.5 text-[12.5px] text-muted">出門前打勾一下，以免漏帶 ✏️</div>
        </div>
        <div className="inline-flex items-center gap-1.5 rounded-full border-2 border-ink bg-mint px-3.5 py-2 text-[13px] font-extrabold text-ink">
          {completed} / {total} 項完成
        </div>
      </div>

      {/* Progress bar */}
      <div className="mb-3.5 h-2.5 overflow-hidden rounded-full border-[1.5px] border-ink bg-cream">
        <div
          className="h-full bg-mint transition-all duration-300"
          style={{ width: `${progress}%` }}
        />
      </div>

      {/* Add new */}
      <form onSubmit={handleAdd} className="mb-3.5 flex gap-2">
        <input
          type="text"
          value={newContent}
          onChange={(e) => setNewContent(e.target.value)}
          placeholder="新增自訂項目…"
          maxLength={500}
          className="flex-1 rounded-full border-2 border-ink bg-cream px-4 py-2 text-[14px] font-medium text-ink outline-none placeholder:text-muted/60"
        />
        <button
          type="submit"
          disabled={adding || !newContent.trim()}
          className="rounded-full border-2 border-ink bg-coral px-4 py-2 text-[14px] font-extrabold text-white shadow-[2px_2px_0_var(--pop-shadow-color)] transition active:translate-x-px active:translate-y-px disabled:cursor-not-allowed disabled:opacity-50 disabled:shadow-none"
        >
          + 加入
        </button>
      </form>


      {loading ? (
        <div className="flex items-center gap-2 text-sm text-muted">
          <Loader2 className="h-4 w-4 animate-spin" />
          載入中...
        </div>
      ) : items.length === 0 ? (
        <div className="text-sm text-muted">目前沒有項目，新增一個吧。</div>
      ) : (
        <div className="grid grid-cols-1 gap-2 md:grid-cols-2">
          {items.map((item) => (
            <label
              key={item.id}
              className={`flex cursor-pointer items-center gap-2.5 rounded-[14px] border-2 border-ink px-3 py-2.5 shadow-[2px_2px_0_var(--pop-shadow-color)] transition ${
                item.checked ? 'bg-mint' : 'bg-paper'
              }`}
            >
              <button
                type="button"
                onClick={(e) => {
                  e.preventDefault()
                  handleToggle(item)
                }}
                aria-label={item.checked ? '取消勾選' : '勾選'}
                className={`inline-flex h-[22px] w-[22px] shrink-0 items-center justify-center rounded-md border-2 border-ink transition ${
                  item.checked ? 'bg-ink text-paper' : 'bg-paper'
                }`}
              >
                {item.checked && <span className="text-[13px] font-extrabold leading-none">✓</span>}
              </button>
              <span
                className={`flex-1 text-[14px] font-semibold text-ink transition ${
                  item.checked ? 'opacity-60' : ''
                }`}
              >
                {item.content}
              </span>
              {/* 預設項目原本不給刪（親友回饋 2026-08-12 F4-02）：國內兩天一夜的行程，
                  「護照 / 身分證件」擺在第一條卻刪不掉，只能一直看著它。清單是給人用的，
                  不是給人繞過的——限制拿掉，預設項目與自訂項目一視同仁。 */}
              <button
                type="button"
                onClick={(e) => {
                  e.preventDefault()
                  handleDelete(item)
                }}
                aria-label={`刪除 ${item.content}`}
                className="inline-flex h-[22px] w-[22px] shrink-0 items-center justify-center rounded-full border-[1.5px] border-ink bg-paper text-[12px] font-extrabold leading-none text-ink transition hover:bg-coral hover:text-white"
              >
                ×
              </button>
            </label>
          ))}
        </div>
      )}

      {/* 放在清單「下方」而不是上方：這顆按鈕會隨著缺不缺預設項目出現／消失，
          擺在上方的話，刪掉第一條的瞬間它憑空冒出來、把整份清單往下推一格，
          使用者看到的就是「刪一行畫面亂跳」（親友回饋 2026-08-12）。 */}
      {!loading && missingDefaults > 0 && (
        <div className="mt-3.5">
          <button
            type="button"
            onClick={handleRestoreDefaults}
            disabled={restoring}
            className="inline-flex items-center gap-1.5 rounded-full border-[1.5px] border-ink bg-cream px-3 py-1.5 text-[12.5px] font-bold text-ink transition hover:bg-mint active:translate-x-px active:translate-y-px disabled:cursor-not-allowed disabled:opacity-50"
          >
            {restoring ? '補回中…' : `↩ 補回預設項目（${missingDefaults}）`}
          </button>
        </div>
      )}

      {error && (
        <p className="mt-3 text-sm font-semibold text-coral-deep">{error}</p>
      )}
    </div>
  )
}
