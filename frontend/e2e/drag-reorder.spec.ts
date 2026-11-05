import { test, expect } from '@playwright/test'
import type { Page } from '@playwright/test'
import { mockApi } from './fixtures/mockApi'

/**
 * 拖曳重排的時間重算測試 — 守護 commit 5fedf52 的 bug fix。
 *
 * 不變式：把任一活動拖到第一位後，新的第一筆活動的開始時間
 * 必須等於「重排前」的當天第一筆開始時刻；而不是被拖過去那筆活動
 * 原本的開始時刻。
 *
 * 舊版 bug：reassignTimes 用 activities[0].time 當起點，
 * 結果整天時間軸跟著被拖的活動位移。
 *
 * 細節：dnd-kit PointerSensor 設了 activationConstraint distance=8，
 * 所以滑鼠按下後必須先做一次 >8px 的小移動才會「啟動」拖曳；
 * 中間用 steps>1 拆分位移以確保 dnd-kit 的 over 偵測能觸發。
 */

const DRAG_REORDER_PLAN = {
  title: '拖曳測試行程',
  summary: null,
  days: [
    {
      day: 1,
      theme: '東區散步',
      activities: [
        {
          time: '09:00 - 10:00',
          location: '中正紀念堂',
          description: 'A',
          transport: '步行 10 分鐘',
          category: '景點',
          emoji: '🏛️',
        },
        {
          time: '10:10 - 11:30',
          location: '永康街',
          description: 'B',
          transport: '步行 15 分鐘',
          category: '美食',
          emoji: '🍜',
        },
        {
          time: '11:45 - 13:00',
          location: '象山步道',
          description: 'C',
          transport: '步行 20 分鐘',
          category: '景點',
          emoji: '⛰️',
        },
      ],
      hotels: null,
    },
  ],
}

// dnd-kit PointerSensor + Playwright 拖曳：用 page.mouse 配合刻意的延遲。
// Chromium 會在 mousedown/move/up 同步合成 pointerdown/move/up，但 dnd-kit 對序列
// 順序與 batch 時機敏感，需要小段移動 + 短延遲讓 React 提交狀態。
async function dndKitDrag(
  page: Page,
  sourceHandle: ReturnType<Page['locator']>,
  targetCard: ReturnType<Page['locator']>,
) {
  await sourceHandle.scrollIntoViewIfNeeded()
  await targetCard.scrollIntoViewIfNeeded()
  await sourceHandle.hover()  // hover 會 scroll + 移動指標到中心

  const sourceBox = await sourceHandle.boundingBox()
  const targetBox = await targetCard.boundingBox()
  if (!sourceBox || !targetBox) throw new Error('drag source/target not visible')

  const sx = sourceBox.x + sourceBox.width / 2
  const sy = sourceBox.y + sourceBox.height / 2
  const tx = targetBox.x + targetBox.width / 2
  // 落點放在 target 的上 1/4 處，sortable-strategy 比較容易判定為「插到 target 之前」
  const ty = targetBox.y + targetBox.height / 4

  await page.mouse.move(sx, sy)
  await page.mouse.down()
  await page.waitForTimeout(50)
  // 啟動 PointerSensor 的 activationConstraint distance=8
  await page.mouse.move(sx, sy + 12)
  await page.waitForTimeout(50)
  // 慢慢移動到目標，分 30 個小步驟 + 每步停頓
  await page.mouse.move(tx, ty, { steps: 30 })
  await page.waitForTimeout(150)
  await page.mouse.up()
  await page.waitForTimeout(100)
}

test('拖第三個活動到第一位 → 新第一筆的時間仍從當天原始起點 09:00 開始', async ({ page }) => {
  await mockApi(page, {
    generate: async (route) => route.fulfill({ json: DRAG_REORDER_PLAN }),
  })
  await page.goto('/')

  // 觸發生成
  await page.getByPlaceholder('台中兩天一夜美食散步之旅').fill('拖曳測試')
  await page.getByRole('button', { name: /出發/ }).click()

  // 等三筆活動都 render（用「時間 + 地點」當顯眼錨點）
  await expect(page.getByText('中正紀念堂').first()).toBeVisible({ timeout: 10_000 })
  await expect(page.getByText('永康街').first()).toBeVisible()
  await expect(page.getByText('象山步道').first()).toBeVisible()

  // 初始狀態：第一張卡片是中正紀念堂、時間以 09:00 開頭
  // 用 hasText 過濾把 timeline button 圈出來；外層 button 包含 location text
  const initialFirstCard = page.locator('button').filter({ hasText: /09:00/ }).first()
  await expect(initialFirstCard).toContainText('中正紀念堂')

  // 拖象山步道（第三筆）的 grip handle 到中正紀念堂（第一筆）上方
  const sourceHandle = page
    .locator('button')
    .filter({ hasText: '象山步道' })
    .first()
    .getByRole('button', { name: '拖曳排序' })
  const targetCard = page.locator('button').filter({ hasText: '中正紀念堂' }).first()

  await dndKitDrag(page, sourceHandle, targetCard)

  // 重排後等 React 重新 render（用「象山步道在第一筆」當訊號）
  // dnd-kit 完成排序後，外層卡片的 DOM 順序會反映新順序
  await expect(async () => {
    const firstCardAfter = page.locator('button').filter({ hasText: /\d{2}:\d{2}/ }).first()
    await expect(firstCardAfter).toContainText('象山步道')
  }).toPass({ timeout: 5_000 })

  // ★ 核心斷言：新的第一筆（象山步道）必須以 09:00 開頭，
  // 而不是它原本的 11:45。這就是 commit 5fedf52 修復的 bug。
  const firstCardAfter = page.locator('button').filter({ hasText: '象山步道' }).first()
  await expect(firstCardAfter).toContainText(/09:00\s*-/)
  // 防呆：不應該還掛著舊的 11:45
  await expect(firstCardAfter).not.toContainText('11:45')
})
