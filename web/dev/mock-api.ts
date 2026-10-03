// Fake Mini App API with sample data, for `mise run web:preview`; vite proxies /api here (vite.config.ts).
// State lives in memory and resets on restart.
const groups = [
  { id: -1001, title: 'Limassol Climbers', hasForm: true, retentionDays: 90, active: true },
  { id: -1002, title: 'Nicosia Book Club', hasForm: false, retentionDays: 90, active: true },
  { id: -1003, title: 'Paphos Sailing', hasForm: true, retentionDays: 90, active: false },
]
let version = 3
let schema: any = { welcome: 'Hi! A few questions before you join', fields: [
  { id: 'a1', type: 'radio', prompt: 'Where do you live?', options: ['Limassol', 'Nicosia'], other: true },
  { id: 'a2', type: 'radio', prompt: 'Agree to the rules?', options: ['Yes', 'No'] },
  { id: 'a3', type: 'multi', prompt: 'Interests?', options: ['Bouldering', 'Lead', 'Outdoor trips'], min: 1, max: 3 },
  { id: 'a4', type: 'text', prompt: 'About you', maxLen: 300 },
  { id: 'a5', type: 'int', prompt: 'Age?', min: 18, max: 120 },
  { id: 'a6', type: 'link', prompt: 'LinkedIn?', required: false },
  { id: 'a7', type: 'consent', prompt: 'Privacy terms… Do you agree?' },
] }
const now = Date.now(), h = 3600e3
let rows = [
  { id: 6, userId: 101, name: 'Anna Kova', username: 'annak', status: 'PENDING', createdAt: new Date(now - h).toISOString(), decidedBy: null },
  { id: 5, userId: 102, name: 'Marios Petrou', username: null, status: 'PENDING', createdAt: new Date(now - 3 * h).toISOString(), decidedBy: null },
  { id: 4, userId: 103, name: 'Sofia Lambrou', username: 'sofl', status: 'PENDING', createdAt: new Date(now - 26 * h).toISOString(), decidedBy: null },
  { id: 3, userId: 104, name: 'Dmitri T.', username: 'dmt', status: 'APPROVED', createdAt: new Date(now - 50 * h).toISOString(), decidedBy: 1 },
  { id: 2, userId: 105, name: 'Elena H.', username: null, status: 'REJECTED', createdAt: new Date(now - 74 * h).toISOString(), decidedBy: 1 },
  { id: 1, userId: 106, name: 'George N.', username: null, status: 'EXPIRED', createdAt: new Date(now - 240 * h).toISOString(), decidedBy: null },
]
const answers = [
  { fieldId: 'a1', prompt: 'Where do you live?', value: 'Limassol' },
  { fieldId: 'a2', prompt: 'Agree to the rules?', value: 'Yes' },
  { fieldId: 'a3', prompt: 'Interests?', value: 'Bouldering, Outdoor trips' },
  { fieldId: 'a4', prompt: 'About you', value: 'Climbing for two years, mostly at the Limassol wall. Looking for weekend partners.' },
  { fieldId: 'a5', prompt: 'Age?', value: '29' },
  { fieldId: 'a6', prompt: 'LinkedIn?', value: '' },
]
const slow = () => new Promise((r) => setTimeout(r, 400))
Bun.serve({ port: 8080, hostname: '127.0.0.1', async fetch(req) {
  const u = new URL(req.url), p = u.pathname, m = req.method
  if (p === '/api/groups') return Response.json(groups)
  if (p.endsWith('/form') && m === 'GET') return Response.json({ version, schema })
  if (p.endsWith('/form') && m === 'PUT') { await slow(); const b: any = await req.json(); schema = b.schema; return Response.json({ version: ++version }) }
  const sid = p.match(/submissions\/(\d+)$/)?.[1]
  if (sid && m === 'GET') { const row = rows.find((r) => r.id === +sid)!; return Response.json({ row, partial: false, answers }) }
  if (sid && m === 'DELETE') { rows = rows.filter((r) => r.id !== +sid); return new Response(null, { status: 204 }) }
  if (p.endsWith('/submissions')) { const s = u.searchParams.get('status'); return Response.json(s ? rows.filter((r) => r.status === s) : rows) }
  if (p.endsWith('/export')) { await slow(); return new Response(null, { status: 202 }) }
  if (p.endsWith('/settings')) { await slow(); return new Response(null, { status: 204 }) }
  return new Response('not found', { status: 404 })
} })
console.log('mock API on :8080')
