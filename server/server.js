const http = require('http')
const fs = require('fs')
const path = require('path')

const DATA_DIR = '/var/lib/expo-suggestions'
const DATA_FILE = path.join(DATA_DIR, 'suggestions.jsonl')
const MAX_TITLE = 200
const MAX_CONTENT = 5000
const MAX_BODY = 64 * 1024

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

const server = http.createServer((req, res) => {
  if (req.method === 'OPTIONS') {
    res.writeHead(204, {
      'Access-Control-Allow-Origin': '*',
      'Access-Control-Allow-Methods': 'POST, OPTIONS',
      'Access-Control-Allow-Headers': 'Content-Type',
    })
    res.end()
    return
  }
  const pathname = (req.url || '').split('?')[0]
  if (req.method !== 'POST' || pathname !== '/api/suggestions') {
    send(res, 404, { ok: false, error: 'not found' })
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
    const record = {
      title,
      content,
      appVersion: String(data && data.appVersion || '').slice(0, 50),
      deviceModel: String(data && data.deviceModel || '').slice(0, 100),
      androidVersion: String(data && data.androidVersion || '').slice(0, 50),
      submittedAt: Number(data && data.submittedAt) || Date.now(),
      receivedAt: Date.now(),
      ip: String(req.headers['x-forwarded-for'] || req.socket.remoteAddress || '').slice(0, 100),
    }
    try {
      fs.mkdirSync(DATA_DIR, { recursive: true })
      fs.appendFileSync(DATA_FILE, JSON.stringify(record) + '\n')
      send(res, 200, { ok: true })
    } catch (error) {
      console.error('failed to store suggestion', error)
      send(res, 500, { ok: false, error: 'storage failed' })
    }
  })
})

server.listen(7978, '127.0.0.1', () => {
  console.log('expo suggestions listening on 127.0.0.1:7978')
})
