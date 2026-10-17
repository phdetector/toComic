const http = require('http')
const fs = require('fs')
const path = require('path')
const { randomUUID, createHmac, scryptSync, timingSafeEqual, randomBytes } = require('node:crypto')
const { DatabaseSync } = require('node:sqlite')

const ROOT = __dirname
const DATA_DIR = path.join(ROOT, 'data')
const PUBLIC_DIR = path.join(ROOT, 'public')

const PORT = Number(process.env.WEB_PORT || 11451)
const HOST = String(process.env.WEB_HOST || '0.0.0.0')
const DB_FILE = process.env.WEB_DB ? path.resolve(process.env.WEB_DB) : path.join(DATA_DIR, 'trip.db')
const ACCOUNTS_FILE = path.join(path.dirname(DB_FILE), 'accounts.txt')
const SECRET_FILE = path.join(path.dirname(DB_FILE), 'session-secret')
const ACCOUNT_PREFIX = String(process.env.WEB_ACCOUNT_PREFIX || 'user')
const ACCOUNT_COUNT = 10
const SESSION_DAYS = Number(process.env.WEB_SESSION_DAYS || 7)

const MAX_BODY = 64 * 1024
const MAX_LOCATION = 100
const MAX_DAY_NOTE = 500
const MAX_DETAIL = 200
const MAX_STATE_NOTE = 500
const LOGIN_MAX_FAILURES = Number(process.env.WEB_LOGIN_MAX_FAILURES || 5)
const LOGIN_LOCK_MS = Number(process.env.WEB_LOGIN_LOCK_MS || 15 * 60 * 1000)
const LOGIN_HOURLY_MAX = Number(process.env.WEB_LOGIN_HOURLY_MAX || 10)
const LOGIN_HOURLY_WINDOW_MS = Number(process.env.WEB_LOGIN_HOURLY_WINDOW_MS || 60 * 60 * 1000)

const loginFailures = new Map()
const loginHourlyFailures = new Map()

setInterval(() => {
  const now = Date.now()
  for (const [ip, times] of loginHourlyFailures) {
    const recent = times.filter(time => now - time < LOGIN_HOURLY_WINDOW_MS)
    if (recent.length) loginHourlyFailures.set(ip, recent)
    else loginHourlyFailures.delete(ip)
  }
}, 10 * 60 * 1000).unref()

fs.mkdirSync(path.dirname(DB_FILE), { recursive: true })

function sessionSecret() {
  if (process.env.WEB_SESSION_SECRET) return process.env.WEB_SESSION_SECRET
  try {
    const existing = fs.readFileSync(SECRET_FILE, 'utf8').trim()
    if (existing) return existing
  } catch (error) {
    if (!error || error.code !== 'ENOENT') throw error
  }
  const secret = randomBytes(32).toString('hex')
  fs.writeFileSync(SECRET_FILE, secret + '\n', { mode: 0o600 })
  return secret
}

const SESSION_SECRET = sessionSecret()

const db = new DatabaseSync(DB_FILE)
db.exec('PRAGMA journal_mode = WAL')
db.exec('PRAGMA foreign_keys = ON')
db.exec(`
  CREATE TABLE IF NOT EXISTS users (
    username   TEXT PRIMARY KEY,
    pass_hash  TEXT NOT NULL,
    pass_salt  TEXT NOT NULL,
    created_at INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS days (
    id         TEXT PRIMARY KEY,
    date       TEXT NOT NULL,
    location   TEXT NOT NULL,
    note       TEXT NOT NULL DEFAULT '',
    created_by TEXT NOT NULL,
    created_at INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS activities (
    id         TEXT PRIMARY KEY,
    day_id     TEXT NOT NULL REFERENCES days(id) ON DELETE CASCADE,
    location   TEXT NOT NULL,
    start_time TEXT NOT NULL,
    end_time   TEXT NOT NULL,
    detail     TEXT NOT NULL DEFAULT '',
    created_by TEXT NOT NULL,
    created_at INTEGER NOT NULL
  );
  CREATE TABLE IF NOT EXISTS activity_state (
    activity_id TEXT NOT NULL REFERENCES activities(id) ON DELETE CASCADE,
    username    TEXT NOT NULL REFERENCES users(username) ON DELETE CASCADE,
    note        TEXT NOT NULL DEFAULT '',
    favorite    INTEGER NOT NULL DEFAULT 0,
    completed   INTEGER NOT NULL DEFAULT 0,
    updated_at  INTEGER NOT NULL,
    PRIMARY KEY (activity_id, username)
  );
  CREATE INDEX IF NOT EXISTS idx_activities_day ON activities(day_id);
  CREATE INDEX IF NOT EXISTS idx_state_user ON activity_state(username);
`)

// ---------- accounts ----------

const PASSWORD_ALPHABET = 'abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789'

function randomPassword(length = 16) {
  const bytes = randomBytes(length)
  let out = ''
  for (let i = 0; i < length; i += 1) out += PASSWORD_ALPHABET[bytes[i] % PASSWORD_ALPHABET.length]
  return out
}

function accountName(index) {
  return ACCOUNT_PREFIX + String(index).padStart(2, '0')
}

function insertUser(username, password) {
  const salt = randomBytes(16).toString('hex')
  const hash = scryptSync(password, salt, 64).toString('hex')
  db.prepare('INSERT OR REPLACE INTO users (username, pass_hash, pass_salt, created_at) VALUES (?, ?, ?, ?)')
    .run(username, hash, salt, Date.now())
}

function userCount() {
  return db.prepare('SELECT COUNT(*) AS c FROM users').get().c
}

function generateAccounts({ force = false, prefix = ACCOUNT_PREFIX, writeFile = true } = {}) {
  if (force) db.prepare('DELETE FROM users').run()
  const pairs = []
  for (let i = 1; i <= ACCOUNT_COUNT; i += 1) {
    const username = prefix + String(i).padStart(2, '0')
    const password = randomPassword()
    insertUser(username, password)
    pairs.push({ username, password })
  }
  if (writeFile) {
    const header = '# 首次生成的账号，请立即抄走并删除本文件。\n# 生成时间: ' + new Date().toISOString() + '\n'
    const body = pairs.map(p => `${p.username}\t${p.password}`).join('\n') + '\n'
    fs.writeFileSync(ACCOUNTS_FILE, header + body)
  }
  return pairs
}

function setPassword(username, password) {
  const salt = randomBytes(16).toString('hex')
  const hash = scryptSync(password, salt, 64).toString('hex')
  const result = db.prepare('UPDATE users SET pass_hash = ?, pass_salt = ? WHERE username = ?')
    .run(hash, salt, username)
  return result.changes > 0
}

if (userCount() === 0) {
  const pairs = generateAccounts()
  console.log('='.repeat(64))
  console.log('首次启动：已生成 ' + pairs.length + ' 组账号（请立即保存，随后删除 data/accounts.txt）')
  for (const p of pairs) console.log('  ' + p.username + '\t' + p.password)
  console.log('='.repeat(64))
}

// ---------- sessions ----------

function signSession(username, expiry) {
  const payload = `${username}:${expiry}`
  const signature = createHmac('sha256', SESSION_SECRET).update(payload).digest('hex')
  return `${payload}:${signature}`
}

function verifySession(token) {
  if (!token) return null
  const index = token.lastIndexOf(':')
  if (index <= 0) return null
  const payload = token.slice(0, index)
  const signature = String(token.slice(index + 1))
  const expected = createHmac('sha256', SESSION_SECRET).update(payload).digest('hex')
  if (signature.length !== expected.length) return null
  if (!timingSafeEqual(Buffer.from(signature), Buffer.from(expected))) return null
  const separator = payload.lastIndexOf(':')
  if (separator <= 0) return null
  const username = payload.slice(0, separator)
  const expiry = Number(payload.slice(separator + 1))
  if (!Number.isFinite(expiry) || Date.now() > expiry) return null
  if (!db.prepare('SELECT 1 AS ok FROM users WHERE username = ?').get(username)) return null
  return username
}

function parseCookies(header) {
  const out = new Map()
  for (const part of String(header || '').split(';')) {
    const index = part.indexOf('=')
    if (index < 0) continue
    out.set(part.slice(0, index).trim(), part.slice(index + 1).trim())
  }
  return out
}

function currentUser(req) {
  return verifySession(parseCookies(req.headers.cookie).get('sid'))
}

function sessionCookie(token, maxAgeSeconds) {
  return `sid=${token}; HttpOnly; SameSite=Lax; Path=/; Max-Age=${maxAgeSeconds}`
}

// ---------- helpers ----------

function send(res, code, body, extraHeaders = {}) {
  const payload = JSON.stringify(body)
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
    'Referrer-Policy': 'no-referrer',
    ...extraHeaders,
  })
  res.end(payload)
}

function fail(res, code, error) {
  send(res, code, { ok: false, error })
}

function readBody(req, res) {
  return new Promise((resolve, reject) => {
    let raw = ''
    let size = 0
    req.setEncoding('utf8')
    req.on('data', chunk => {
      size += Buffer.byteLength(chunk)
      if (size > MAX_BODY) {
        fail(res, 413, '请求体过大')
        req.destroy()
        return
      }
      raw += chunk
    })
    req.on('end', () => {
      if (res.writableEnded) return
      if (!raw) return resolve({})
      try {
        resolve(JSON.parse(raw))
      } catch (_error) {
        reject(new Error('invalid json'))
      }
    })
    req.on('error', reject)
  })
}

function sameOrigin(req) {
  const origin = req.headers.origin
  if (origin) {
    try {
      return new URL(origin).host === req.headers.host
    } catch (_error) {
      return false
    }
  }
  const site = req.headers['sec-fetch-site']
  if (site && site !== 'same-origin' && site !== 'none') return false
  return true
}

function clientIp(req) {
  return String(req.socket.remoteAddress || '').slice(0, 100)
}

function loginState(ip) {
  return loginFailures.get(ip) || { count: 0, lockedUntil: 0 }
}

function recordLoginFailure(ip) {
  const now = Date.now()
  const state = loginState(ip)
  state.count += 1
  if (state.count >= LOGIN_MAX_FAILURES) {
    state.lockedUntil = now + LOGIN_LOCK_MS
    state.count = 0
  }
  loginFailures.set(ip, state)
  recordHourlyFailure(ip, now)
}

// 同一 IP 滚动 1 小时内的失败尝试时间戳。
// 只统计失败；成功登录不清空（否则攻击者拿一个已知账号登录即可重置自己的爆破预算）。
function hourlyFailureTimes(ip, now) {
  const times = (loginHourlyFailures.get(ip) || []).filter(time => now - time < LOGIN_HOURLY_WINDOW_MS)
  if (times.length) loginHourlyFailures.set(ip, times)
  else loginHourlyFailures.delete(ip)
  return times
}

function recordHourlyFailure(ip, now) {
  const times = hourlyFailureTimes(ip, now)
  times.push(now)
  loginHourlyFailures.set(ip, times)
  if (loginHourlyFailures.size > 10000) {
    let oldestKey = null
    let oldestTime = Infinity
    for (const [key, list] of loginHourlyFailures) {
      if (list.length && list[0] < oldestTime) {
        oldestTime = list[0]
        oldestKey = key
      }
    }
    if (oldestKey !== null) loginHourlyFailures.delete(oldestKey)
  }
}

function text(value, max) {
  return String(value == null ? '' : value).replace(/\s+/g, ' ').trim().slice(0, max)
}

function multiline(value, max) {
  return String(value == null ? '' : value).replace(/\r\n/g, '\n').trim().slice(0, max)
}

function isIsoDate(value) {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return false
  const [y, m, d] = value.split('-').map(Number)
  const date = new Date(Date.UTC(y, m - 1, d))
  return date.getUTCFullYear() === y && date.getUTCMonth() === m - 1 && date.getUTCDate() === d
}

function timeToMinutes(value) {
  const match = /^(\d{1,2}):(\d{2})$/.exec(String(value || '').trim())
  if (!match) return null
  const hours = Number(match[1])
  const minutes = Number(match[2])
  if (minutes > 59) return null
  if (hours > 24) return null
  if (hours === 24 && minutes !== 0) return null
  return hours * 60 + minutes
}

function normalizeTime(value, allow2400) {
  const match = /^(\d{1,2}):(\d{2})$/.exec(String(value || '').trim())
  if (!match) return null
  const hours = Number(match[1])
  const minutes = Number(match[2])
  if (minutes > 59) return null
  if (hours === 24) {
    if (!allow2400 || minutes !== 0) return null
    return '24:00'
  }
  if (hours > 23) return null
  return String(hours).padStart(2, '0') + ':' + String(minutes).padStart(2, '0')
}

// ---------- data access ----------

function loadDays(username) {
  const days = db.prepare('SELECT * FROM days ORDER BY date ASC, created_at ASC').all()
  const activities = db.prepare(`
    SELECT a.*, COALESCE(s.note, '') AS my_note, COALESCE(s.favorite, 0) AS my_favorite,
           COALESCE(s.completed, 0) AS my_completed
    FROM activities a
    LEFT JOIN activity_state s ON s.activity_id = a.id AND s.username = ?
    ORDER BY a.start_time ASC, a.location ASC, a.created_at ASC
  `).all(username)
  const byDay = new Map()
  for (const activity of activities) {
    if (!byDay.has(activity.day_id)) byDay.set(activity.day_id, [])
    byDay.get(activity.day_id).push({
      id: activity.id,
      location: activity.location,
      startTime: activity.start_time,
      endTime: activity.end_time,
      detail: activity.detail,
      createdBy: activity.created_by,
      state: {
        note: activity.my_note,
        favorite: Boolean(activity.my_favorite),
        completed: Boolean(activity.my_completed),
      },
    })
  }
  return days.map(day => ({
    id: day.id,
    date: day.date,
    location: day.location,
    note: day.note,
    createdBy: day.created_by,
    activities: byDay.get(day.id) || [],
  }))
}

function findActivity(id) {
  return db.prepare('SELECT * FROM activities WHERE id = ?').get(id) || null
}

function dayExists(id) {
  return Boolean(db.prepare('SELECT 1 AS ok FROM days WHERE id = ?').get(id))
}

// ---------- static ----------

const STATIC_FILES = new Map([
  ['/', ['index.html', 'text/html; charset=utf-8']],
  ['/index.html', ['index.html', 'text/html; charset=utf-8']],
  ['/style.css', ['style.css', 'text/css; charset=utf-8']],
  ['/app.js', ['app.js', 'text/javascript; charset=utf-8']],
  ['/favicon.svg', ['favicon.svg', 'image/svg+xml']],
])

function serveStatic(res, pathname) {
  const entry = STATIC_FILES.get(pathname)
  if (!entry) return false
  const [file, type] = entry
  try {
    const content = fs.readFileSync(path.join(PUBLIC_DIR, file))
    res.writeHead(200, {
      'Content-Type': type,
      'Cache-Control': 'no-store',
      'X-Content-Type-Options': 'nosniff',
      'X-Frame-Options': 'DENY',
      'Referrer-Policy': 'no-referrer',
    })
    res.end(content)
  } catch (_error) {
    fail(res, 500, '静态资源缺失')
  }
  return true
}

// ---------- routes ----------

async function handleLogin(req, res) {
  const ip = clientIp(req)
  const now = Date.now()
  const state = loginState(ip)
  if (state.lockedUntil > now) {
    const minutes = Math.ceil((state.lockedUntil - now) / 60000)
    return fail(res, 429, `尝试次数过多，请 ${minutes} 分钟后再试`)
  }
  const recentFailures = hourlyFailureTimes(ip, now)
  if (recentFailures.length >= LOGIN_HOURLY_MAX) {
    const minutes = Math.max(1, Math.ceil((recentFailures[0] + LOGIN_HOURLY_WINDOW_MS - now) / 60000))
    return fail(res, 429, `同一 IP 每小时最多 ${LOGIN_HOURLY_MAX} 次失败尝试，请 ${minutes} 分钟后再试`)
  }
  let body
  try {
    body = await readBody(req, res)
  } catch (_error) {
    return fail(res, 400, '请求格式错误')
  }
  if (res.writableEnded) return
  const username = text(body.username, 64)
  const password = String(body.password == null ? '' : body.password)
  const row = db.prepare('SELECT * FROM users WHERE username = ?').get(username)
  const salt = row ? row.pass_salt : 'invalid'
  const expected = row ? Buffer.from(row.pass_hash, 'hex') : Buffer.alloc(64)
  const candidate = scryptSync(password, salt, 64)
  const matched = Boolean(row) && candidate.length === expected.length && timingSafeEqual(candidate, expected)
  if (!matched) {
    recordLoginFailure(ip)
    return fail(res, 401, '用户名或密码错误')
  }
  // 成功只清空「连续失败」计数，不清空每小时失败窗口（见 recordHourlyFailure 注释）
  loginFailures.delete(ip)
  const maxAge = Math.round(SESSION_DAYS * 24 * 60 * 60)
  const token = signSession(username, Date.now() + maxAge * 1000)
  send(res, 200, { ok: true, username }, { 'Set-Cookie': sessionCookie(token, maxAge) })
}

function createDay(username, body) {
  const date = text(body.date, 10)
  const location = text(body.location, MAX_LOCATION)
  const note = multiline(body.note, MAX_DAY_NOTE)
  if (!isIsoDate(date)) return { error: '日期格式不正确' }
  if (!location) return { error: '地点不能为空' }
  const id = randomUUID()
  db.prepare('INSERT INTO days (id, date, location, note, created_by, created_at) VALUES (?, ?, ?, ?, ?, ?)')
    .run(id, date, location, note, username, Date.now())
  return { day: { id, date, location, note, createdBy: username, activities: [] } }
}

function createActivity(username, dayId, body) {
  const location = text(body.location, MAX_LOCATION)
  const start = normalizeTime(body.startTime, false)
  const end = normalizeTime(body.endTime, true)
  const detail = multiline(body.detail, MAX_DETAIL)
  if (!location) return { error: '地点不能为空' }
  if (!start || !end) return { error: '时间格式不正确（HH:mm）' }
  if (timeToMinutes(start) > timeToMinutes(end)) return { error: '开始时间不能晚于结束时间' }
  const id = randomUUID()
  db.prepare(`
    INSERT INTO activities (id, day_id, location, start_time, end_time, detail, created_by, created_at)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
  `).run(id, dayId, location, start, end, detail, username, Date.now())
  return {
    activity: {
      id, location, startTime: start, endTime: end, detail, createdBy: username,
      state: { note: '', favorite: false, completed: false },
    },
  }
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url || '/', 'http://127.0.0.1')
  const pathname = url.pathname
  const method = req.method || 'GET'

  if (method === 'OPTIONS') {
    res.writeHead(204)
    return res.end()
  }

  if (pathname === '/api/health' && method === 'GET') {
    return send(res, 200, { ok: true, ts: Date.now() })
  }

  if (pathname.startsWith('/api/')) {
    if (method !== 'GET' && !sameOrigin(req)) return fail(res, 403, '来源校验失败')

    if (pathname === '/api/login' && method === 'POST') return handleLogin(req, res)

    const username = currentUser(req)
    if (!username) return fail(res, 401, '未登录')

    if (pathname === '/api/logout' && method === 'POST') {
      return send(res, 200, { ok: true }, { 'Set-Cookie': sessionCookie('', 0) })
    }

    if (pathname === '/api/me' && method === 'GET') {
      return send(res, 200, { ok: true, username })
    }

    if (pathname === '/api/days' && method === 'GET') {
      return send(res, 200, { ok: true, days: loadDays(username) })
    }

    if (pathname === '/api/days' && method === 'POST') {
      let body
      try {
        body = await readBody(req, res)
      } catch (_error) {
        return fail(res, 400, '请求格式错误')
      }
      if (res.writableEnded) return
      const result = createDay(username, body)
      if (result.error) return fail(res, 400, result.error)
      return send(res, 200, { ok: true, day: result.day })
    }

    const activityCreateMatch = /^\/api\/days\/([^/]+)\/activities$/.exec(pathname)
    if (activityCreateMatch && method === 'POST') {
      const dayId = decodeURIComponent(activityCreateMatch[1])
      if (!dayExists(dayId)) return fail(res, 404, '日期不存在')
      let body
      try {
        body = await readBody(req, res)
      } catch (_error) {
        return fail(res, 400, '请求格式错误')
      }
      if (res.writableEnded) return
      const result = createActivity(username, dayId, body)
      if (result.error) return fail(res, 400, result.error)
      return send(res, 200, { ok: true, activity: result.activity })
    }

    const dayMatch = /^\/api\/days\/([^/]+)$/.exec(pathname)
    if (dayMatch) {
      const dayId = decodeURIComponent(dayMatch[1])
      if (!dayExists(dayId)) return fail(res, 404, '日期不存在')

      if (method === 'PATCH') {
        let body
        try {
          body = await readBody(req, res)
        } catch (_error) {
          return fail(res, 400, '请求格式错误')
        }
        if (res.writableEnded) return
        const sets = []
        const values = []
        if (body.date !== undefined) {
          const date = text(body.date, 10)
          if (!isIsoDate(date)) return fail(res, 400, '日期格式不正确')
          sets.push('date = ?')
          values.push(date)
        }
        if (body.location !== undefined) {
          const location = text(body.location, MAX_LOCATION)
          if (!location) return fail(res, 400, '地点不能为空')
          sets.push('location = ?')
          values.push(location)
        }
        if (body.note !== undefined) {
          sets.push('note = ?')
          values.push(multiline(body.note, MAX_DAY_NOTE))
        }
        if (!sets.length) return fail(res, 400, '没有需要更新的字段')
        values.push(dayId)
        db.prepare(`UPDATE days SET ${sets.join(', ')} WHERE id = ?`).run(...values)
        return send(res, 200, { ok: true })
      }

      if (method === 'DELETE') {
        db.prepare('DELETE FROM days WHERE id = ?').run(dayId)
        return send(res, 200, { ok: true })
      }

    }

    const activityMatch = /^\/api\/activities\/([^/]+)$/.exec(pathname)
    if (activityMatch) {
      const activityId = decodeURIComponent(activityMatch[1])
      const activity = findActivity(activityId)
      if (!activity) return fail(res, 404, '日程不存在')

      if (method === 'PATCH') {
        let body
        try {
          body = await readBody(req, res)
        } catch (_error) {
          return fail(res, 400, '请求格式错误')
        }
        if (res.writableEnded) return
        const sets = []
        const values = []
        const nextStart = body.startTime !== undefined ? normalizeTime(body.startTime, false) : activity.start_time
        const nextEnd = body.endTime !== undefined ? normalizeTime(body.endTime, true) : activity.end_time
        if (body.startTime !== undefined && !nextStart) return fail(res, 400, '时间格式不正确（HH:mm）')
        if (body.endTime !== undefined && !nextEnd) return fail(res, 400, '时间格式不正确（HH:mm）')
        if (timeToMinutes(nextStart) > timeToMinutes(nextEnd)) return fail(res, 400, '开始时间不能晚于结束时间')
        if (body.location !== undefined) {
          const location = text(body.location, MAX_LOCATION)
          if (!location) return fail(res, 400, '地点不能为空')
          sets.push('location = ?')
          values.push(location)
        }
        if (body.startTime !== undefined) {
          sets.push('start_time = ?')
          values.push(nextStart)
        }
        if (body.endTime !== undefined) {
          sets.push('end_time = ?')
          values.push(nextEnd)
        }
        if (body.detail !== undefined) {
          sets.push('detail = ?')
          values.push(multiline(body.detail, MAX_DETAIL))
        }
        if (!sets.length) return fail(res, 400, '没有需要更新的字段')
        values.push(activityId)
        db.prepare(`UPDATE activities SET ${sets.join(', ')} WHERE id = ?`).run(...values)
        return send(res, 200, { ok: true })
      }

      if (method === 'DELETE') {
        db.prepare('DELETE FROM activities WHERE id = ?').run(activityId)
        return send(res, 200, { ok: true })
      }
    }

    const stateMatch = /^\/api\/activities\/([^/]+)\/state$/.exec(pathname)
    if (stateMatch && method === 'PUT') {
      const activityId = decodeURIComponent(stateMatch[1])
      if (!findActivity(activityId)) return fail(res, 404, '日程不存在')
      let body
      try {
        body = await readBody(req, res)
      } catch (_error) {
        return fail(res, 400, '请求格式错误')
      }
      if (res.writableEnded) return
      const current = db.prepare('SELECT * FROM activity_state WHERE activity_id = ? AND username = ?')
        .get(activityId, username)
      const note = body.note !== undefined ? multiline(body.note, MAX_STATE_NOTE) : (current ? current.note : '')
      const favorite = body.favorite !== undefined ? (body.favorite ? 1 : 0) : (current ? current.favorite : 0)
      const completed = body.completed !== undefined ? (body.completed ? 1 : 0) : (current ? current.completed : 0)
      db.prepare(`
        INSERT INTO activity_state (activity_id, username, note, favorite, completed, updated_at)
        VALUES (?, ?, ?, ?, ?, ?)
        ON CONFLICT(activity_id, username) DO UPDATE SET
          note = excluded.note, favorite = excluded.favorite,
          completed = excluded.completed, updated_at = excluded.updated_at
      `).run(activityId, username, note, favorite, completed, Date.now())
      return send(res, 200, {
        ok: true,
        state: { note, favorite: Boolean(favorite), completed: Boolean(completed) },
      })
    }

    return fail(res, 404, '接口不存在')
  }

  if (serveStatic(res, pathname)) return
  return fail(res, 404, '页面不存在')
})

if (require.main === module) {
  server.listen(PORT, HOST, () => {
    console.log(`toComic 行程安排网页版 listening on http://${HOST}:${PORT}`)
    console.log(`数据库: ${DB_FILE}`)
  })
}

module.exports = { db, generateAccounts, setPassword, accountName, randomPassword, userCount }
