import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter, Routes, Route } from 'react-router-dom'
import { GoogleOAuthProvider } from '@react-oauth/google'
import './index.css'
import App from './App.tsx'
import ViewPage from './ViewPage.tsx'
import MyTripsPage from './MyTripsPage.tsx'
import { AuthProvider } from './auth/AuthContext'
import ErrorBoundary from './components/ErrorBoundary'

const googleClientId = import.meta.env.VITE_GOOGLE_OAUTH_CLIENT_ID ?? ''
const authDisabled = !googleClientId

// 沒設 VITE_GOOGLE_OAUTH_CLIENT_ID 時跳過 GoogleOAuthProvider（後者要求 clientId 非空），
// AuthProvider 拿到 disabled=true 後不會打 /api/auth/me，登入按鈕也會 hide。
const routes = (
  <AuthProvider disabled={authDisabled}>
    <BrowserRouter>
      {/* 最外層防線：App 內部的結果區已有自己的 boundary，這顆接的是它涵蓋不到的地方
          （首頁的輸入區與 header、以及 /view、/trips 兩頁）。少了它，那些位置一旦
          render 丟例外還是會整片白。 */}
      <ErrorBoundary scope="page">
        <Routes>
          <Route path="/" element={<App />} />
          <Route path="/view/:id" element={<ViewPage />} />
          <Route path="/trips" element={<MyTripsPage />} />
        </Routes>
      </ErrorBoundary>
    </BrowserRouter>
  </AuthProvider>
)

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    {authDisabled ? routes : <GoogleOAuthProvider clientId={googleClientId}>{routes}</GoogleOAuthProvider>}
  </StrictMode>,
)
