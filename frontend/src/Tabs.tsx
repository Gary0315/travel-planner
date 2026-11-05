export type TabKey = 'timeline' | 'checklist'

const tabs: { key: TabKey; label: string }[] = [
  { key: 'timeline', label: '🗺️ 行程' },
  { key: 'checklist', label: '✓ 行前清單' },
]

export function TabNav({
  active,
  onChange,
}: {
  active: TabKey
  onChange: (key: TabKey) => void
}) {
  return (
    <div
      role="tablist"
      className="mb-3.5 flex w-fit gap-1 rounded-full border-2 border-ink bg-paper p-1 shadow-[2px_2px_0_var(--pop-shadow-color)]"
    >
      {tabs.map(({ key, label }) => {
        const isActive = key === active
        return (
          <button
            key={key}
            role="tab"
            aria-selected={isActive}
            onClick={() => onChange(key)}
            className={`rounded-full px-4 py-1.5 text-[13px] font-bold transition ${
              isActive ? 'bg-ink text-paper' : 'text-ink hover:bg-cream'
            }`}
          >
            {label}
          </button>
        )
      })}
    </div>
  )
}
