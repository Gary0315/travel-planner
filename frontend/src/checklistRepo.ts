// Checklist 儲存抽象。三種實作：
//   - LocalChecklistRepo: localStorage（匿名 + 未存）
//   - ShareChecklistRepo: /api/itinerary/{shareId}/checklist（已存／檢視分享連結）
//   - （未來）UserChecklistRepo: 登入後綁使用者
//
// UI 元件只認 ChecklistRepository interface，不知道資料在哪。

export type ChecklistItem = {
  id: string
  content: string
  checked: boolean
  /**
   * 是否為首次 seed 的預設項目。**2026-08-12 起不再影響任何行為**——它原本用來擋刪除，
   * 那個限制已拿掉（見 LocalChecklistRepo.remove）。保留欄位是為了相容既有 localStorage
   * 資料，以及未來 UserChecklistRepo 可能需要區分來源。
   */
  defaultItem: boolean
}

export interface ChecklistRepository {
  list(): Promise<ChecklistItem[]>
  add(content: string): Promise<ChecklistItem>
  toggle(id: string): Promise<ChecklistItem>
  remove(id: string): Promise<void>
  /**
   * 把缺少的預設項目補回來，回傳補完後的完整清單。
   *
   * 開放刪除預設項目（2026-08-12）之後，使用者刪掉就再也拿不回來——除非自己去清
   * localStorage，那不是使用者該知道的事。這個方法是那條路的出口。
   *
   * **補缺、不重加**：已存在的（依 content 比對）原封不動，連勾選狀態一起保留。
   * 做成 repository 方法而不是在 UI 跑 10 次 add()，是因為對未來 API-backed 的實作，
   * 前者是一次請求、後者是十次。
   */
  restoreDefaults(): Promise<ChecklistItem[]>
}

// ─── Defaults ────────────────────────────────────────────────────────────────

export const DEFAULT_CHECKLIST_ITEMS = [
  '護照 / 身分證件',
  '錢包與信用卡',
  '手機',
  '手機充電器與線',
  '行動電源',
  '鑰匙',
  '換洗衣物',
  '個人常備藥品',
  '盥洗用品',
  '面紙',
]

function buildDefaults(): ChecklistItem[] {
  return DEFAULT_CHECKLIST_ITEMS.map((content) => ({
    id: crypto.randomUUID(),
    content,
    checked: false,
    defaultItem: true,
  }))
}

// ─── Local (localStorage) ────────────────────────────────────────────────────

export const DEFAULT_LOCAL_KEY = 'travelChecklist:v1'

function readLocal(key: string): ChecklistItem[] {
  try {
    const raw = localStorage.getItem(key)
    if (!raw) return buildDefaults()
    const parsed = JSON.parse(raw) as ChecklistItem[]
    // 只有「壞資料」才重建預設；**空陣列是合法狀態，不能 re-seed**。
    // 預設項目開放刪除後（2026-08-12），使用者可能把清單清空——原本的
    // `parsed.length === 0 → buildDefaults()` 會讓那 10 條在下次開啟時全部復活，
    // 使用者刪了半天等於白刪。「從未初始化」由上面的 `!raw` 負責，兩者要分開。
    if (!Array.isArray(parsed)) return buildDefaults()
    return parsed
  } catch {
    return buildDefaults()
  }
}

function writeLocal(key: string, items: ChecklistItem[]): void {
  try {
    localStorage.setItem(key, JSON.stringify(items))
  } catch {
    // ignore quota / privacy errors
  }
}

export class LocalChecklistRepo implements ChecklistRepository {
  private readonly key: string

  constructor(storageKey = DEFAULT_LOCAL_KEY) {
    this.key = storageKey
  }

  async list(): Promise<ChecklistItem[]> {
    const items = readLocal(this.key)
    // 第一次讀取（空 storage）會 build defaults — 把它寫回 storage 讓後續讀取一致
    writeLocal(this.key, items)
    return items
  }

  async add(content: string): Promise<ChecklistItem> {
    const item: ChecklistItem = {
      id: crypto.randomUUID(),
      content: content.trim(),
      checked: false,
      defaultItem: false,
    }
    const items = [...readLocal(this.key), item]
    writeLocal(this.key, items)
    return item
  }

  async toggle(id: string): Promise<ChecklistItem> {
    const items = readLocal(this.key)
    const idx = items.findIndex((i) => i.id === id)
    if (idx === -1) throw new Error('項目不存在')
    const updated = { ...items[idx], checked: !items[idx].checked }
    items[idx] = updated
    writeLocal(this.key, items)
    return updated
  }

  async remove(id: string): Promise<void> {
    const items = readLocal(this.key)
    // 預設項目原本在這裡被擋掉（連同 UI 不給按刪除鈕）。2026-08-12 拿掉：
    // 使用者反映國內行程用不到「護照 / 身分證件」卻刪不掉。找不到才 no-op。
    if (!items.some((i) => i.id === id)) return
    writeLocal(this.key, items.filter((i) => i.id !== id))
  }

  async restoreDefaults(): Promise<ChecklistItem[]> {
    const items = readLocal(this.key)
    const existing = new Set(items.map((i) => i.content.trim()))
    // 依 content 比對而非 id：使用者刪掉「護照 / 身分證件」後又自己手打一條一樣的，
    // 這裡就不該再補一條重複的給他
    const missing = DEFAULT_CHECKLIST_ITEMS.filter((content) => !existing.has(content))
    if (missing.length === 0) return items
    const restored = [
      ...items,
      ...missing.map((content) => ({
        id: crypto.randomUUID(),
        content,
        checked: false,
        defaultItem: true,
      })),
    ]
    writeLocal(this.key, restored)
    return restored
  }
}

