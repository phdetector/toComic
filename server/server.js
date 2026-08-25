const http = require('http')
const fs = require('fs')
const path = require('path')
const { randomUUID, createHmac, scryptSync, timingSafeEqual, createHash, randomBytes } = require('node:crypto')
const { DatabaseSync } = require('node:sqlite')

const DATA_DIR = '/var/lib/expo-suggestions'
const DATA_FILE = path.join(DATA_DIR, 'suggestions.jsonl')
const DB_FILE = path.join(DATA_DIR, 'suggestions.db')
const CLIENT_SECRETS_FILE = path.join(DATA_DIR, 'client-secrets.jsonl')
const MAX_TITLE = 200
const MAX_CONTENT = 5000
const MAX_BODY = 64 * 1024
const RATE_WINDOW_MS = 10 * 60 * 1000
const RATE_LIMIT = 10
const requestTimes = new Map()

setInterval(() => {
  const cutoff = Date.now() - RATE_WINDOW_MS
  for (const [ip, times] of requestTimes) {
    const recent = times.filter(time => time >= cutoff)
    if (recent.length === 0) requestTimes.delete(ip)
    else requestTimes.set(ip, recent)
  }
}, RATE_WINDOW_MS).unref()

function allowRequest(ip) {
  const now = Date.now()
  const recent = (requestTimes.get(ip) || []).filter(time => now - time < RATE_WINDOW_MS)
  if (recent.length >= RATE_LIMIT) return false
  recent.push(now)
  requestTimes.set(ip, recent)
  if (requestTimes.size > 10000) {
    let oldestKey = null
    let oldestTime = Infinity
    for (const [key, times] of requestTimes) {
      if (times.length > 0 && times[0] < oldestTime) {
        oldestTime = times[0]
        oldestKey = key
      }
    }
    if (oldestKey !== null) requestTimes.delete(oldestKey)
  }
  return true
}

function readClientSecrets() {
  let raw = ''
  try {
    raw = fs.readFileSync(CLIENT_SECRETS_FILE, 'utf8')
  } catch (error) {
    if (error && error.code === 'ENOENT') return new Map()
    throw error
  }
  const map = new Map()
  for (const line of raw.split('\n')) {
    if (!line.trim()) continue
    try {
      const parsed = JSON.parse(line)
      if (parsed.clientId && parsed.secret) map.set(parsed.clientId, parsed.secret)
    } catch (_error) { /* skip malformed line */ }
  }
  return map
}

function issueClientSecret(clientId) {
  const secrets = readClientSecrets()
  const existing = secrets.get(clientId)
  if (existing) return existing
  const secret = randomBytes(24).toString('hex')
  fs.mkdirSync(DATA_DIR, { recursive: true })
  fs.appendFileSync(CLIENT_SECRETS_FILE, JSON.stringify({ clientId, secret }) + '\n')
  return secret
}

function validClientSignature(clientId, signature) {
  const secrets = readClientSecrets()
  const secret = secrets.get(clientId)
  if (!secret || !signature) return false
  const expected = createHmac('sha256', secret).update(clientId).digest('hex')
  const provided = String(signature)
  if (provided.length !== expected.length) return false
  return timingSafeEqual(Buffer.from(expected), Buffer.from(provided))
}

function ipHash(ip) {
  return createHash('sha256').update(String(ip)).digest('hex').slice(0, 32)
}

const APP_MESSAGES_FILE = path.join(DATA_DIR, 'app-messages.json')
const ALLOWED_UPDATE_HOSTS = ['269278.xyz', 'www.269278.xyz']
const DEFAULT_APP_MESSAGES = {
  notices: [
    {
      id: 'notice-0001',
      title: '欢迎使用',
      body: '欢迎使用 Expo Planner, 长按项目卡片可以编辑或删除项目.',
    },
  ],
  update: null,
}

function sanitizeUpdate(update) {
  if (!update || typeof update !== 'object') return null
  const versionCode = Number(update.versionCode)
  if (!Number.isInteger(versionCode) || versionCode < 1 || versionCode > 99999) return null
  const url = String(update.url || '')
  if (!url.startsWith('https://')) return null
  let host = ''
  try {
    host = new URL(url).hostname.toLowerCase()
  } catch (_error) {
    return null
  }
  if (!ALLOWED_UPDATE_HOSTS.some(entry => host === entry || host.endsWith('.' + entry))) return null
  return {
    versionCode,
    versionName: String(update.versionName || '').slice(0, 40),
    title: String(update.title || '').slice(0, 200),
    body: String(update.body || '').slice(0, 2000),
    url,
  }
}

function sanitizeNotices(notices) {
  if (!Array.isArray(notices)) return []
  return notices.slice(0, 50).map(item => {
    const id = String(item && item.id || '').slice(0, 100)
    const title = String(item && item.title || '').slice(0, 200)
    const body = String(item && item.body || '').slice(0, 2000)
    return { id, title, body }
  }).filter(item => item.id && (item.title || item.body))
}

function readAppMessages() {
  try {
    const raw = fs.readFileSync(APP_MESSAGES_FILE, 'utf8')
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') return DEFAULT_APP_MESSAGES
    return {
      notices: sanitizeNotices(parsed.notices),
      update: sanitizeUpdate(parsed.update),
    }
  } catch (error) {
    if (!error || error.code !== 'ENOENT') console.error('failed to read app messages', error)
    return DEFAULT_APP_MESSAGES
  }
}

function send(res, code, body) {
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
  })
  res.end(JSON.stringify(body))
}

function readBody(req, callback) {
  let raw = ''
  let size = 0
  req.setEncoding('utf8')
  req.on('data', chunk => {
    size += Buffer.byteLength(chunk)
    if (size > MAX_BODY) {
      req.destroy()
      return
    }
    raw += chunk
  })
  req.on('end', () => callback(raw))
}

const db = new DatabaseSync(DB_FILE)
db.exec(`
  CREATE TABLE IF NOT EXISTS suggestions (
    id TEXT PRIMARY KEY,
    clientId TEXT NOT NULL,
    title TEXT NOT NULL,
    content TEXT NOT NULL,
    appVersion TEXT DEFAULT '',
    deviceModel TEXT DEFAULT '',
    androidVersion TEXT DEFAULT '',
    submittedAt INTEGER NOT NULL,
    receivedAt INTEGER NOT NULL,
    reply TEXT DEFAULT '',
    repliedAt INTEGER,
    processed INTEGER DEFAULT 0,
    favorite INTEGER DEFAULT 0,
    archived INTEGER DEFAULT 0,
    ipHash TEXT DEFAULT ''
  );
  CREATE INDEX IF NOT EXISTS idx_suggestions_client ON suggestions(clientId);
`)

function migrateLegacyJsonl() {
  let raw = ''
  try {
    raw = fs.readFileSync(DATA_FILE, 'utf8')
  } catch (error) {
    if (error && error.code === 'ENOENT') return
    throw error
  }
  const insert = db.prepare(`
    INSERT OR IGNORE INTO suggestions
      (id, clientId, title, content, appVersion, deviceModel, androidVersion,
       submittedAt, receivedAt, reply, repliedAt, processed, favorite, archived, ipHash)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
  `)
  for (const line of raw.split('\n')) {
    if (!line.trim()) continue
    try {
      const r = JSON.parse(line)
      insert.run(
        String(r.id || randomUUID()), String(r.clientId || ''), String(r.title || ''), String(r.content || ''),
        String(r.appVersion || ''), String(r.deviceModel || ''), String(r.androidVersion || ''),
        Number(r.submittedAt) || Date.now(), Number(r.receivedAt) || Date.now(),
        String(r.reply || ''), r.repliedAt == null ? null : Number(r.repliedAt),
        r.processed ? 1 : 0, r.favorite ? 1 : 0, r.archived ? 1 : 0, String(r.ipHash || '')
      )
    } catch (_error) { /* skip malformed legacy line */ }
  }
  fs.renameSync(DATA_FILE, DATA_FILE + '.legacy')
}

migrateLegacyJsonl()

function readSuggestions() {
  const rows = db.prepare('SELECT * FROM suggestions').all()
  return rows.map(row => ({
    id: row.id,
    clientId: row.clientId,
    title: row.title,
    content: row.content,
    appVersion: row.appVersion,
    deviceModel: row.deviceModel,
    androidVersion: row.androidVersion,
    submittedAt: Number(row.submittedAt),
    receivedAt: Number(row.receivedAt),
    reply: row.reply,
    repliedAt: row.repliedAt == null ? null : Number(row.repliedAt),
    processed: Boolean(row.processed),
    favorite: Boolean(row.favorite),
    archived: Boolean(row.archived),
    ipHash: row.ipHash,
  }))
}

function appendSuggestion(record) {
  fs.mkdirSync(DATA_DIR, { recursive: true })
  db.prepare(`
    INSERT INTO suggestions
      (id, clientId, title, content, appVersion, deviceModel, androidVersion,
       submittedAt, receivedAt, reply, repliedAt, processed, favorite, archived, ipHash)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, '', NULL, 0, 0, 0, ?)
  `).run(
    record.id, record.clientId, record.title, record.content,
    record.appVersion, record.deviceModel, record.androidVersion,
    record.submittedAt, record.receivedAt, record.ipHash
  )
}

const ADMIN_PORT = 37795
const ADMIN_CREDENTIALS_FILE = path.join(DATA_DIR, 'admin-credentials.json')
const ADMIN_TOKEN_TTL_MS = 30 * 60 * 1000
const ADMIN_TOKEN_RENEW_MS = 5 * 60 * 1000
const ADMIN_LOGIN_MAX_FAILURES = 5
const ADMIN_LOGIN_LOCK_MS = 15 * 60 * 1000
const adminTokens = new Map()
const adminLoginFailures = new Map()

function adminSecret() {
  const secret = process.env.EXPO_ADMIN_SECRET
  if (!secret) {
    console.error('EXPO_ADMIN_SECRET is required')
    process.exit(1)
  }
  return secret
}

function readAdminCredentials() {
  const raw = fs.readFileSync(ADMIN_CREDENTIALS_FILE, 'utf8')
  const parsed = JSON.parse(raw)
  return { username: parsed.username, hash: parsed.hash, salt: parsed.salt }
}

function issueAdminToken(username) {
  const expiry = Date.now() + ADMIN_TOKEN_TTL_MS
  const token = createHmac('sha256', adminSecret()).update(`${username}:${expiry}`).digest('hex')
  adminTokens.set(token, { username, expiry })
  return token
}

function verifyAdmin(req, res) {
  const header = String(req.headers.authorization || '')
  if (!header.startsWith('Bearer ')) return false
  const token = header.slice(7)
  const state = adminTokens.get(token)
  if (!state) return false
  if (Date.now() > state.expiry) {
    adminTokens.delete(token)
    return false
  }
  if (state.expiry - Date.now() < ADMIN_TOKEN_RENEW_MS) {
    adminTokens.delete(token)
    res.setHeader('X-Expo-Token', issueAdminToken(state.username))
  }
  return true
}

function loginAttempts(ip) {
  const state = adminLoginFailures.get(ip)
  if (!state) return { count: 0, lockedUntil: 0 }
  return state
}

function recordLoginFailure(ip) {
  const state = adminLoginFailures.get(ip) || { count: 0, lockedUntil: 0 }
  state.count += 1
  if (state.count >= ADMIN_LOGIN_MAX_FAILURES) {
    state.lockedUntil = Date.now() + ADMIN_LOGIN_LOCK_MS
    state.count = 0
  }
  adminLoginFailures.set(ip, state)
}

function clearLoginFailures(ip) {
  adminLoginFailures.delete(ip)
}

function updateSuggestion(id, patch) {
  const sets = []
  const values = []
  for (const key of ['reply', 'repliedAt', 'processed', 'favorite', 'archived']) {
    if (Object.prototype.hasOwnProperty.call(patch, key)) {
      sets.push(`${key} = ?`)
      values.push(key === 'processed' || key === 'favorite' || key === 'archived' ? (patch[key] ? 1 : 0) : patch[key])
    }
  }
  if (sets.length === 0) return false
  values.push(id)
  const result = db.prepare(`UPDATE suggestions SET ${sets.join(', ')} WHERE id = ?`).run(...values)
  return result.changes > 0
}

const server = http.createServer((req, res) => {
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
    })
    res.end()
    return
  }
  const requestUrl = new URL(req.url || '/', 'http://127.0.0.1')
  const pathname = requestUrl.pathname

  if (req.method === 'GET' && pathname === '/api/app/messages') {
    send(res, 200, { ok: true, ...readAppMessages() })
    return
  }

  if (req.method === 'GET' && pathname === '/api/suggestions') {
    const clientId = String(requestUrl.searchParams.get('clientId') || '').trim().slice(0, 100)
    const signature = String(requestUrl.searchParams.get('sig') || '')
    if (clientId.length === 0) {
      send(res, 400, { ok: false, error: 'clientId is required' })
      return
    }
    if (!validClientSignature(clientId, signature)) {
      send(res, 401, { ok: false, error: 'unauthorized' })
      return
    }
    try {
      const suggestions = readSuggestions()
        .filter(item => item.clientId === clientId)
        .sort((left, right) => (right.submittedAt || 0) - (left.submittedAt || 0))
        .slice(0, 200)
      send(res, 200, { ok: true, suggestions })
    } catch (error) {
      console.error('failed to read suggestions', error)
      send(res, 500, { ok: false, error: 'storage failed' })
    }
    return
  }

  if (req.method !== 'POST' || pathname !== '/api/suggestions') {
    send(res, 404, { ok: false, error: 'not found' })
    return
  }
  const clientIp = String(req.headers['x-forwarded-for'] || req.socket.remoteAddress || '')
    .split(',')[0]
    .trim()
    .slice(0, 100)
  if (!allowRequest(clientIp)) {
    send(res, 429, { ok: false, error: 'too many requests' })
    return
  }
  readBody(req, raw => {
    let data
    try {
      data = JSON.parse(raw)
    } catch (_error) {
      send(res, 400, { ok: false, error: 'invalid json' })
      return
    }
    const title = String(data && data.title || '').trim()
    const content = String(data && data.content || '').trim()
    if (title.length === 0 || content.length === 0) {
      send(res, 400, { ok: false, error: 'title and content are required' })
      return
    }
    if (title.length > MAX_TITLE || content.length > MAX_CONTENT) {
      send(res, 422, { ok: false, error: 'title or content is too long' })
      return
    }
    const clientId = String(data && data.clientId || '').trim().slice(0, 100)
    if (clientId.length === 0) {
      send(res, 400, { ok: false, error: 'clientId is required' })
      return
    }
    const clientSecret = issueClientSecret(clientId)
    const record = {
      id: randomUUID(),
      clientId,
      title,
      content,
      appVersion: String(data && data.appVersion || '').slice(0, 50),
      deviceModel: String(data && data.deviceModel || '').slice(0, 100),
      androidVersion: String(data && data.androidVersion || '').slice(0, 50),
      submittedAt: Number(data && data.submittedAt) || Date.now(),
      receivedAt: Date.now(),
      reply: '',
      repliedAt: null,
      ipHash: ipHash(clientIp),
    }
    try {
      appendSuggestion(record)
      send(res, 200, { ok: true, suggestion: record, secret: clientSecret })
    } catch (error) {
      console.error('failed to store suggestion', error)
      send(res, 500, { ok: false, error: 'storage failed' })
    }
  })
})

server.listen(7978, '127.0.0.1', () => {
  console.log('expo suggestions listening on 127.0.0.1:7978')
})

const adminServer = http.createServer((req, res) => {
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type, Authorization',
    })
    res.end()
    return
  }
  const requestUrl = new URL(req.url || '/', 'http://127.0.0.1')
  const pathname = requestUrl.pathname

  if (req.method === 'POST' && pathname === '/admin/login') {
    readBody(req, raw => {
      const ip = String(req.socket.remoteAddress || '').slice(0, 100)
      const attempts = loginAttempts(ip)
      if (attempts.lockedUntil > Date.now()) {
        send(res, 429, { ok: false, error: 'too many attempts' })
        return
      }
      let data
      try {
        data = JSON.parse(raw)
      } catch (_error) {
        send(res, 400, { ok: false, error: 'invalid json' })
        return
      }
      try {
        const credentials = readAdminCredentials()
        const username = String(data && data.username || '')
        const password = String(data && data.password || '')
        const candidate = scryptSync(password, credentials.salt, 64)
        const expected = Buffer.from(credentials.hash, 'hex')
        if (username !== credentials.username || candidate.length !== expected.length
          || !timingSafeEqual(candidate, expected)) {
          recordLoginFailure(ip)
          send(res, 401, { ok: false, error: 'invalid credentials' })
          return
        }
        clearLoginFailures(ip)
        send(res, 200, { ok: true, token: issueAdminToken(username) })
      } catch (error) {
        console.error('admin login failed', error)
        send(res, 500, { ok: false, error: 'login failed' })
      }
    })
    return
  }

  if (!verifyAdmin(req, res)) {
    send(res, 401, { ok: false, error: 'unauthorized' })
    return
  }

  if (req.method === 'GET' && pathname === '/admin/suggestions') {
    try {
      const suggestions = readSuggestions()
        .sort((left, right) => {
          const leftDone = Boolean(left.reply) || Boolean(left.processed)
          const rightDone = Boolean(right.reply) || Boolean(right.processed)
          if (leftDone !== rightDone) return leftDone ? 1 : -1
          return (left.submittedAt || 0) - (right.submittedAt || 0)
        })
      send(res, 200, { ok: true, suggestions })
    } catch (error) {
      console.error('failed to list suggestions', error)
      send(res, 500, { ok: false, error: 'storage failed' })
    }
    return
  }

  if (req.method === 'POST' && ['/admin/reply', '/admin/favorite', '/admin/archive'].includes(pathname)) {
    readBody(req, raw => {
      let data
      try {
        data = JSON.parse(raw)
      } catch (_error) {
        send(res, 400, { ok: false, error: 'invalid json' })
        return
      }
      const id = String(data && data.id || '')
      if (!id) {
        send(res, 400, { ok: false, error: 'id is required' })
        return
      }
      try {
        let patch
        if (pathname === '/admin/reply') {
          const reply = String(data && data.reply || '').trim().slice(0, 5000)
          patch = { reply, repliedAt: reply ? Date.now() : null, processed: reply.length > 0 }
        } else if (pathname === '/admin/favorite') {
          patch = { favorite: Boolean(data && data.favorite) }
        } else {
          patch = { archived: Boolean(data && data.archived) }
        }
        const updated = updateSuggestion(id, patch)
        send(res, updated ? 200 : 404, updated ? { ok: true } : { ok: false, error: 'not found' })
      } catch (error) {
        console.error('failed to update suggestion', error)
        send(res, 500, { ok: false, error: 'storage failed' })
      }
    })
    return
  }

  send(res, 404, { ok: false, error: 'not found' })
})

if (!process.env.EXPO_ADMIN_SECRET) {
  console.error('EXPO_ADMIN_SECRET is required')
  process.exit(1)
}

adminServer.listen(ADMIN_PORT, '127.0.0.1', () => {
  console.log('expo admin listening on 127.0.0.1:' + ADMIN_PORT)
})
