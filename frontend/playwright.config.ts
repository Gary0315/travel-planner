import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright E2E config for TravelPlanner frontend.
 *
 * - 預設只跑 Chromium，加速本機開發 cycle；CI 環境可擴充到 firefox / webkit
 * - 自動拉起 vite dev server（已開的話直接複用，本機快）
 * - 所有 spec 跑在 mock 環境（透過 page.route() 攔下後端呼叫），不依賴 Spring Boot
 *   也不打真實 Gemini / Google Places API
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 2 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: process.env.CI ? 'github' : 'list',

  use: {
    baseURL: 'http://localhost:5173',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
  },

  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],

  webServer: {
    command: 'npm run dev',
    url: 'http://localhost:5173',
    reuseExistingServer: !process.env.CI,
    timeout: 60_000,
    stdout: 'ignore',
    stderr: 'pipe',
    // 啟用 GoogleOAuthProvider + AuthProvider — 讓需要驗證的端點（例如 /api/users/me/trips）
    // 可以透過 mockApi 模擬登入態。fake client id 不會去打真 Google；GIS script 是否成功載入
    // 不影響 React render（我們不會真的點 Google 登入按鈕，只 mock /api/auth/me）。
    env: { VITE_GOOGLE_OAUTH_CLIENT_ID: 'e2e-fake-client-id' },
  },
})
