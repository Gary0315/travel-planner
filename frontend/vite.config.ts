import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react(), tailwindcss()],
  // 壓縮時保留函式／元件名稱。正式站的 stack trace 原本會壓成 `at t`、`at a`，
  // 崩潰回報收到也看不出是哪個元件炸的；keepNames 讓 ErrorBoundary 送回來的
  // component 欄位是 `DetailPanel` 這種讀得懂的名字。
  //
  // 為什麼不用 source map 解這件事：repo 是 private，而 source map 檔內含完整原始碼，
  // 部署出去等於把私有原始碼公開在網站上。keepNames 只多露出名稱——而前端 bundle
  // 本來就整包送到每個瀏覽器，minify 從來不是安全機制，所以這裡沒有新增暴露面。
  // 代價是 bundle 大幾 KB。
  esbuild: { keepNames: true },
  server: {
  allowedHosts: ['.ngrok-free.app'],
    proxy: {
      // 只要是 /api 開頭的請求，一律轉發到後端 8080
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      }
    }
  }
})
