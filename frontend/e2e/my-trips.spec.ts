import { test, expect } from '@playwright/test'
import { mockApi, MOCK_SHARE_ID, MOCK_TRIP_PLAN, MOCK_USER } from './fixtures/mockApi'

/**
 * /trips 我的行程列表頁。
 *
 * 資料來源：後端 GET /api/users/me/trips（綁 itinerary.user_id）。未登入回 401，
 * 前端 MyTripsPage 對應顯示「請先登入」空狀態。
 *
 * 註：原本 localStorage 過渡層已淘汰，所有 trip metadata 都來自 DB。
 */

test.describe('/trips — 未登入', () => {
  test('shows 請先登入 empty state when not signed in', async ({ page }) => {
    await mockApi(page) // 不帶 user → 預設未登入
    await page.goto('/trips')

    // Eyebrow + H1 仍然在（page chrome 不受 auth 影響）
    await expect(page.getByText('MY TRIPS ✦')).toBeVisible()
    await expect(page.getByRole('heading', { level: 1, name: '我的行程' })).toBeVisible()

    // 「請先登入」空狀態
    await expect(page.getByText('請先登入才能查看你的行程')).toBeVisible()
    await expect(page.getByRole('link', { name: /回首頁登入/ })).toBeVisible()

    // filter pills 不顯示（沒資料）
    await expect(page.getByRole('button', { name: /^全部/ })).toHaveCount(0)
  })
})

test.describe('/trips — 已登入', () => {
  test('empty state when no trips yet', async ({ page }) => {
    await mockApi(page, { user: MOCK_USER }) // 已登入，預設回 []
    await page.goto('/trips')

    await expect(page.getByText('還沒有任何行程')).toBeVisible()
    await expect(page.getByRole('link', { name: /規劃新行程/ }).first()).toBeVisible()

    // 沒資料時 filter pills 仍然不顯示
    await expect(page.getByRole('button', { name: /^全部/ })).toHaveCount(0)
  })

  test('renders cards from /api/users/me/trips response', async ({ page }) => {
    const cards = [
      {
        shareId: 'share-A',
        title: '宜蘭兩天一夜親子放鬆',
        summary: '溫泉、童玩節、礁溪夜市',
        days: 2,
        places: 8,
        coverEmoji: '♨️',
        savedAt: '2026-05-20T10:00:00',
      },
      {
        shareId: 'share-B',
        title: '台中兩天一夜美食散步',
        summary: '草悟道、審計新村',
        days: 2,
        places: 12,
        coverEmoji: '🍜',
        savedAt: '2026-05-15T10:00:00',
      },
    ]
    await mockApi(page, {
      user: MOCK_USER,
      myTrips: (route) => route.fulfill({ json: cards }),
    })
    await page.goto('/trips')

    // 兩張卡都看得到
    await expect(page.getByText('宜蘭兩天一夜親子放鬆')).toBeVisible()
    await expect(page.getByText('台中兩天一夜美食散步')).toBeVisible()

    // filter pill「全部 2」顯示
    await expect(page.getByRole('button', { name: /^全部\s*2$/ })).toBeVisible()

    // 卡片連到 /view/{shareId}
    const cardA = page.locator('a', { hasText: '宜蘭兩天一夜親子放鬆' })
    await expect(cardA).toHaveAttribute('href', '/view/share-A')
  })

  test('shows error state on 5xx', async ({ page }) => {
    await mockApi(page, {
      user: MOCK_USER,
      myTrips: (route) => route.fulfill({ status: 500, body: '' }),
    })
    await page.goto('/trips')

    await expect(page.getByText('無法載入行程')).toBeVisible()
  })

  test('save trip → /trips 看到剛存的行程', async ({ page }) => {
    // 第一次 GET /api/users/me/trips 回空；按下儲存後再導去 /trips 時 mock 回有資料。
    // 用 let trips 模擬後端 DB 狀態：save 一次後 listMine 才會看到新筆。
    let trips: object[] = []
    await mockApi(page, {
      user: MOCK_USER,
      save: async (route) => {
        trips = [
          {
            shareId: MOCK_SHARE_ID,
            title: MOCK_TRIP_PLAN.title,
            summary: MOCK_TRIP_PLAN.summary,
            days: 1,
            places: 2,
            coverEmoji: '🏛️',
            savedAt: '2026-05-23T10:00:00',
          },
        ]
        await route.fulfill({ json: { shareId: MOCK_SHARE_ID, deleteToken: 'tk' } })
      },
      myTrips: (route) => route.fulfill({ json: trips }),
    })
    await page.goto('/')

    // 生成 + 儲存
    await page.getByPlaceholder('台中兩天一夜美食散步之旅').fill('test')
    await page.getByRole('button', { name: /出發/ }).click()
    await expect(page.getByText(MOCK_TRIP_PLAN.title)).toBeVisible({ timeout: 10_000 })
    await page.getByRole('button', { name: /儲存並分享/ }).click()
    await expect(page.getByText('行程已收藏')).toBeVisible()

    // 進「我的行程」應該看到剛存的這趟（從後端撈出）
    await page.goto('/trips')
    await expect(page.getByText(MOCK_TRIP_PLAN.title)).toBeVisible()
    const card = page.locator('a', { hasText: MOCK_TRIP_PLAN.title })
    await expect(card).toHaveAttribute('href', `/view/${MOCK_SHARE_ID}`)
  })
})
