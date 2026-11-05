// My Trips — list of saved trips in B 方案 vocabulary.
// Trip card: postcard photo, title, dates, member avatars, day count, wallet preview.

const { useState: useStateMT, useMemo: useMemoMT } = React;

const MT = {
  cream: 'oklch(0.97 0.025 75)',
  paper: 'oklch(0.985 0.015 75)',
  ink: 'oklch(0.28 0.04 50)',
  muted: 'oklch(0.55 0.03 50)',
  coral: 'oklch(0.72 0.16 35)',
  coralDeep: 'oklch(0.6 0.18 35)',
  mint: 'oklch(0.78 0.11 165)',
  mintDeep: 'oklch(0.55 0.12 165)',
  sky: 'oklch(0.82 0.08 230)',
  skyDeep: 'oklch(0.5 0.12 230)',
  butter: 'oklch(0.9 0.1 90)',
  butterDeep: 'oklch(0.55 0.14 80)',
  pink: 'oklch(0.88 0.09 350)',
  border: 'oklch(0.9 0.02 75)',
};
const MT_FONT = '"Quicksand", "Nunito", ui-rounded, "Noto Sans TC", system-ui, sans-serif';
const MT_HAND = '"Caveat", "Indie Flower", cursive';

const TRIP_STATUS = {
  upcoming: { label: '即將出發', emoji: '✈️', bg: MT.coral, fg: 'white' },
  ongoing:  { label: '進行中',   emoji: '📍', bg: MT.mint,  fg: MT.ink },
  done:     { label: '已完成',   emoji: '✦',  bg: MT.butter, fg: MT.ink },
};

// Sample trips
const SAMPLE_TRIPS = [
  {
    id: 't1', status: 'upcoming',
    title: '宜蘭兩天一夜親子放鬆',
    summary: '溫泉、童玩節、礁溪夜市',
    dateLabel: '5/30 (六) — 5/31 (日)',
    days: 2, places: 8,
    coverKey: '宜蘭溫泉公園', coverCat: '景點', coverEmoji: '♨️',
    members: ['me', 'yian', 'jay'],
    wallet: { total: 0, count: 0 },
  },
  {
    id: 't2', status: 'upcoming',
    title: '日本京都賞櫻',
    summary: '哲學之道、清水寺、鴨川野餐',
    dateLabel: '6/12 (四) — 6/16 (一)',
    days: 5, places: 18,
    coverKey: '京都清水寺', coverCat: '景點', coverEmoji: '🌸',
    members: ['me', 'annie'],
    wallet: { total: 0, count: 0 },
  },
  {
    id: 't3', status: 'done',
    title: '台中兩天一夜美食散步之旅',
    summary: '草悟道、審計新村、第二市場',
    dateLabel: '5/10 (六) — 5/11 (日)',
    days: 2, places: 12,
    coverKey: '台中第二市場', coverCat: '美食', coverEmoji: '🍜',
    members: ['me', 'yian', 'jay', 'annie'],
    wallet: { total: 18220, count: 8 },
  },
  {
    id: 't4', status: 'done',
    title: '台南三天兩夜文青咖啡行',
    summary: '神農街、林百貨、奉茶',
    dateLabel: '4/18 (五) — 4/20 (日)',
    days: 3, places: 14,
    coverKey: '台南神農街', coverCat: '景點', coverEmoji: '☕',
    members: ['me', 'yian'],
    wallet: { total: 6840, count: 11 },
  },
  {
    id: 't5', status: 'upcoming',
    title: '花蓮三天兩夜山海療癒',
    summary: '太魯閣、七星潭、瑰穗法園',
    dateLabel: '7/4 (五) — 7/6 (日)',
    days: 3, places: 11,
    coverKey: '花蓮七星潭', coverCat: '景點', coverEmoji: '🏔️',
    members: ['me', 'jay'],
    wallet: { total: 0, count: 0 },
  },
];

const MEMBERS_MT = {
  me:    { name: '我',    initial: '我', color: MT.coral,  fg: 'white' },
  yian:  { name: '怡安',  initial: '怡', color: MT.mint,   fg: MT.ink },
  jay:   { name: '小傑',  initial: '傑', color: MT.sky,    fg: MT.ink },
  annie: { name: 'Annie', initial: 'A',  color: MT.butter, fg: MT.ink },
};

function nt(n) { return 'NT$' + Math.round(n).toLocaleString(); }

function MiniAvatar({ id, size = 22 }) {
  const m = MEMBERS_MT[id] || MEMBERS_MT.me;
  return <span style={{ width: size, height: size, borderRadius: '50%', background: m.color, color: m.fg, border: `2px solid ${MT.ink}`, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontSize: Math.round(size * 0.42), fontWeight: 800, flexShrink: 0 }}>{m.initial}</span>;
}

function MemberStack({ ids, size = 22, max = 4 }) {
  const show = ids.slice(0, max);
  const more = ids.length - max;
  return (
    <span style={{ display: 'inline-flex', alignItems: 'center' }}>
      {show.map((id, i) => (
        <span key={id} style={{ marginLeft: i === 0 ? 0 : -7, position: 'relative', zIndex: show.length - i }}>
          <MiniAvatar id={id} size={size} />
        </span>
      ))}
      {more > 0 && <span style={{ marginLeft: 5, fontSize: 11, color: MT.muted, fontWeight: 700 }}>+{more}</span>}
    </span>
  );
}

// ── Main ─────────────────────────────────────────────────────────────────
function MyTrips({ mobile = false }) {
  const [filter, setFilter] = useStateMT('all');

  const filters = [
    { id: 'all',      label: '全部',     count: SAMPLE_TRIPS.length },
    { id: 'upcoming', label: '即將出發', count: SAMPLE_TRIPS.filter(t => t.status === 'upcoming').length },
    { id: 'done',     label: '已完成',   count: SAMPLE_TRIPS.filter(t => t.status === 'done').length },
  ];

  const trips = useMemoMT(() => filter === 'all' ? SAMPLE_TRIPS : SAMPLE_TRIPS.filter(t => t.status === filter), [filter]);

  return (
    <div style={{ fontFamily: MT_FONT, background: MT.cream, color: MT.ink, minHeight: '100%', padding: mobile ? '14px 14px 90px' : '24px 32px 36px', position: 'relative', overflow: 'hidden' }}>
      {/* Decorative blobs */}
      <div style={{ position: 'absolute', top: -60, right: -40, width: 200, height: 200, borderRadius: '50%', background: MT.butter, opacity: 0.45, filter: 'blur(2px)', pointerEvents: 'none' }} />
      <div style={{ position: 'absolute', top: 280, left: -80, width: 240, height: 240, borderRadius: '50%', background: MT.sky, opacity: 0.3, filter: 'blur(2px)', pointerEvents: 'none' }} />

      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: mobile ? 14 : 20, position: 'relative', gap: 10 }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, minWidth: 0 }}>
          <a href="B Prototype.html" title="返回" style={{ width: mobile ? 34 : 38, height: mobile ? 34 : 38, borderRadius: 12, background: 'white', border: `2px solid ${MT.ink}`, boxShadow: `2px 2px 0 ${MT.ink}`, fontSize: 16, fontWeight: 800, color: MT.ink, cursor: 'pointer', fontFamily: 'inherit', flexShrink: 0, padding: 0, textDecoration: 'none', display: 'inline-flex', alignItems: 'center', justifyContent: 'center' }}>←</a>
          <div style={{ minWidth: 0 }}>
            <div style={{ fontSize: 10.5, fontWeight: 700, color: MT.coralDeep, letterSpacing: '0.1em' }}>MY TRIPS ✦</div>
            <div style={{ fontSize: mobile ? 19 : 24, fontWeight: 800, letterSpacing: '-0.02em', whiteSpace: 'nowrap' }}>我的行程</div>
          </div>
        </div>
        {!mobile && (
          <a href="B Prototype.html" style={{ padding: '10px 18px', fontSize: 14, fontWeight: 800, borderRadius: 999, border: `2px solid ${MT.ink}`, background: MT.coral, color: 'white', cursor: 'pointer', boxShadow: `3px 3px 0 ${MT.ink}`, fontFamily: 'inherit', display: 'inline-flex', alignItems: 'center', gap: 6, textDecoration: 'none' }}>
            ＋ 規劃新行程
          </a>
        )}
      </div>

      {/* Filter pills */}
      <div style={{ display: 'flex', gap: 6, marginBottom: 16, flexWrap: 'wrap' }}>
        {filters.map(f => {
          const on = filter === f.id;
          return (
            <button key={f.id} onClick={() => setFilter(f.id)} style={{ padding: '8px 14px', fontSize: 13, fontWeight: 800, borderRadius: 999, border: `2px solid ${MT.ink}`, background: on ? MT.ink : 'white', color: on ? 'white' : MT.ink, cursor: 'pointer', boxShadow: on ? `2px 2px 0 ${MT.ink}` : 'none', transform: on ? 'translate(-1px,-1px)' : 'none', fontFamily: 'inherit', display: 'inline-flex', alignItems: 'center', gap: 6 }}>
              {f.label}
              <span style={{ fontSize: 10.5, fontWeight: 800, background: on ? MT.coral : MT.cream, color: on ? 'white' : MT.muted, padding: '1px 7px', borderRadius: 999 }}>{f.count}</span>
            </button>
          );
        })}
      </div>

      {/* Trip grid */}
      {trips.length === 0 ? (
        <EmptyState mobile={mobile} />
      ) : (
        <div style={{ display: 'grid', gridTemplateColumns: mobile ? '1fr' : 'repeat(auto-fill, minmax(340px, 1fr))', gap: 16 }}>
          {trips.map(t => <TripCard key={t.id} trip={t} mobile={mobile} />)}
        </div>
      )}

      {/* Mobile FAB */}
      {mobile && (
        <a href="B Prototype.html" style={{ position: 'absolute', bottom: 18, right: 18, padding: '14px 20px', fontSize: 15, fontWeight: 800, borderRadius: 999, border: `2px solid ${MT.ink}`, background: MT.coral, color: 'white', cursor: 'pointer', boxShadow: `4px 4px 0 ${MT.ink}`, fontFamily: 'inherit', display: 'inline-flex', alignItems: 'center', gap: 6, zIndex: 20, textDecoration: 'none' }}>
          ＋ 規劃新行程
        </a>
      )}
    </div>
  );
}

function TripCard({ trip, mobile }) {
  const status = TRIP_STATUS[trip.status];
  const cover = window.TP && window.TP.photoFor(trip.coverKey, trip.coverCat, trip.coverEmoji);
  return (
    <a href="B Prototype.html" style={{ display: 'block', background: 'white', borderRadius: 22, border: `2px solid ${MT.ink}`, boxShadow: `3px 3px 0 ${MT.ink}`, overflow: 'hidden', textDecoration: 'none', color: MT.ink, transition: 'transform .15s, box-shadow .15s', cursor: 'pointer' }}
      onMouseEnter={(e) => { e.currentTarget.style.transform = 'translate(-2px,-2px)'; e.currentTarget.style.boxShadow = `5px 5px 0 ${MT.ink}`; }}
      onMouseLeave={(e) => { e.currentTarget.style.transform = 'none'; e.currentTarget.style.boxShadow = `3px 3px 0 ${MT.ink}`; }}
    >
      {/* Cover */}
      <div style={{ position: 'relative', aspectRatio: '16/10', background: MT.cream, borderBottom: `2px solid ${MT.ink}` }}>
        {cover && <img src={cover} alt={trip.title} style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }} />}
        {/* Status chip */}
        <span style={{ position: 'absolute', top: 10, left: 10, padding: '4px 10px', borderRadius: 999, background: status.bg, color: status.fg, border: `1.5px solid ${MT.ink}`, fontSize: 11, fontWeight: 800, boxShadow: `2px 2px 0 ${MT.ink}` }}>
          <span style={{ marginRight: 4 }}>{status.emoji}</span>{status.label}
        </span>
        {/* Days badge */}
        {trip.days && (
          <span style={{ position: 'absolute', top: 10, right: 10, padding: '4px 10px', borderRadius: 999, background: 'rgba(255,255,255,0.95)', color: MT.ink, border: `1.5px solid ${MT.ink}`, fontSize: 11, fontWeight: 800 }}>
            {trip.days} 天 · {trip.places} 個地點
          </span>
        )}
      </div>

      {/* Body */}
      <div style={{ padding: '14px 16px 16px' }}>
        <div style={{ fontSize: mobile ? 16 : 17, fontWeight: 800, letterSpacing: '-0.01em', lineHeight: 1.25, marginBottom: 3 }}>{trip.title}</div>
        <div style={{ fontSize: 12.5, color: MT.muted, lineHeight: 1.5, marginBottom: 8, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{trip.summary}</div>

        {/* Meta row */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 10, flexWrap: 'wrap' }}>
          <span style={{ fontSize: 11.5, fontWeight: 700, color: MT.coralDeep, background: 'oklch(0.96 0.05 35)', padding: '3px 9px', borderRadius: 999, border: `1px solid oklch(0.85 0.08 35)` }}>📅 {trip.dateLabel}</span>
        </div>

        {/* Footer: members + wallet chip */}
        <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, paddingTop: 10, borderTop: `1.5px dashed ${MT.border}` }}>
          <div style={{ display: 'flex', alignItems: 'center', gap: 7, minWidth: 0 }}>
            <MemberStack ids={trip.members} size={22} max={4} />
            <span style={{ fontSize: 11.5, color: MT.muted, fontWeight: 600 }}>{trip.members.length} 人同行</span>
          </div>
          {trip.wallet ? (
            trip.wallet.count > 0 ? (
              <span title="共同記帳" style={{ display: 'inline-flex', alignItems: 'center', gap: 4, fontSize: 11.5, fontWeight: 800, color: MT.ink, background: MT.butter, padding: '4px 10px', borderRadius: 999, border: `1.5px solid ${MT.ink}` }}>
                🧾 {nt(trip.wallet.total)} <span style={{ opacity: 0.6, fontWeight: 600 }}>· {trip.wallet.count}</span>
              </span>
            ) : (
              <span style={{ fontSize: 11, color: MT.muted, fontWeight: 600 }}>🧾 未開始記帳</span>
            )
          ) : null}
        </div>
      </div>
    </a>
  );
}

function EmptyState({ mobile }) {
  return (
    <div style={{ textAlign: 'center', padding: '60px 20px', background: 'white', borderRadius: 22, border: `2px dashed ${MT.border}` }}>
      <div style={{ fontSize: 48, marginBottom: 8 }}>🧳</div>
      <div style={{ fontSize: 17, fontWeight: 800, color: MT.ink, marginBottom: 4 }}>沒有符合條件的行程</div>
      <div style={{ fontSize: 13, color: MT.muted, marginBottom: 18 }}>切換上面的篩選，或從頭規劃一個新行程吧 ✨</div>
      <a href="B Prototype.html" style={{ display: 'inline-flex', padding: '12px 22px', borderRadius: 999, background: MT.coral, color: 'white', fontSize: 14, fontWeight: 800, border: `2px solid ${MT.ink}`, cursor: 'pointer', boxShadow: `3px 3px 0 ${MT.ink}`, alignItems: 'center', gap: 6, textDecoration: 'none' }}>
        🎒 規劃新行程
      </a>
    </div>
  );
}

window.MyTrips = MyTrips;
