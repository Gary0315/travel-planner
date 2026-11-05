import type { CSSProperties, MouseEvent } from 'react'

type Variant = 'default' | 'compact' | 'inline'

type Props = {
  name?: string | null
  href?: string | null
  variant?: Variant
}

// 合規 Google Places ToS：user-contributed 圖片必須清楚標示原作者。
// 平常只露一顆相機 chip，hover / focus 時用 max-width 過渡展開作者名，避免擋住圖片主體。
export default function PhotoCredit({ name, href, variant = 'default' }: Props) {
  if (!name) return null

  const isInline = variant === 'inline'
  const isCompact = variant === 'compact'

  // 樣式按 variant 分：default / compact 都絕對定位浮在容器右下；inline 走文字流。
  const containerClass = isInline
    ? 'group inline-flex items-center'
    : 'group absolute bottom-1.5 right-1.5 z-10 inline-flex items-center'

  const sizeClass = isCompact
    ? 'h-[18px] text-[10px]'
    : isInline
      ? 'h-[18px] text-[10px]'
      : 'h-[22px] text-[11px]'

  // chip：max-width 收合成 icon 大小（≈ 22px），hover / focus-within 展開到 200px
  const chipClass = [
    'flex items-center gap-1 overflow-hidden whitespace-nowrap rounded-full',
    'bg-black/60 px-1.5 font-semibold text-white no-underline',
    'backdrop-blur-[4px]',
    'transition-[max-width] duration-[250ms] ease-out',
    'max-w-[22px] group-hover:max-w-[200px] group-focus-within:max-w-[200px]',
    sizeClass,
  ].join(' ')

  const iconStyle: CSSProperties = { lineHeight: 1 }

  const inner = (
    <span className={chipClass} title={name}>
      <span aria-hidden style={iconStyle}>📷</span>
      <span className="truncate">{name}</span>
    </span>
  )

  // 點 chip 時不要把點擊事件冒泡到外層（避免觸發外層圖片放大 / 卡片跳轉）
  const stop = (e: MouseEvent) => e.stopPropagation()

  if (href) {
    return (
      <a
        href={href}
        target="_blank"
        rel="noopener noreferrer"
        onClick={stop}
        className={containerClass}
        aria-label={`Photo by ${name}`}
      >
        {inner}
      </a>
    )
  }

  return (
    <span className={containerClass} aria-label={`Photo by ${name}`}>
      {inner}
    </span>
  )
}
