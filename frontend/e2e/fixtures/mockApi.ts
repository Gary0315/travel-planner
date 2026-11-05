import type { Page } from '@playwright/test'

/**
 * 攔下所有後端 API，回固定 mock 資料。
 * 讓 E2E 不依賴 Spring Boot、不打真實 Gemini / Google Places API（會花錢且不穩）。
 *
 * 使用方式：
 *   // 匿名（未登入）
 *   await mockApi(page)
 *
 *   // 模擬已登入
 *   await mockApi(page, { user: MOCK_USER })
 *
 *   // 覆寫單一端點
 *   await mockApi(page, {
 *     user: MOCK_USER,
 *     myTrips: (route) => route.fulfill({ json: [SAMPLE_CARD] }),
 *   })
 */

export const MOCK_SHARE_ID = 'test-share-uuid-1234'
export const MOCK_DELETE_TOKEN = 'test-delete-token-5678'

export const MOCK_USER = {
  id: 1,
  email: 'gary@example.com',
  name: 'Gary',
  pictureUrl: null as string | null,
}

export const MOCK_TRIP_PLAN = {
  title: 'E2E 測試行程：台北一日散步',
  summary: 'Playwright 在跑的假行程，從中正紀念堂走到淡水。',
  days: [
    {
      day: 1,
      theme: '城南漫遊',
      activities: [
        {
          time: '09:00 - 10:30',
          location: '中正紀念堂',
          description: '拍照與廣場散步。',
          transport: '步行 8 分鐘',
          category: '景點',
          emoji: '🏛️',
        },
        {
          time: '10:38 - 12:00',
          location: '永康街',
          description: '芒果冰與小吃巡禮。',
          transport: '步行 10 分鐘',
          category: '美食',
          emoji: '🍧',
        },
      ],
      hotels: null,
    },
  ],
}

type RouteHandler = Parameters<Page['route']>[1]

interface MockOverrides {
  health?: RouteHandler
  generate?: RouteHandler
  save?: RouteHandler
  getItinerary?: RouteHandler
  deleteItinerary?: RouteHandler
  placesPhoto?: RouteHandler
  authMe?: RouteHandler
  myTrips?: RouteHandler
}

interface MockOptions extends MockOverrides {
  /**
   * 模擬登入：傳這個 user，/api/auth/me 會回 200 + 該 user，
   * 其他需要 auth 的端點（例如 /api/users/me/trips）也以此為基準回 200。
   * 不傳時所有 auth-required 端點回 401（預設匿名）。
   */
  user?: typeof MOCK_USER
}

export async function mockApi(page: Page, options: MockOptions = {}) {
  const { user, ...overrides } = options

  // GET /api/health — S2 冷啟動探測（useServerWakeup）。預設立即回「已醒」，
  // 讓其他 spec 不被喚醒閘門鎖住按鈕；冷啟動情境由 cold-start.spec.ts 用 override 模擬。
  await page.route('**/api/health', overrides.health ?? (async (route) => {
    await route.fulfill({ json: { status: 'ok' } })
  }))

  // GET /api/auth/me — frontend apiFetch 會在首次非 safe method 之前 ping 這支以拿 XSRF-TOKEN cookie。
  // user 有給 → 回 200 + 該 user；否則回 401（未登入）。所有回應都帶 fake XSRF-TOKEN cookie
  // 模擬 Spring Security 的 CookieCsrfTokenRepository。
  await page.route('**/api/auth/me', overrides.authMe ?? (async (route) => {
    const headers = {
      'set-cookie': 'XSRF-TOKEN=e2e-fake-csrf-token; Path=/; SameSite=Lax',
      'content-type': 'application/json',
    }
    if (user) {
      await route.fulfill({ status: 200, headers, body: JSON.stringify(user) })
    } else {
      await route.fulfill({ status: 401, headers, body: JSON.stringify({ message: 'Not authenticated' }) })
    }
  }))

  // POST /api/itinerary/generate
  await page.route('**/api/itinerary/generate', overrides.generate ?? (async (route) => {
    await route.fulfill({ json: MOCK_TRIP_PLAN })
  }))

  // POST /api/itinerary/save
  await page.route('**/api/itinerary/save', overrides.save ?? (async (route) => {
    await route.fulfill({ json: { shareId: MOCK_SHARE_ID, deleteToken: MOCK_DELETE_TOKEN } })
  }))

  // GET / DELETE /api/itinerary/{shareId}
  await page.route(`**/api/itinerary/${MOCK_SHARE_ID}`, overrides.getItinerary ?? (async (route) => {
    if (route.request().method() === 'DELETE') {
      if (overrides.deleteItinerary) {
        return overrides.deleteItinerary(route, route.request())
      }
      return route.fulfill({ status: 204, body: '' })
    }
    await route.fulfill({ json: MOCK_TRIP_PLAN })
  }))

  // GET /api/places/photo（metadata）— 直接回 fallback 圖路徑，省一次 /binary
  await page.route('**/api/places/photo**', overrides.placesPhoto ?? (async (route) => {
    await route.fulfill({
      json: {
        imageUrl: '/mock-images/picture.svg',
        placeId: null,
        attribution: null,
        attributionUrl: null,
      },
    })
  }))

  // GET /api/users/me/trips — 「我的行程」列表
  // user 有給 → 預設回空陣列（測試需要資料時可 override）；否則 401
  await page.route('**/api/users/me/trips', overrides.myTrips ?? (async (route) => {
    if (!user) {
      await route.fulfill({
        status: 401,
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ message: 'Not authenticated' }),
      })
      return
    }
    await route.fulfill({ json: [] })
  }))
}
