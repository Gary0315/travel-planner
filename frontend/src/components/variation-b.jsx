// Variation B — 中等: Warm Playful "Sunny Day"
// Coral primary, mint accents, cream paper, chunky rounded shapes, friendly type.

const { useState: useStateB, useEffect: useEffectB } = React;

const B_DEFAULT_CHECKLIST = [
  '護照 / 身分證件', '錢包與信用卡', '手機', '手機充電器與線', '行動電源',
  '鑰匙', '換洗衣物', '個人常備藥品', '盥洗用品', '面紙'
];

const B_PALETTE = {
  cream: 'oklch(0.97 0.025 75)',
  paper: 'oklch(0.985 0.015 75)',
  ink: 'oklch(0.28 0.04 50)',
  muted: 'oklch(0.55 0.03 50)',
  coral: 'oklch(0.72 0.16 35)',
  coralDeep: 'oklch(0.6 0.18 35)',
  mint: 'oklch(0.78 0.11 165)',
  mintDeep: 'oklch(0.55 0.12 165)',
  sky: 'oklch(0.82 0.08 230)',
  butter: 'oklch(0.9 0.1 90)',
  border: 'oklch(0.9 0.02 75)',
};

const B_CAT = {
  景點: { bg: 'oklch(0.92 0.06 230)', fg: 'oklch(0.4 0.13 230)', label: '景點', emoji: '🗺️' },
  美食: { bg: 'oklch(0.93 0.08 60)',  fg: 'oklch(0.48 0.15 50)', label: '美食', emoji: '🍜' },
  住宿: { bg: 'oklch(0.92 0.07 165)', fg: 'oklch(0.42 0.13 165)', label: '住宿', emoji: '🛏️' },
};

function VariationB({ mobile = false }) {
  const [prompt, setPrompt] = useStateB('台中兩天一夜美食散步之旅');
  const [transport, setTransport] = useStateB('walking');
  const [loading, setLoading] = useStateB(false);
  const [plan, setPlan] = useStateB(window.TP.FALLBACK_PLANS.taichung);
  const [activeDay, setActiveDay] = useStateB(1);
  const [activeIdx, setActiveIdx] = useStateB(0);
  const [saved, setSaved] = useStateB(false);
  const [tab, setTab] = useStateB('timeline');
  const [authOpen, setAuthOpen] = useStateB(false);
  const [loggedIn, setLoggedIn] = useStateB(false);
  const [checklist, setChecklist] = useStateB(() => B_DEFAULT_CHECKLIST.map((c, i) => ({ id: 'd' + i, content: c, checked: false, isDefault: true })));
  const [newItem, setNewItem] = useStateB('');

  const handleGenerate = async () => {
    setLoading(true); setActiveIdx(0);
    const next = await window.TP.generatePlan(prompt);
    setPlan(next); setActiveDay(1); setLoading(false);
  };

  const day = plan.days.find((d) => d.day === activeDay) || plan.days[0];
  const active = day.activities[Math.min(activeIdx, day.activities.length-1)];
  const P = B_PALETTE;
  const font = '"Quicksand", "Nunito", ui-rounded, "Noto Sans TC", system-ui, sans-serif';

  return (
    <div style={{ fontFamily: font, background: P.cream, color: P.ink, minHeight: '100%', padding: mobile?'14px':'28px 36px', position: 'relative', overflow: 'hidden' }}>
      {/* Decorative blobs */}
      <div style={{ position: 'absolute', top: -60, right: -40, width: 200, height: 200, borderRadius: '50%', background: P.butter, opacity: 0.5, filter: 'blur(2px)', pointerEvents: 'none' }} />
      <div style={{ position: 'absolute', top: 200, left: -80, width: 240, height: 240, borderRadius: '50%', background: P.sky, opacity: 0.35, filter: 'blur(2px)', pointerEvents: 'none' }} />

      {/* Header */}
      <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: mobile?14:20, position: 'relative' }}>
        <div style={{ display: 'flex', alignItems: 'center', gap: 10 }}>
          <div style={{ width: 38, height: 38, borderRadius: 14, background: P.coral, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'white', fontSize: 20, transform: 'rotate(-6deg)', boxShadow: `3px 3px 0 ${P.ink}` }}>✈</div>
          <div>
            <div style={{ fontSize: mobile?17:19, fontWeight: 800, letterSpacing: '-0.02em' }}>Roamie</div>
            <div style={{ fontSize: 10.5, color: P.muted, marginTop: -2 }}>your pocket-sized travel friend</div>
          </div>
        </div>
        <div style={{ display: 'flex', gap: 8 }}>
          <AuthButtonB mobile={mobile} P={P} loggedIn={loggedIn} setLoggedIn={setLoggedIn} setAuthOpen={setAuthOpen} />
        </div>
      </div>

      {/* Prompt card */}
      <div style={{ background: 'white', borderRadius: 26, padding: mobile?18:24, marginBottom: 18, position: 'relative', boxShadow: `4px 4px 0 ${P.ink}`, border: `2px solid ${P.ink}` }}>
        <div style={{ display: 'flex', alignItems: 'flex-end', gap: 8, marginBottom: 4 }}>
          <div style={{ fontSize: mobile?22:28, fontWeight: 800, letterSpacing: '-0.03em', lineHeight: 1.1 }}>嘿！想去哪裡呢 <span style={{ display: 'inline-block', transform: 'rotate(15deg)' }}>👋</span></div>
        </div>
        <div style={{ fontSize: 13, color: P.muted, marginBottom: 14 }}>跟我說一句話就好 — 我會幫你打包成行程✨</div>

        <div style={{ background: P.cream, borderRadius: 18, padding: '14px 16px', border: `1.5px dashed ${P.coral}` }}>
          <textarea
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            rows={mobile?3:2}
            placeholder="台中兩天一夜美食散步之旅"
            style={{ width: '100%', boxSizing: 'border-box', resize: 'none', border: 'none', background: 'transparent', fontSize: 15, fontFamily: font, outline: 'none', color: P.ink, fontWeight: 500 }}
          />
        </div>

        <div style={{ marginTop: 12, display: 'flex', flexWrap: 'wrap', gap: 6 }}>
          {window.TP.SAMPLE_PROMPTS.map((s, i) => {
            const colors = [P.butter, P.sky, P.mint, 'oklch(0.92 0.08 320)'];
            return (
              <button key={s} onClick={() => setPrompt(s)} style={{ fontSize: 12, fontWeight: 600, padding: '6px 11px', borderRadius: 999, border: `1.5px solid ${P.ink}`, background: colors[i%4], color: P.ink, cursor: 'pointer' }}>✨ {s}</button>
            );
          })}
        </div>

        <div style={{ marginTop: 16, display: 'flex', flexDirection: mobile?'column':'row', alignItems: mobile?'stretch':'center', justifyContent: 'space-between', gap: 12 }}>
          <div style={{ display: 'flex', gap: 6 }}>
            {[['walking','步行','🚶'],['driving','開車','🚗'],['transit','大眾','🚇']].map(([v,l,e]) => (
              <button key={v} onClick={() => setTransport(v)} style={{ display: 'inline-flex', alignItems: 'center', gap: 5, padding: '9px 14px', fontSize: 13, fontWeight: 700, borderRadius: 999, border: `2px solid ${P.ink}`, color: P.ink, background: transport===v?P.mint:'white', cursor: 'pointer', boxShadow: transport===v?`2px 2px 0 ${P.ink}`:'none', transform: transport===v?'translate(-1px,-1px)':'none' }}>
                <span>{e}</span>{l}
              </button>
            ))}
          </div>
          <button onClick={handleGenerate} disabled={loading} style={{ padding: '12px 22px', borderRadius: 999, background: P.coral, color: 'white', fontSize: 14, fontWeight: 800, border: `2px solid ${P.ink}`, cursor: loading?'wait':'pointer', boxShadow: `3px 3px 0 ${P.ink}`, transform: loading?'translate(1px,1px)':'none' }}>
            {loading ? '🪄 規劃中…' : '🎒 出發！'}
          </button>
        </div>
      </div>

      {loading ? <SkeletonB mobile={mobile} P={P} /> : (
        <>
          {/* Tabs */}
          <div style={{ display: 'flex', gap: 6, marginBottom: 14, background: 'white', padding: 4, borderRadius: 999, border: `2px solid ${P.ink}`, boxShadow: `2px 2px 0 ${P.ink}`, width: 'fit-content' }}>
            {[['timeline','🗺️ 行程'],['checklist','✓ 行前清單']].map(([v,l]) => (
              <button key={v} onClick={() => setTab(v)} style={{ padding: '7px 16px', borderRadius: 999, fontSize: 13, fontWeight: 700, border: 'none', background: tab===v?P.ink:'transparent', color: tab===v?'white':P.ink, cursor: 'pointer', fontFamily: 'inherit' }}>{l}</button>
            ))}
          </div>
          {tab === 'timeline' ? (
            <ResultB plan={plan} day={day} activeDay={activeDay} setActiveDay={(d)=>{setActiveDay(d);setActiveIdx(0);}} activeIdx={activeIdx} setActiveIdx={setActiveIdx} active={active} mobile={mobile} P={P} saved={saved} setSaved={setSaved} />
          ) : (
            <ChecklistB items={checklist} setItems={setChecklist} newItem={newItem} setNewItem={setNewItem} mobile={mobile} P={P} />
          )}
        </>
      )}

      {/* Auth modal/sheet renders at VariationB top-level so it fills the whole frame */}
      {authOpen && <AuthOverlayB mobile={mobile} P={P} onClose={() => setAuthOpen(false)} onAuth={() => { setLoggedIn(true); setAuthOpen(false); }} />}
    </div>
  );
}

function SkeletonB({ mobile, P }) {
  return (
    <div style={{ background: 'white', borderRadius: 26, padding: 24, border: `2px solid ${P.ink}`, boxShadow: `4px 4px 0 ${P.ink}`, textAlign: 'center', position: 'relative' }}>
      <div style={{ fontSize: 32, marginBottom: 6, animation: 'bounce 1s infinite' }}>🗺️</div>
      <div style={{ fontSize: 14, fontWeight: 700, color: P.ink }}>正在為你規劃路線⋯</div>
      <div style={{ fontSize: 12, color: P.muted, marginTop: 4 }}>挑選順路的景點與美食</div>
      <div style={{ marginTop: 18, display: 'flex', gap: 6, justifyContent: 'center' }}>
        {['🚶','📍','🍜','🛏️'].map((e,i) => <div key={i} style={{ fontSize: 22, opacity: 0.4 + (i*0.15), animation: `pulse 1.4s infinite ${i*0.2}s` }}>{e}</div>)}
      </div>
      <style>{`@keyframes bounce{0%,100%{transform:translateY(0)}50%{transform:translateY(-8px)}}@keyframes pulse{0%,100%{opacity:.3}50%{opacity:1}}`}</style>
    </div>
  );
}

function ResultB({ plan, day, activeDay, setActiveDay, activeIdx, setActiveIdx, active, mobile, P, saved, setSaved }) {
  return (
    <div style={{ position: 'relative' }}>
      {/* Trip header chip */}
      <div style={{ background: 'white', borderRadius: 22, padding: '14px 18px', border: `2px solid ${P.ink}`, boxShadow: `3px 3px 0 ${P.ink}`, marginBottom: 14, display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 10, flexWrap: 'wrap' }}>
        <div style={{ minWidth: 0, flex: 1 }}>
          <div style={{ fontSize: 11, fontWeight: 700, color: P.coralDeep, letterSpacing: '0.08em' }}>YOUR TRIP ✦</div>
          <div style={{ fontSize: mobile?17:21, fontWeight: 800, letterSpacing: '-0.02em', marginTop: 1 }}>{plan.title}</div>
          {plan.summary && <div style={{ fontSize: 12.5, color: P.muted, marginTop: 2 }}>{plan.summary}</div>}
        </div>
        <button onClick={()=>setSaved(true)} style={{ padding: '10px 16px', borderRadius: 999, background: saved?P.mint:P.ink, color: saved?P.ink:'white', fontSize: 13, fontWeight: 700, border: `2px solid ${P.ink}`, cursor: 'pointer' }}>
          {saved ? '✓ 已收藏' : '💌 分享給朋友'}
        </button>
      </div>

      {/* Day pills */}
      <div style={{ display: 'flex', gap: 8, marginBottom: 14, flexWrap: 'wrap' }}>
        {plan.days.map((d) => (
          <button key={d.day} onClick={() => setActiveDay(d.day)} style={{ padding: '8px 14px', borderRadius: 999, border: `2px solid ${P.ink}`, background: activeDay===d.day?P.coral:'white', color: activeDay===d.day?'white':P.ink, fontSize: 13, fontWeight: 700, cursor: 'pointer', boxShadow: activeDay===d.day?`2px 2px 0 ${P.ink}`:'none' }}>
            Day {d.day} · {d.theme} {d.weather}
          </button>
        ))}
      </div>

      {/* Grid */}
      <div style={{ display: 'grid', gridTemplateColumns: mobile?'1fr':'1.05fr 1fr', gap: mobile?14:18 }}>
        {/* Left timeline */}
        <div>
          {day.activities.map((a, idx) => {
            const c = B_CAT[a.category] || B_CAT.景點;
            const isActive = idx === activeIdx;
            return (
              <React.Fragment key={idx}>
                <div onClick={()=>setActiveIdx(idx)} style={{ display: 'flex', gap: 12, marginBottom: mobile && isActive ? 12 : 12, cursor: 'pointer' }}>
                  <div style={{ display: 'flex', flexDirection: 'column', alignItems: 'center', paddingTop: 6 }}>
                    <div style={{ fontSize: 22, transform: isActive?'scale(1.15)':'scale(1)', transition: 'transform .2s' }}>{a.emoji || c.emoji}</div>
                    {idx < day.activities.length-1 && <div style={{ width: 2, flex: 1, background: P.ink, marginTop: 2, marginBottom: -4, opacity: 0.18, borderRadius: 999 }} />}
                  </div>
                  <div style={{ flex: 1, background: 'white', borderRadius: 16, padding: '12px 14px', border: `2px solid ${P.ink}`, boxShadow: isActive?`3px 3px 0 ${P.coral}`:`2px 2px 0 ${P.ink}`, transform: isActive?'translate(-1px,-1px)':'none', transition: 'all .2s' }}>
                    <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', gap: 8, marginBottom: 4 }}>
                      <div style={{ fontSize: 12, fontWeight: 700, color: P.muted, fontVariantNumeric: 'tabular-nums' }}>{a.time}</div>
                      <span style={{ fontSize: 10.5, padding: '3px 9px', borderRadius: 999, background: c.bg, color: c.fg, fontWeight: 700 }}>{c.label}</span>
                    </div>
                    <div style={{ fontSize: 15, fontWeight: 800, letterSpacing: '-0.01em', marginBottom: 2 }}>{a.location}</div>
                    <div style={{ fontSize: 12.5, color: P.muted, lineHeight: 1.5 }}>{a.description}</div>
                    {a.transport && <div style={{ marginTop: 6, fontSize: 11, color: P.coralDeep, fontWeight: 700 }}>→ {a.transport}</div>}
                  </div>
                </div>
                {mobile && isActive && (
                  <div style={{ marginLeft: 38, marginBottom: 16 }}>
                    <DetailPanelB active={active} P={P} hideHotels />
                  </div>
                )}
              </React.Fragment>
            );
          })}
          {mobile && day.hotels && (
            <div style={{ marginTop: 8 }}>
              <div style={{ fontSize: 12, fontWeight: 800, color: P.mintDeep, marginBottom: 8, display: 'flex', alignItems: 'center', gap: 4 }}>🛏 今晚住哪裡？</div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {day.hotels.slice(0,2).map((h, i) => (
                  <div key={i} style={{ display: 'flex', gap: 10, padding: 8, borderRadius: 14, border: `2px solid ${P.ink}`, background: 'white', boxShadow: `2px 2px 0 ${P.ink}`, alignItems: 'stretch' }}>
                    <div style={{ alignSelf: 'stretch', width: 72, minHeight: 72, borderRadius: 10, background: `url(${window.TP.photoFor(h.name, 'hotel')}) center/cover`, border: `1.5px solid ${P.ink}`, flexShrink: 0 }} />
                    <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', justifyContent: 'center' }}>
                      <div style={{ fontSize: 13, fontWeight: 800, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{h.name}</div>
                      <div style={{ fontSize: 11, color: P.muted, marginTop: 2, fontWeight: 600 }}>⭐ {h.rating} <span style={{ color: P.muted, opacity: 0.7 }}>· {h.reviews.toLocaleString()}</span></div>
                      <div style={{ fontSize: 10.5, color: P.muted, marginTop: 1, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{h.address}</div>
                      <div style={{ color: P.muted, marginTop: 1 }}>
                        <PhotoCredit name={h.attribution} inline href={`https://maps.google.com/maps/contrib/?q=${encodeURIComponent(h.attribution || '')}`} />
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        {/* Right panel — desktop only */}
        {!mobile && (
        <div style={{ position: 'sticky', top: 20, alignSelf: 'flex-start' }}>
          {/* Polaroid */}
          <div style={{ background: 'white', padding: 10, borderRadius: 6, border: `2px solid ${P.ink}`, boxShadow: `4px 4px 0 ${P.ink}`, transform: 'rotate(-1.5deg)' }}>
            <div style={{ position: 'relative', aspectRatio: '4/3', borderRadius: 4, overflow: 'hidden', background: P.cream }}>
              <img src={window.TP.photoFor(active.location, active.category, active.emoji)} alt={active.location} style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }} />
              <div style={{ position: 'absolute', top: 10, left: 10, padding: '4px 9px', borderRadius: 999, background: 'rgba(255,255,255,0.95)', fontSize: 11, fontWeight: 700, color: P.ink }}>{active.time}</div>
              <PhotoCredit name={active.attribution} href={`https://maps.google.com/maps/contrib/?q=${encodeURIComponent(active.attribution || '')}`} />
            </div>
            <div style={{ padding: '12px 4px 4px', textAlign: 'center', fontFamily: '"Caveat", "Indie Flower", cursive', fontSize: 22, fontWeight: 700, color: P.ink, transform: 'rotate(-0.5deg)' }}>{active.location} <span style={{ fontSize: 18 }}>{active.emoji}</span></div>
          </div>

          <div style={{ marginTop: 14, background: 'white', borderRadius: 16, padding: '12px 14px', border: `2px solid ${P.ink}`, boxShadow: `2px 2px 0 ${P.ink}` }}>
            <div style={{ fontSize: 12.5, lineHeight: 1.6, color: P.ink }}>{active.description}</div>
            <div style={{ marginTop: 10, display: 'flex', gap: 6, flexWrap: 'wrap' }}>
              <a href={`https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(active.location)}`} target="_blank" rel="noopener" style={{ padding: '6px 12px', borderRadius: 999, fontSize: 12, fontWeight: 700, background: P.sky, color: P.ink, textDecoration: 'none', border: `1.5px solid ${P.ink}` }}>📍 看地圖</a>
              <button style={{ padding: '6px 12px', borderRadius: 999, fontSize: 12, fontWeight: 700, background: P.butter, color: P.ink, border: `1.5px solid ${P.ink}`, cursor: 'pointer' }}>💾 加入收藏</button>
            </div>
          </div>

          {day.hotels && (
            <div style={{ marginTop: 14 }}>
              <div style={{ fontSize: 12, fontWeight: 800, color: P.mintDeep, marginBottom: 8, display: 'flex', alignItems: 'center', gap: 4 }}>🛏 今晚住哪裡？</div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: 8 }}>
                {day.hotels.slice(0,2).map((h, i) => (
                  <div key={i} style={{ display: 'flex', gap: 10, padding: 8, borderRadius: 14, border: `2px solid ${P.ink}`, background: 'white', boxShadow: `2px 2px 0 ${P.ink}`, alignItems: 'stretch' }}>
                    <div style={{ alignSelf: 'stretch', width: 72, minHeight: 72, borderRadius: 10, background: `url(${window.TP.photoFor(h.name, 'hotel')}) center/cover`, border: `1.5px solid ${P.ink}`, flexShrink: 0 }} />
                    <div style={{ flex: 1, minWidth: 0, display: 'flex', flexDirection: 'column', justifyContent: 'center' }}>
                      <div style={{ fontSize: 13, fontWeight: 800, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{h.name}</div>
                      <div style={{ fontSize: 11, color: P.muted, marginTop: 2, fontWeight: 600 }}>⭐ {h.rating} <span style={{ color: P.muted, opacity: 0.7 }}>· {h.reviews.toLocaleString()}</span></div>
                      <div style={{ fontSize: 10.5, color: P.muted, marginTop: 1, whiteSpace: 'nowrap', overflow: 'hidden', textOverflow: 'ellipsis' }}>{h.address}</div>
                      <div style={{ color: P.muted, marginTop: 1 }}>
                        <PhotoCredit name={h.attribution} inline href={`https://maps.google.com/maps/contrib/?q=${encodeURIComponent(h.attribution || '')}`} />
                      </div>
                    </div>
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>
        )}
      </div>
    </div>
  );
}

function PhotoCredit({ name, compact, inline, href }) {
  const [open, setOpen] = React.useState(false);
  if (!name) return null;
  if (inline) {
    // Inline variant: sits in text flow, light theme, expand-on-hover
    const Comp = href ? 'a' : 'span';
    return (
      <Comp
        href={href || undefined}
        target={href ? '_blank' : undefined}
        rel={href ? 'noopener noreferrer' : undefined}
        onMouseEnter={() => setOpen(true)}
        onMouseLeave={() => setOpen(false)}
        onClick={(e) => { if (!href) { e.stopPropagation(); setOpen(v => !v); } }}
        title={`Photo by ${name}`}
        style={{ display: 'inline-flex', alignItems: 'center', gap: 3, padding: open?'2px 8px 2px 6px':'2px 6px', marginTop: 3, borderRadius: 999, background: 'rgba(0,0,0,0.6)', backdropFilter: 'blur(4px)', WebkitBackdropFilter: 'blur(4px)', color: 'white', fontSize: 10, fontWeight: 500, lineHeight: 1.3, cursor: 'pointer', maxWidth: open ? 180 : 20, overflow: 'hidden', whiteSpace: 'nowrap', textDecoration: 'none', transition: 'max-width .25s, padding .2s' }}
      >
        <span style={{ fontSize: 9 }}>📷</span>
        <span style={{ opacity: open ? 1 : 0, transition: 'opacity .2s', textDecoration: open && href ? 'underline' : 'none' }}>{name}</span>
      </Comp>
    );
  }
  const sz = compact ? 18 : 22;
  const Comp = href ? 'a' : 'div';
  return (
    <Comp
      href={href || undefined}
      target={href ? '_blank' : undefined}
      rel={href ? 'noopener noreferrer' : undefined}
      onMouseEnter={() => setOpen(true)}
      onMouseLeave={() => setOpen(false)}
      onClick={(e) => { if (!href) { e.stopPropagation(); setOpen(v => !v); } else { e.stopPropagation(); } }}
      style={{ position: 'absolute', right: compact?3:6, bottom: compact?3:6, display: 'inline-flex', alignItems: 'center', gap: 4, padding: open ? '3px 8px 3px 6px' : (compact?'2px 5px':'3px 6px'), borderRadius: 999, background: 'rgba(0,0,0,0.6)', backdropFilter: 'blur(4px)', WebkitBackdropFilter: 'blur(4px)', color: 'white', fontSize: compact?9.5:10, fontWeight: 500, lineHeight: 1.2, cursor: 'pointer', maxWidth: open ? 220 : sz, overflow: 'hidden', whiteSpace: 'nowrap', transition: 'max-width .25s, padding .2s', zIndex: open ? 5 : 1, textDecoration: 'none' }}
      title={`Photo by ${name}`}
    >
      <span style={{ fontSize: compact?9:10 }}>📷</span>
      <span style={{ opacity: open ? 1 : 0, transition: 'opacity .2s' }}>{name}</span>
    </Comp>
  );
}

function DetailPanelB({ active, P, hideHotels }) {
  return (
    <div>
      <div style={{ background: 'white', padding: 10, borderRadius: 6, border: `2px solid ${P.ink}`, boxShadow: `4px 4px 0 ${P.ink}`, transform: 'rotate(-1.5deg)' }}>
        <div style={{ position: 'relative', aspectRatio: '4/3', borderRadius: 4, overflow: 'hidden', background: P.cream }}>
          <img src={window.TP.photoFor(active.location, active.category, active.emoji)} alt={active.location} style={{ width: '100%', height: '100%', objectFit: 'cover', display: 'block' }} />
          <div style={{ position: 'absolute', top: 10, left: 10, padding: '4px 9px', borderRadius: 999, background: 'rgba(255,255,255,0.95)', fontSize: 11, fontWeight: 700, color: P.ink }}>{active.time}</div>
          <PhotoCredit name={active.attribution} href={`https://maps.google.com/maps/contrib/?q=${encodeURIComponent(active.attribution || '')}`} />
        </div>
        <div style={{ padding: '12px 4px 4px', textAlign: 'center', fontFamily: '"Caveat", "Indie Flower", cursive', fontSize: 22, fontWeight: 700, color: P.ink, transform: 'rotate(-0.5deg)' }}>{active.location} <span style={{ fontSize: 18 }}>{active.emoji}</span></div>
      </div>
      <div style={{ marginTop: 14, background: 'white', borderRadius: 16, padding: '12px 14px', border: `2px solid ${P.ink}`, boxShadow: `2px 2px 0 ${P.ink}` }}>
        <div style={{ fontSize: 12.5, lineHeight: 1.6, color: P.ink }}>{active.description}</div>
        <div style={{ marginTop: 10, display: 'flex', gap: 6, flexWrap: 'wrap' }}>
          <a href={`https://www.google.com/maps/search/?api=1&query=${encodeURIComponent(active.location)}`} target="_blank" rel="noopener" style={{ padding: '6px 12px', borderRadius: 999, fontSize: 12, fontWeight: 700, background: P.sky, color: P.ink, textDecoration: 'none', border: `1.5px solid ${P.ink}` }}>📍 看地圖</a>
          <button style={{ padding: '6px 12px', borderRadius: 999, fontSize: 12, fontWeight: 700, background: P.butter, color: P.ink, border: `1.5px solid ${P.ink}`, cursor: 'pointer' }}>💾 加入收藏</button>
        </div>
      </div>
    </div>
  );
}

function ChecklistB({ items, setItems, newItem, setNewItem, mobile, P }) {
  const toggle = (id) => setItems(items.map(it => it.id === id ? { ...it, checked: !it.checked } : it));
  const remove = (id) => setItems(items.filter(it => it.id !== id || it.isDefault));
  const add = () => {
    if (!newItem.trim()) return;
    setItems([...items, { id: 'c' + Date.now(), content: newItem.trim(), checked: false, isDefault: false }]);
    setNewItem('');
  };
  const completed = items.filter(i => i.checked).length;

  return (
    <div style={{ background: 'white', borderRadius: 22, padding: mobile?18:24, border: `2px solid ${P.ink}`, boxShadow: `3px 3px 0 ${P.ink}` }}>
      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between', marginBottom: 14, gap: 10, flexWrap: 'wrap' }}>
        <div>
          <div style={{ fontSize: 11, fontWeight: 700, color: P.mintDeep, letterSpacing: '0.08em' }}>PACK YOUR BAG ✓</div>
          <div style={{ fontSize: mobile?18:22, fontWeight: 800, letterSpacing: '-0.02em', marginTop: 2 }}>行前清單</div>
          <div style={{ fontSize: 12.5, color: P.muted, marginTop: 2 }}>出門前打勾一下，以免漏帶 ✏️</div>
        </div>
        <div style={{ display: 'inline-flex', alignItems: 'center', gap: 8, padding: '8px 14px', borderRadius: 999, background: P.mint, border: `2px solid ${P.ink}`, fontSize: 13, fontWeight: 800, color: P.ink }}>
          {completed} / {items.length} 項完成
        </div>
      </div>

      {/* Progress bar */}
      <div style={{ height: 10, background: P.cream, borderRadius: 999, border: `1.5px solid ${P.ink}`, overflow: 'hidden', marginBottom: 14 }}>
        <div style={{ height: '100%', width: `${items.length ? (completed/items.length)*100 : 0}%`, background: P.mint, transition: 'width .3s' }} />
      </div>

      {/* Add new */}
      <div style={{ display: 'flex', gap: 8, marginBottom: 14 }}>
        <input value={newItem} onChange={(e)=>setNewItem(e.target.value)} onKeyDown={(e)=>e.key==='Enter'&&add()} placeholder="新增自訂項目…" style={{ flex: 1, padding: '10px 14px', fontSize: 14, borderRadius: 999, border: `2px solid ${P.ink}`, background: P.cream, fontFamily: 'inherit', outline: 'none', color: P.ink }} />
        <button onClick={add} style={{ padding: '10px 18px', borderRadius: 999, background: P.coral, color: 'white', fontSize: 14, fontWeight: 800, border: `2px solid ${P.ink}`, boxShadow: `2px 2px 0 ${P.ink}`, cursor: 'pointer', fontFamily: 'inherit' }}>+ 加入</button>
      </div>

      {/* List */}
      <div style={{ display: 'grid', gridTemplateColumns: mobile?'1fr':'1fr 1fr', gap: 8 }}>
        {items.map(it => (
          <label key={it.id} style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '10px 12px', borderRadius: 14, border: `2px solid ${P.ink}`, background: it.checked?P.mint:'white', boxShadow: `2px 2px 0 ${P.ink}`, cursor: 'pointer', transition: 'background .15s' }}>
            <span onClick={(e)=>{e.preventDefault(); toggle(it.id);}} style={{ width: 22, height: 22, borderRadius: 6, border: `2px solid ${P.ink}`, background: it.checked?P.ink:'white', display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'white', fontSize: 13, fontWeight: 800, flexShrink: 0 }}>{it.checked?'✓':''}</span>
            <span style={{ flex: 1, fontSize: 14, fontWeight: 600, color: P.ink, textDecoration: it.checked?'line-through':'none', textDecorationThickness: 2, opacity: it.checked?0.6:1 }}>{it.content}</span>
            {!it.isDefault && (
              <button onClick={(e)=>{e.preventDefault(); remove(it.id);}} style={{ width: 22, height: 22, borderRadius: 999, border: `1.5px solid ${P.ink}`, background: 'white', fontSize: 12, fontWeight: 800, color: P.ink, cursor: 'pointer', lineHeight: 1, padding: 0 }}>×</button>
            )}
          </label>
        ))}
      </div>
    </div>
  );
}

window.VariationB = VariationB;

// ─────────────────────────────────────────────────────────────────────────
// AuthButtonB — login / register flow integrated into the B header.
// Logged-out: butter "🧳 登入 / 註冊" pill → opens modal (desktop) / sheet (mobile)
// Logged-in: avatar pill → opens dropdown menu
// All overlays render position:absolute inside VariationB's relative frame.
// ─────────────────────────────────────────────────────────────────────────
const HAND_FONT_B = '"Caveat", "Indie Flower", cursive';

function AuthButtonB({ mobile, P, loggedIn, setLoggedIn, setAuthOpen }) {
  const [menuOpen, setMenuOpen] = useStateB(false);
  const [userName] = useStateB('怡安');

  // close dropdown on outside click
  useEffectB(() => {
    if (!menuOpen) return;
    const t = setTimeout(() => {
      const handler = () => setMenuOpen(false);
      window.addEventListener('click', handler, { once: true });
      // store remove fn
      AuthButtonB._h = () => window.removeEventListener('click', handler);
    }, 0);
    return () => { clearTimeout(t); if (AuthButtonB._h) AuthButtonB._h(); };
  }, [menuOpen]);

  // Esc closes dropdown (modal Esc handled by VariationB-level overlay)
  useEffectB(() => {
    if (!menuOpen) return;
    const onKey = (e) => { if (e.key === 'Escape') setMenuOpen(false); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [menuOpen]);

  const onLogout = () => { setLoggedIn(false); setMenuOpen(false); };

  if (loggedIn) {
    const initial = userName.slice(0, 1);
    return (
      <div style={{ position: 'relative' }}>
        <button
          onClick={(e) => { e.stopPropagation(); setMenuOpen(v => !v); }}
          style={{ display: 'inline-flex', alignItems: 'center', gap: 8, padding: '4px 12px 4px 4px', borderRadius: 999, border: `2px solid ${P.ink}`, background: 'white', boxShadow: `3px 3px 0 ${P.ink}`, cursor: 'pointer', fontFamily: 'inherit' }}
        >
          <span style={{ width: 28, height: 28, borderRadius: '50%', background: P.mint, border: `2px solid ${P.ink}`, display: 'inline-flex', alignItems: 'center', justifyContent: 'center', fontSize: 13, fontWeight: 800, color: P.ink }}>{initial}</span>
          {!mobile && <span style={{ fontSize: 13, fontWeight: 800, color: P.ink }}>{userName}</span>}
          <span style={{ fontSize: 9, color: P.muted }}>▾</span>
        </button>
        {menuOpen && (
          <div
            onClick={(e) => e.stopPropagation()}
            style={{ position: 'absolute', top: 'calc(100% + 8px)', right: 0, background: 'white', border: `2px solid ${P.ink}`, borderRadius: 18, boxShadow: `4px 4px 0 ${P.ink}`, padding: 6, minWidth: 220, zIndex: 30, fontFamily: 'inherit' }}
          >
            <div style={{ padding: '10px 12px 8px', borderBottom: `1.5px dashed ${P.border}`, marginBottom: 4 }}>
              <div style={{ fontSize: 13, fontWeight: 800, color: P.ink }}>{userName} 👋</div>
              <div style={{ fontSize: 11, color: P.muted, marginTop: 1 }}>yian@roamie.app</div>
            </div>
            {[
              { e: '💌', l: '我的行程', c: '3' },
              { e: '🧾', l: '共同記帳', c: 'NEW', href: 'Trip Wallet.html', highlight: true },
              { e: '💾', l: '收藏景點', c: '12' },
              { e: '✓', l: '行前清單' },
              { e: '⚙', l: '帳戶設定' },
            ].map((item) => {
              const Tag = item.href ? 'a' : 'button';
              return (
                <Tag key={item.l} href={item.href || undefined} style={{ display: 'flex', alignItems: 'center', gap: 10, width: '100%', padding: '8px 12px', border: 'none', background: 'transparent', borderRadius: 12, cursor: 'pointer', fontFamily: 'inherit', color: P.ink, textDecoration: 'none', boxSizing: 'border-box' }}
                  onMouseEnter={(ev) => ev.currentTarget.style.background = P.cream}
                  onMouseLeave={(ev) => ev.currentTarget.style.background = 'transparent'}
                >
                  <span style={{ fontSize: 14, width: 18, textAlign: 'center' }}>{item.e}</span>
                  <span style={{ flex: 1, textAlign: 'left', fontSize: 13, fontWeight: 600 }}>{item.l}</span>
                  {item.c && <span style={{ fontSize: 10, fontWeight: 800, background: item.highlight ? P.mint : P.butter, color: P.ink, padding: '2px 8px', borderRadius: 999, border: `1.5px solid ${P.ink}`, letterSpacing: item.highlight ? '0.06em' : 0 }}>{item.c}</span>}
                </Tag>
              );
            })}
            <div style={{ borderTop: `1.5px dashed ${P.border}`, marginTop: 4, paddingTop: 4 }}>
              <button onClick={onLogout} style={{ display: 'flex', alignItems: 'center', gap: 10, width: '100%', padding: '8px 12px', border: 'none', background: 'transparent', borderRadius: 12, cursor: 'pointer', fontFamily: 'inherit', color: P.coralDeep, fontWeight: 700, fontSize: 13 }}>
                <span style={{ fontSize: 14, width: 18, textAlign: 'center' }}>↩</span>
                <span>登出</span>
              </button>
            </div>
          </div>
        )}
      </div>
    );
  }

  // logged-out: butter pill
  return (
    <button
      onClick={() => setAuthOpen(true)}
      style={{ display: 'inline-flex', alignItems: 'center', gap: 6, padding: mobile ? '7px 12px' : '8px 14px', fontSize: mobile ? 12.5 : 13, fontWeight: 800, borderRadius: 999, border: `2px solid ${P.ink}`, background: P.butter, color: P.ink, cursor: 'pointer', boxShadow: `3px 3px 0 ${P.ink}`, fontFamily: 'inherit', transition: 'transform .12s, box-shadow .12s' }}
      onMouseEnter={(e) => { e.currentTarget.style.transform = 'translate(-1px,-1px)'; e.currentTarget.style.boxShadow = `4px 4px 0 ${P.ink}`; }}
      onMouseLeave={(e) => { e.currentTarget.style.transform = 'none'; e.currentTarget.style.boxShadow = `3px 3px 0 ${P.ink}`; }}
    >
      <span style={{ fontSize: mobile ? 13 : 14 }}>🧳</span>
      {mobile ? '登入' : '登入 / 註冊'}
    </button>
  );
}

function AuthOverlayB({ mobile, P, onClose, onAuth }) {
  // Esc to close
  useEffectB(() => {
    const onKey = (e) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    return () => window.removeEventListener('keydown', onKey);
  }, [onClose]);

  // desktop: centered modal. mobile: bottom sheet.
  return (
    <div
      onClick={onClose}
      style={{ position: 'fixed', inset: 0, background: 'rgba(40,30,20,0.45)', display: 'flex', alignItems: mobile ? 'flex-end' : 'center', justifyContent: 'center', zIndex: 40, padding: mobile ? 0 : 20, fontFamily: 'inherit', animation: 'authFade .18s ease-out', boxSizing: 'border-box' }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={mobile ? {
          width: '100%', maxWidth: 420, background: 'white', borderRadius: '24px 24px 0 0', border: `2px solid ${P.ink}`, borderBottom: 'none', padding: 22, position: 'relative', boxSizing: 'border-box', animation: 'authSlideUp .25s cubic-bezier(.2,.7,.3,1)'
        } : {
          width: '100%', maxWidth: 380, background: 'white', borderRadius: 26, border: `2px solid ${P.ink}`, boxShadow: `6px 6px 0 ${P.ink}`, padding: 26, position: 'relative', boxSizing: 'border-box', animation: 'authPop .25s cubic-bezier(.2,.7,.3,1)'
        }}
      >
        {/* Mobile drag handle */}
        {mobile && <div style={{ width: 48, height: 5, background: P.border, borderRadius: 999, margin: '0 auto 14px' }} />}

        {/* Close (desktop only — sheet uses backdrop/handle) */}
        {!mobile && (
          <button onClick={onClose} style={{ position: 'absolute', top: 14, right: 14, width: 30, height: 30, borderRadius: 999, border: `2px solid ${P.ink}`, background: 'white', fontSize: 14, fontWeight: 800, color: P.ink, cursor: 'pointer', boxShadow: `2px 2px 0 ${P.ink}`, fontFamily: 'inherit', padding: 0, lineHeight: 1 }}>×</button>
        )}

        {/* Sticker logo + eyebrow */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 12, marginBottom: mobile ? 10 : 14 }}>
          <div style={{ width: mobile ? 36 : 42, height: mobile ? 36 : 42, borderRadius: 14, background: P.coral, display: 'flex', alignItems: 'center', justifyContent: 'center', color: 'white', fontSize: mobile ? 18 : 22, transform: 'rotate(-6deg)', boxShadow: `3px 3px 0 ${P.ink}`, flexShrink: 0 }}>✈</div>
          <div>
            <div style={{ fontSize: 11, fontWeight: 700, color: P.coralDeep, letterSpacing: '0.1em' }}>WELCOME BACK ✦</div>
            <div style={{ fontSize: 14, fontWeight: 800, color: P.ink, marginTop: 1 }}>歡迎回來，旅人</div>
          </div>
        </div>

        <div style={{ fontSize: mobile ? 17 : 20, fontWeight: 800, letterSpacing: '-0.02em', lineHeight: 1.25, marginBottom: mobile ? 14 : 18 }}>
          登入後，即可管理你的行程! <span style={{ display: 'inline-block', transform: 'rotate(10deg)' }}>📒</span>
        </div>

        {/* OAuth */}
        <div style={{ display: 'flex', flexDirection: 'column', gap: 9, marginTop: mobile ? 14 : 0, marginBottom: 12 }}>
          <button onClick={onAuth} style={authBtnB({ bg: 'white', fg: P.ink, P })}>
            <span style={{ width: 20, display: 'inline-flex', justifyContent: 'center', fontSize: 15, fontWeight: 800 }}>G</span>
            <span style={{ flex: 1, textAlign: 'center' }}>使用 Google 繼續</span>
            <span style={{ width: 20 }} />
          </button>
          <button onClick={onAuth} style={authBtnB({ bg: P.ink, fg: 'white', P })}>
            <span style={{ width: 20, display: 'inline-flex', justifyContent: 'center', fontSize: 16 }}></span>
            <span style={{ flex: 1, textAlign: 'center' }}>使用 Apple 繼續</span>
            <span style={{ width: 20 }} />
          </button>
        </div>

        {/* Divider */}
        <div style={{ display: 'flex', alignItems: 'center', gap: 10, margin: '14px 0 12px' }}>
          <div style={{ flex: 1, height: 1.5, background: P.border, borderRadius: 999 }} />
          <div style={{ fontSize: 11, fontWeight: 700, color: P.muted, letterSpacing: '0.08em' }}>OR</div>
          <div style={{ flex: 1, height: 1.5, background: P.border, borderRadius: 999 }} />
        </div>

        {/* Email */}
        <div style={{ background: P.cream, borderRadius: 16, padding: '10px 14px', border: `1.5px dashed ${P.coral}`, marginBottom: 10 }}>
          <div style={{ fontSize: 10.5, fontWeight: 700, color: P.muted, letterSpacing: '0.08em', marginBottom: 2 }}>EMAIL</div>
          <input type="email" placeholder="hello@roamie.app" style={{ width: '100%', border: 'none', background: 'transparent', fontSize: 14, fontFamily: 'inherit', fontWeight: 600, outline: 'none', color: P.ink }} />
        </div>
        <button onClick={onAuth} style={authBtnB({ bg: P.coral, fg: 'white', P })}>
          📮 寄一個登入連結給我
        </button>

        <div style={{ fontSize: 11, color: P.muted, marginTop: 14, textAlign: 'center', lineHeight: 1.5 }}>
          繼續即代表你同意我們的 <a href="#" style={{ color: P.coralDeep, fontWeight: 700 }}>服務條款</a> 和 <a href="#" style={{ color: P.coralDeep, fontWeight: 700 }}>隱私政策</a>
        </div>
      </div>

      <style>{`
        @keyframes authFade { from { opacity: 0; } to { opacity: 1; } }
        @keyframes authPop { from { opacity: 0; transform: scale(0.96) translateY(8px); } to { opacity: 1; transform: scale(1) translateY(0); } }
        @keyframes authSlideUp { from { transform: translateY(100%); } to { transform: translateY(0); } }
      `}</style>
    </div>
  );
}

function authBtnB({ bg, fg, P }) {
  return { display: 'flex', alignItems: 'center', gap: 6, width: '100%', padding: '11px 14px', fontSize: 14, fontWeight: 800, borderRadius: 999, border: `2px solid ${P.ink}`, background: bg, color: fg, cursor: 'pointer', boxShadow: `3px 3px 0 ${P.ink}`, fontFamily: 'inherit', justifyContent: 'center', boxSizing: 'border-box' };
}