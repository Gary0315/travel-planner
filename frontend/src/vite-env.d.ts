/// <reference types="vite/client" />

interface ImportMetaEnv {
  /**
   * Google OAuth Client ID（與後端 GOOGLE_OAUTH_CLIENT_ID 同值）。
   * 未設定時前端關閉登入功能（hide 登入按鈕）。詳見 SPEC §19.9。
   */
  readonly VITE_GOOGLE_OAUTH_CLIENT_ID?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
