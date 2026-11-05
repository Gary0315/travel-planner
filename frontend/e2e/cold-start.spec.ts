import { test, expect } from '@playwright/test'
import { mockApi, MOCK_TRIP_PLAN } from './fixtures/mockApi'

/**
 * 冷啟動喚醒提示（PLAN S2）與 Cloudflare 524 防線。
 *
 * 驗證 useServerWakeup 的閘門 wiring：
 *   - 熱機（health 立即回 200）→ 橫幅不出現、出發按鈕正常可按
 *   - 冷啟動（health 吊住不回）→ 「伺服器喚醒中」橫幅出現、按鈕鎖住顯示「喚醒中」；
 *     health 回應後橫幅消失、按鈕解鎖
 *   - 生成前閘門：頁面開著時後端睡掉 → 按出發會先喚醒，POST 不在醒來前送出
 *   - 閘道錯誤辨識：非 JSON 的 5xx（CF 524 錯誤頁）與後端 JSON 的 502（AI 失敗）
 *     必須顯示不同訊息——前者是「還沒醒」，後者是 S3 的重試文案，不可互相蓋掉
 *
 * 真實環境驗收（Render 閒置 15 分鐘後開頁實測）需在 prod 手動做，不在 E2E 範圍；
 * 95 秒逾時（GENERATE_TIMEOUT_MS）也不測——E2E 等不起，只能靠 prod 觀察。
 */

test('熱機：喚醒橫幅不出現，出發按鈕可按', async ({ page }) => {
  await mockApi(page)
  await page.goto('/')

  await expect(page.getByRole('button', { name: /出發/ })).toBeEnabled()
  await expect(page.getByText(/伺服器喚醒中/)).toHaveCount(0)
})

test('冷啟動：橫幅出現且按鈕鎖住；health 回應後解鎖', async ({ page }) => {
  // health 吊住直到測試呼叫 wake()——模擬 Render 冷啟動時請求被掛著的行為。
  // StrictMode 會發兩次探測，兩個 handler await 同一個 promise，wake() 一次全放行。
  let wake!: () => void
  const wokenUp = new Promise<void>((resolve) => { wake = resolve })
  await mockApi(page, {
    health: async (route) => {
      await wokenUp
      await route.fulfill({ json: { status: 'ok' } })
    },
  })
  await page.goto('/')

  // 寬限期（1.5s）過後：橫幅出現、按鈕變「喚醒中」且 disabled
  await expect(page.getByText(/伺服器喚醒中/)).toBeVisible({ timeout: 10_000 })
  await expect(page.getByRole('button', { name: /喚醒中/ })).toBeDisabled()

  // 模擬伺服器醒來
  wake()

  // 橫幅消失、按鈕恢復「出發」且可按
  await expect(page.getByText(/伺服器喚醒中/)).toHaveCount(0, { timeout: 10_000 })
  await expect(page.getByRole('button', { name: /出發/ })).toBeEnabled()
})

/**
 * 生成前閘門（Cloudflare 524 的正解）。
 *
 * 開頁時後端是醒的，但頁面擺著超過 Render 的 15 分鐘閒置門檻後它睡了——
 * 掛載時那輪 wakeup 的 'awake' 早就過期。這時直接送 POST，那一個請求要同時扛
 * 「冷啟動 30–60s + AI 生成 20–60s」，會撞爆 Cloudflare 免費方案不可調的
 * 100 秒 proxy 上限拿到 524（回的是 CF 的 HTML 錯誤頁，前端連後端訊息都拿不到）。
 * 正解是先用 health 把後端叫醒（單次探測 10 秒自己 abort 重連，永遠碰不到那 100 秒），
 * 醒了才送真正的請求。本案守的就是「閘門真的擋著，不是裝飾」。
 */
test('生成前閘門：後端睡掉時先喚醒，POST 不會在醒來前送出', async ({ page }) => {
  let backendAsleep = false
  let generateCalls = 0
  let wake!: () => void
  const wokenUp = new Promise<void>((resolve) => { wake = resolve })

  await mockApi(page, {
    health: async (route) => {
      if (backendAsleep) await wokenUp
      await route.fulfill({ json: { status: 'ok' } })
    },
    generate: async (route) => {
      generateCalls++
      await route.fulfill({ json: MOCK_TRIP_PLAN })
    },
  })
  await page.goto('/')

  // 開頁當下後端是醒的：閘門放行、橫幅不出現
  await expect(page.getByRole('button', { name: /出發/ })).toBeEnabled()
  await expect(page.getByText(/伺服器喚醒中/)).toHaveCount(0)

  // 頁面開著的這段期間，後端閒置休眠了
  backendAsleep = true

  await page.getByPlaceholder('台中兩天一夜美食散步之旅').fill('喚醒閘門測試')
  await page.getByRole('button', { name: /出發/ }).click()

  // 寬限期過後橫幅重新出現 = ensureAwake 這輪確實跑起來了（不是沿用開頁那輪的結論）
  await expect(page.getByText(/伺服器喚醒中/)).toBeVisible({ timeout: 10_000 })

  // 關鍵斷言：POST 還沒送出。閘門若沒接上，這裡會是 1。
  expect(generateCalls).toBe(0)

  wake() // 伺服器醒來

  // 醒了才送出，且結果正常 render
  await expect(page.getByText(MOCK_TRIP_PLAN.title)).toBeVisible({ timeout: 15_000 })
  expect(generateCalls).toBe(1)
})

/**
 * 閘道錯誤：CF 逾時回的是 524 + 一頁 HTML，不是後端的 JSON。
 * 沒特別處理的話前端只會顯示一句無意義的失敗，使用者不知道該不該再試。
 */
test('閘道逾時：524 + HTML 錯誤頁 → 顯示「伺服器剛醒來還沒準備好」', async ({ page }) => {
  await mockApi(page, {
    generate: async (route) => {
      await route.fulfill({
        status: 524,
        contentType: 'text/html',
        body: '<html><body>A timeout occurred (Cloudflare)</body></html>',
      })
    },
  })
  await page.goto('/')

  await page.getByPlaceholder('台中兩天一夜美食散步之旅').fill('CF 524 錯誤頁測試')
  await page.getByRole('button', { name: /出發/ }).click()

  await expect(page.getByText(/伺服器剛醒來還沒準備好/)).toBeVisible({ timeout: 10_000 })
})

/**
 * 反向案：502 也是後端 AI 失敗的碼（S3）。閘道分支必須先確認「解析不出 JSON」
 * 才能套用，否則會把 S3 的錯誤訊息整片蓋掉——那是親友第一印象的生死線。
 */
test('AI 失敗的 502 帶後端 JSON → 顯示後端訊息，不被閘道文案蓋掉', async ({ page }) => {
  await mockApi(page, {
    generate: async (route) => {
      await route.fulfill({
        status: 502,
        json: { message: '行程生成失敗，請再試一次', errorCode: 'AI_ERROR' },
      })
    },
  })
  await page.goto('/')

  await page.getByPlaceholder('台中兩天一夜美食散步之旅').fill('AI 失敗文案測試')
  await page.getByRole('button', { name: /出發/ }).click()

  await expect(page.getByText(/行程生成失敗，請再試一次/)).toBeVisible({ timeout: 10_000 })
  await expect(page.getByText(/伺服器剛醒來還沒準備好/)).toHaveCount(0)

  // AI 錯誤不觸發倒數、按鈕不鎖（S3 拍板的極簡重試設計：直接再按「出發」）
  await expect(page.getByRole('button', { name: /出發/ })).toBeEnabled()
})
