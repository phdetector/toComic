const http = require('http')
const fs = require('fs')
const path = require('path')
const { randomUUID } = require('node:crypto')

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
