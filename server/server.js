const http = require('http')
const fs = require('fs')
const path = require('path')
const { randomUUID, createHmac, scryptSync, timingSafeEqual } = require('node:crypto')

const DATA_DIR = '/var/lib/expo-suggestions'
const DATA_FILE = path.join(DATA_DIR, 'suggestions.jsonl')
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
  return true
}

const APP_MESSAGES_FILE = path.join(DATA_DIR, 'app-messages.json')
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

function readAppMessages() {
  try {
    const raw = fs.readFileSync(APP_MESSAGES_FILE, 'utf8')
    const parsed = JSON.parse(raw)
    if (!parsed || typeof parsed !== 'object') return DEFAULT_APP_MESSAGES
    return {
      notices: Array.isArray(parsed.notices) ? parsed.notices : [],
      update: parsed.update && typeof parsed.update === 'object' ? parsed.update : null,
    }
  } catch (error) {
    if (!error || error.code !== 'ENOENT') console.error('failed to read app messages', error)
    return DEFAULT_APP_MESSAGES
  }
}

function send(res, code, body) {
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Access-Control-Allow-Origin': '*',
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

function readSuggestions() {
  let raw = ''
  try {
    raw = fs.readFileSync(DATA_FILE, 'utf8')
  } catch (error) {
    if (error && error.code === 'ENOENT') return []
    throw error
  }
  return raw
    .split('\n')
    .filter(Boolean)
    .map(line => {
      try {
        return JSON.parse(line)
      } catch (_error) {
        return null
      }
    })
    .filter(Boolean)
}

function appendSuggestion(record) {
  fs.mkdirSync(DATA_DIR, { recursive: true })
  fs.appendFileSync(DATA_FILE, JSON.stringify(record) + '\n')
}

const ADMIN_PORT = 37795
const ADMIN_CREDENTIALS_FILE = path.join(DATA_DIR, 'admin-credentials.json')
const ADMIN_TOKEN_TTL_MS = 30 * 60 * 1000
const adminTokens = new Map()

function readAdminCredentials() {
  const raw = fs.readFileSync(ADMIN_CREDENTIALS_FILE, 'utf8')
  const parsed = JSON.parse(raw)
  return { username: parsed.username, hash: parsed.hash, salt: parsed.salt }
}

function issueAdminToken(username) {
  const secret = process.env.EXPO_ADMIN_SECRET || 'expo-admin-local-secret'
  const expiry = Date.now() + ADMIN_TOKEN_TTL_MS
  const token = createHmac('sha256', secret).update(`${username}:${expiry}`).digest('hex')
  adminTokens.set(token, expiry)
  return token
}

function verifyAdmin(req) {
  const header = String(req.headers.authorization || '')
  if (!header.startsWith('Bearer ')) return false
  const token = header.slice(7)
  const expiry = adminTokens.get(token)
  if (!expiry) return false
  if (Date.now() > expiry) {
    adminTokens.delete(token)
    return false
  }
  return true
}

function updateSuggestion(id, patch) {
  const lines = fs.readFileSync(DATA_FILE, 'utf8').split('\n')
  let updated = false
  const next = lines.map(line => {
    if (!line.trim()) return line
    const record = JSON.parse(line)
    if (record.id !== id) return line
    Object.assign(record, patch)
    updated = true
    return JSON.stringify(record)
  })
  if (!updated) return false
  const stats = fs.statSync(DATA_FILE)
  const tmp = DATA_FILE + '.tmp'
  fs.writeFileSync(tmp, next.join('\n'))
  fs.renameSync(tmp, DATA_FILE)
  fs.chownSync(DATA_FILE, stats.uid, stats.gid)
  return true
}

const server = http.createServer((req, res) => {
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
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
    if (clientId.length === 0) {
      send(res, 400, { ok: false, error: 'clientId is required' })
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
      ip: clientIp,
    }
    try {
      appendSuggestion(record)
      send(res, 200, { ok: true, suggestion: record })
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
      'Access-Control-Allow-Origin': '*',
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
          send(res, 401, { ok: false, error: 'invalid credentials' })
          return
        }
        send(res, 200, { ok: true, token: issueAdminToken(username) })
      } catch (error) {
        console.error('admin login failed', error)
        send(res, 500, { ok: false, error: 'login failed' })
      }
    })
    return
  }

  if (!verifyAdmin(req)) {
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

adminServer.listen(ADMIN_PORT, '0.0.0.0', () => {
  console.log('expo admin listening on 0.0.0.0:' + ADMIN_PORT)
})
