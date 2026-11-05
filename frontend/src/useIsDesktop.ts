import { useEffect, useState } from 'react'

// Tailwind 的 md breakpoint = 768px
const QUERY = '(min-width: 768px)'

// 用 JS 判斷裝置尺寸來決定是否「真正 mount」桌機 / 手機版面。
// 避免 Tailwind `hidden md:block` 兩邊都 mount 導致 useEffect / fetch 跑兩次。
export function useIsDesktop(): boolean {
  const [isDesktop, setIsDesktop] = useState<boolean>(() =>
    typeof window !== 'undefined' ? window.matchMedia(QUERY).matches : false,
  )

  useEffect(() => {
    const mq = window.matchMedia(QUERY)
    const handler = (e: MediaQueryListEvent) => setIsDesktop(e.matches)
    mq.addEventListener('change', handler)
    return () => mq.removeEventListener('change', handler)
  }, [])

  return isDesktop
}
