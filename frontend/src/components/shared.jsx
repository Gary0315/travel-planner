// Shared sample data + simulated AI generator for all 3 variations.
// Exposes window.TP for the variation files.

const SAMPLE_PROMPTS = [
  '台中兩天一夜美食散步之旅',
  '台南三天兩夜文青咖啡行',
  '宜蘭兩天一夜親子放鬆',
  '花蓮三天兩夜山海療癒',
];

// Cute generated SVG postcards — soft pastel scene + the activity emoji centered.
// Deterministic by location name; styled by category.
const CUTE_PALETTES = [
  { sky: '#FFE5B4', ground: '#FFB088', accent: '#FF8C5A', tag: 'peach' },
  { sky: '#CDE7F7', ground: '#9BCAEC', accent: '#5B9BD5', tag: 'sky' },
  { sky: '#D7F0DC', ground: '#A8DCB8', accent: '#6BBF82', tag: 'mint' },
  { sky: '#FFE0EC', ground: '#FFB8D0', accent: '#FF7AAA', tag: 'pink' },
  { sky: '#FFF1B8', ground: '#FFD970', accent: '#F2B736', tag: 'butter' },
  { sky: '#E5D9F5', ground: '#C5AEEA', accent: '#9C7DD5', tag: 'lavender' },
  { sky: '#FFD8C2', ground: '#F2A88B', accent: '#D97A5A', tag: 'coral' },
];

function _hash(s) {
  let h = 0;
  for (let i = 0; i < (s||'').length; i++) h = (h * 31 + s.charCodeAt(i)) | 0;
  return Math.abs(h);
}

function photoFor(location, category, emoji) {
  const h = _hash(location || 'x');
  const pal = CUTE_PALETTES[h % CUTE_PALETTES.length];
  const emo = emoji || (category === '美食' ? '🍜' : category === '住宿' ? '🏨' : category === 'hotel' ? '🛏️' : '🗺️');
  const isFood = category === '美食';
  const isHotel = category === '住宿' || category === 'hotel';

  let scene;
  if (isHotel) {
    // City silhouette + moon
    scene = `
      <circle cx="650" cy="120" r="48" fill="#FFFAE0" opacity="0.92"/>
      <circle cx="632" cy="112" r="40" fill="${pal.sky}"/>
      <rect x="120" y="280" width="120" height="200" rx="8" fill="${pal.accent}" opacity="0.85"/>
      <rect x="260" y="220" width="160" height="260" rx="8" fill="white" stroke="${pal.accent}" stroke-width="5"/>
      <rect x="440" y="300" width="110" height="180" rx="8" fill="${pal.accent}" opacity="0.7"/>
      <rect x="570" y="260" width="130" height="220" rx="8" fill="white" stroke="${pal.accent}" stroke-width="5"/>
      ${[0,1,2,3,4].map(r => [0,1,2].map(c => `<rect x="${280+c*45}" y="${245+r*40}" width="28" height="28" rx="3" fill="#FFFAE0"/>`).join('')).join('')}
      ${[0,1,2,3,4].map(r => [0,1].map(c => `<rect x="${590+c*55}" y="${285+r*40}" width="32" height="28" rx="3" fill="#FFFAE0"/>`).join('')).join('')}`;
  } else if (isFood) {
    // Tablecloth + plate
    scene = `
      <circle cx="650" cy="100" r="40" fill="#FFFAE0" opacity="0.9"/>
      <path d="M0,360 L800,360 L800,520 L0,520 Z" fill="${pal.accent}" opacity="0.45"/>
      <path d="M0,360 L800,360" stroke="${pal.accent}" stroke-width="3" stroke-dasharray="14 10" opacity="0.6"/>
      <ellipse cx="400" cy="430" rx="220" ry="55" fill="white" stroke="${pal.accent}" stroke-width="5"/>
      <ellipse cx="400" cy="425" rx="195" ry="42" fill="${pal.sky}" opacity="0.5"/>`;
  } else {
    // Scenic: sun + cloud + hills
    scene = `
      <circle cx="640" cy="130" r="58" fill="#FFE066" opacity="0.9"/>
      <ellipse cx="180" cy="160" rx="60" ry="20" fill="white" opacity="0.88"/>
      <ellipse cx="220" cy="148" rx="40" ry="18" fill="white" opacity="0.88"/>
      <ellipse cx="540" cy="200" rx="48" ry="16" fill="white" opacity="0.7"/>
      <path d="M0,420 Q150,310 300,360 T600,320 T820,380 L820,540 L0,540 Z" fill="${pal.accent}" opacity="0.7"/>
      <path d="M0,460 Q200,390 400,420 T820,440 L820,540 L0,540 Z" fill="${pal.ground}"/>`;
  }

  const svg = `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 800 520" preserveAspectRatio="xMidYMid slice">
    <defs><linearGradient id="bg${h%999}" x1="0" y1="0" x2="0" y2="1">
      <stop offset="0%" stop-color="${pal.sky}"/>
      <stop offset="100%" stop-color="${pal.ground}" stop-opacity="0.55"/>
    </linearGradient></defs>
    <rect width="800" height="520" fill="url(#bg${h%999})"/>
    ${scene}
    <g transform="translate(400 ${isFood ? 240 : 260})">
      <circle r="92" fill="white" opacity="0.55"/>
      <text text-anchor="middle" dominant-baseline="central" font-size="120" font-family="'Apple Color Emoji','Segoe UI Emoji','Noto Color Emoji',sans-serif">${emo}</text>
    </g>
  </svg>`;

  // Encode unicode-safe
  const b64 = btoa(unescape(encodeURIComponent(svg)));
  return 'data:image/svg+xml;base64,' + b64;
}

const FALLBACK_PLANS = {
  taichung: {
    title: '台中兩天一夜美食散步之旅',
    summary: '走進台中西區的文青角落，沿著草悟道與審計新村慢慢吃慢慢逛。',
    days: [
      {
        day: 1,
        theme: '西區散步日',
        weather: '☀️ 26°C',
        activities: [
          { time: '09:00 - 10:30', location: '草悟道', category: '景點', description: '沿線散步與咖啡店巡禮，體驗台中西區文青氛圍。', transport: '步行 8 分鐘', emoji: '🌳', attribution: '劉韶春' },
          { time: '10:38 - 12:00', location: '審計新村', category: '景點', description: '探索特色小店與文創品牌，適合拍照打卡。', transport: '步行 10 分鐘', emoji: '🏘️', attribution: 'Min-Hao Lin' },
          { time: '12:10 - 13:40', location: '向上市場', category: '美食', description: '在地小吃午餐，推薦麵線、滷味與甜點。', transport: '步行 12 分鐘', emoji: '🍜', attribution: '陳怡君' },
          { time: '13:52 - 15:00', location: '中區老字號冰菓室', category: '美食', description: '台中經典甜點老店，霜淇淋疊得跟塔一樣高，記得來一支古早味芋頭。', transport: '計程車 8 分鐘', emoji: '🍦', attribution: 'Wei-Ting Wu' },
          { time: '15:08 - 16:30', location: '雲隅文旅 Check-in', category: '住宿', description: '台中西區精品旅店，房內有溜滑梯，放下行李拍張照再出門。', transport: '步行 5 分鐘', emoji: '🛏️', attribution: 'Yu-Chen Hsu' },
        ],
        hotels: [
          { name: '雲隅文旅', rating: 4.4, reviews: 3210, address: '台中市中區 · 步行 5 分鐘', attribution: 'Hotel Photographer' },
          { name: '漫旅 Stay 台中館', rating: 4.6, reviews: 5602, address: '台中市西區 · 步行 8 分鐘', attribution: '林雅婷' },
          { name: '日和旅宿 文森', rating: 4.5, reviews: 1284, address: '台中市西區 · 步行 12 分鐘', attribution: 'Kenji Sato' },
        ],
      },
      {
        day: 2,
        theme: '美術館 + 綠園道',
        weather: '⛅ 24°C',
        activities: [
          { time: '09:30 - 11:00', location: '國立臺灣美術館', category: '景點', description: '參觀常設展與當期特展，感受台中文化能量。', transport: '公車 15 分鐘', emoji: '🖼️', attribution: 'A-Chen Photography' },
          { time: '11:15 - 12:45', location: '勤美誠品綠園道', category: '美食', description: '書店與選品店逛街，安排輕食午餐。', transport: '步行 9 分鐘', emoji: '📚', attribution: '黃柏睿' },
          { time: '12:54 - 14:10', location: '一中街商圈', category: '美食', description: '甜點掃街，鮮芋仙、地瓜球、奶茶一次滿足。', transport: '計程車 12 分鐘', emoji: '🧋', attribution: 'Sarah Lin' },
        ],
        hotels: null,
      },
    ],
  },
};

async function generatePlan(userPrompt) {
  // Simulated 1.6s loading regardless of source
  const start = Date.now();
  let plan = null;

  // Try claude.complete first if available
  try {
    if (window.claude && window.claude.complete) {
      const sysPrompt = `You are a Taiwan travel itinerary planner. Output ONLY valid JSON, no markdown, no commentary. Schema:
{"title":"行程標題","summary":"一句話總結","days":[{"day":1,"theme":"當日主題","weather":"☀️ 26°C","activities":[{"time":"09:00 - 10:30","location":"景點名","category":"景點|美食|住宿","description":"一句推薦","transport":"步行 8 分鐘","emoji":"🌳"}],"hotels":[{"name":"飯店名","rating":4.5,"reviews":1234,"address":"地址"}]}]}
Categories: 景點|美食|住宿. Last day hotels=null. 2-3 days, 3-5 activities/day. Use Traditional Chinese. emoji is a single 1-char emoji.

Request: ${userPrompt}`;
      const raw = await window.claude.complete(sysPrompt);
      const jsonStart = raw.indexOf('{');
      const jsonEnd = raw.lastIndexOf('}');
      if (jsonStart >= 0 && jsonEnd > jsonStart) {
        plan = JSON.parse(raw.slice(jsonStart, jsonEnd + 1));
      }
    }
  } catch (e) {
    console.warn('claude.complete failed, falling back', e);
  }

  if (!plan) {
    // Canned fallback
    plan = JSON.parse(JSON.stringify(FALLBACK_PLANS.taichung));
    if (userPrompt) plan.title = userPrompt.length > 24 ? userPrompt.slice(0, 24) + '…' : userPrompt;
  }

  // Ensure minimum 1.4s perceived latency for nice loading anim
  const elapsed = Date.now() - start;
  if (elapsed < 1400) await new Promise((r) => setTimeout(r, 1400 - elapsed));
  return plan;
}

const TP = { SAMPLE_PROMPTS, FALLBACK_PLANS, generatePlan, photoFor, CUTE_PALETTES };
window.TP = TP;
