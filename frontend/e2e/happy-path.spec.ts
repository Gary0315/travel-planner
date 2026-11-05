import { test, expect } from '@playwright/test'
import { mockApi, MOCK_SHARE_ID, MOCK_TRIP_PLAN, MOCK_USER } from './fixtures/mockApi'

/**
 * 主流程冒煙測試：生成 → 儲存 → 打開分享連結。
 *
 * 涵蓋最容易壞掉的 wiring：
 *   - /generate 回應正確 render 到 timeline
 *   - 儲存後 ShareModal 出現、shareUrl 含後端回的 shareId
 *   - 開啟 /view/{shareId} 不會 404、能看到 SHARED TRIP header
 *   - 因為 save 回了 deleteToken，view page 能看到「刪除分享」按鈕（擁有者視角）
 *
 * 註：本檔前兩個測試走「未登入」(匿名) 流程，按鈕文案是「💌 分享」/「✓ 已分享」。
 * 登入後變成「💌 儲存並分享」/「✓ 已收藏」，由本檔最後一個 test 覆蓋。
 */
test('generate → save → open share link → see delete button', async ({ page }) => {
  await mockApi(page)
  await page.goto('/')

  // 初始畫面有「出發」按鈕（=表單已 render）
  await expect(page.getByRole('button', { name: /出發/ })).toBeVisible()

  // 填入需求並送出
  const input = page.getByPlaceholder('台中兩天一夜美食散步之旅')
  await input.click()
  await input.fill('Playwright 自動化的台北一日散步')
  await page.getByRole('button', { name: /出發/ }).click()

  // 生成完成 → mock title 出現
  await expect(page.getByText(MOCK_TRIP_PLAN.title)).toBeVisible({ timeout: 10_000 })

  // 第一個 activity 場景名稱應該也在（中正紀念堂同時出現在 timeline 卡片與右側 Polaroid，取首個即可）
  await expect(page.getByRole('button', { name: /中正紀念堂/ }).first()).toBeVisible()

  // 未登入 + 真實行程 + 未分享 → 應該看到登入 CTA
  await expect(page.getByRole('button', { name: /登入即可儲存到我的行程/ })).toBeVisible()

  // 「💌 分享」按鈕現在應該不再 disabled（isMock=false），且不該出現「儲存並分享」
  const shareBtn = page.getByRole('button', { name: /^💌 分享$/ })
  await expect(shareBtn).toBeEnabled()
  await expect(page.getByRole('button', { name: /儲存並分享/ })).toHaveCount(0)
  await shareBtn.click()

  // ShareModal 出現 + URL 含 mock shareId
  await expect(page.getByText('行程已收藏')).toBeVisible()
  await expect(page.getByText(new RegExp(MOCK_SHARE_ID))).toBeVisible()

  // 直接導去分享連結（避免處理 target="_blank" 開新分頁的麻煩）
  await page.goto(`/view/${MOCK_SHARE_ID}`)

  // ViewPage 載入後 trip header chip 出現
  await expect(page.getByText('SHARED TRIP ✦')).toBeVisible()
  await expect(page.getByText(MOCK_TRIP_PLAN.title)).toBeVisible()

  // 因為 App 那邊 save 時把 deleteToken 寫進 localStorage，
  // ViewPage 應該偵測到並顯示「刪除分享」按鈕（擁有者視角的驗證）
  await expect(page.getByRole('button', { name: /刪除分享/ })).toBeVisible()
})

/**
 * 匿名：儲存後關掉 modal，按鈕應該維持「✓ 已分享」而不是還原成「💌 分享」；
 * 再點一次「✓ 已分享」應該重開分享 modal（不會再發一次 /save）。
 */
test('anonymous: save → close modal → 已分享 stays → click reopens same share modal', async ({ page }) => {
  // 計算 /api/itinerary/save 被打幾次：點「已分享」重開 modal 時不應該再 +1
  let saveCallCount = 0
  await mockApi(page, {
    save: async (route) => {
      saveCallCount++
      await route.fulfill({ json: { shareId: MOCK_SHARE_ID, deleteToken: 'reuse-token' } })
    },
  })
  await page.goto('/')

  // Generate + share
  const input = page.getByPlaceholder('台中兩天一夜美食散步之旅')
  await input.fill('Playwright 自動化的台北一日散步')
  await page.getByRole('button', { name: /出發/ }).click()
  await expect(page.getByText(MOCK_TRIP_PLAN.title)).toBeVisible({ timeout: 10_000 })

  await page.getByRole('button', { name: /^💌 分享$/ }).click()
  await expect(page.getByText('行程已收藏')).toBeVisible()
  expect(saveCallCount).toBe(1)

  // 關掉 modal（用右上 X）
  await page.getByRole('button', { name: '關閉' }).click()
  await expect(page.getByText('行程已收藏')).not.toBeVisible()

  // 按鈕應該維持「✓ 已分享」(mint)；「💌 分享」(ink) 不該再出現；CTA 也應該消失（saved=true）
  await expect(page.getByRole('button', { name: /^✓ 已分享$/ })).toBeVisible()
  await expect(page.getByRole('button', { name: /^💌 分享$/ })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /登入即可儲存到我的行程/ })).toHaveCount(0)

  // 點「已分享」應該重開同一個 modal（URL 一樣），但不應該再發 /save
  await page.getByRole('button', { name: /^✓ 已分享$/ }).click()
  await expect(page.getByText('行程已收藏')).toBeVisible()
  await expect(page.getByText(new RegExp(MOCK_SHARE_ID))).toBeVisible()
  expect(saveCallCount).toBe(1)
})

/**
 * 登入：按鈕應該是「💌 儲存並分享」/「✓ 已收藏」，不該看到 CTA。
 * 驗證 isLoggedIn 切換成功且 backend 行為一致（同一支 /save 端點）。
 */
test('logged-in: button reads 儲存並分享, no login CTA, save shows 已收藏', async ({ page }) => {
  await mockApi(page, { user: MOCK_USER })
  await page.goto('/')

  const input = page.getByPlaceholder('台中兩天一夜美食散步之旅')
  await input.fill('Playwright 登入流程測試')
  await page.getByRole('button', { name: /出發/ }).click()
  await expect(page.getByText(MOCK_TRIP_PLAN.title)).toBeVisible({ timeout: 10_000 })

  // 登入後不該看到 CTA
  await expect(page.getByRole('button', { name: /登入即可儲存到我的行程/ })).toHaveCount(0)

  // 按鈕文案是「儲存並分享」，匿名版「💌 分享」不該存在
  const saveBtn = page.getByRole('button', { name: /^💌 儲存並分享$/ })
  await expect(saveBtn).toBeEnabled()
  await expect(page.getByRole('button', { name: /^💌 分享$/ })).toHaveCount(0)
  await saveBtn.click()

  // 成功 → ShareModal + 按鈕變成「✓ 已收藏」（不是「已分享」）
  await expect(page.getByText('行程已收藏')).toBeVisible()
  await expect(page.getByText(new RegExp(MOCK_SHARE_ID))).toBeVisible()
  await page.getByRole('button', { name: '關閉' }).click()
  await expect(page.getByRole('button', { name: /^✓ 已收藏$/ })).toBeVisible()
  await expect(page.getByRole('button', { name: /^✓ 已分享$/ })).toHaveCount(0)
})
