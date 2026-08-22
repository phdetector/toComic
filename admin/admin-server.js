const http = require('http')
const fs = require('fs')
const path = require('path')

const PORT = 37796
const REMOTE = 'http://101.37.21.166:37795'

let token = null
let cache = []
let lastSync = 0

function json(res, code, body) {
  res.writeHead(code, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' })
  res.end(JSON.stringify(body))
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let raw = ''
    req.setEncoding('utf8')
    req.on('data', chunk => { raw += chunk })
    req.on('end', () => {
      try { resolve(JSON.parse(raw || '{}')) } catch (error) { reject(error) }
    })
    req.on('error', reject)
  })
}

async function refresh() {
  const response = await fetch(`${REMOTE}/admin/suggestions`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  if (!response.ok) throw new Error('remote fetch failed')
  const payload = await response.json()
  cache = Array.isArray(payload.suggestions) ? payload.suggestions : []
  lastSync = Date.now()
}

async function remotePost(route, body) {
  const response = await fetch(`${REMOTE}${route}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify(body),
  })
  const payload = await response.json()
  if (!response.ok || !payload.ok) throw new Error(payload.error || 'remote update failed')
  await refresh()
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`)
  const pathname = url.pathname

  if (req.method === 'POST' && pathname === '/api/login') {
    try {
      const body = await readBody(req)
      const response = await fetch(`${REMOTE}/admin/login`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ username: body.username, password: body.password }),
      })
      const payload = await response.json()
      if (!response.ok || !payload.ok) {
        json(res, 401, { ok: false, error: payload.error || 'invalid credentials' })
        return
      }
      token = payload.token
      await refresh()
      json(res, 200, { ok: true })
    } catch (error) {
      json(res, 401, { ok: false, error: String(error) })
    }
    return
  }

  if (req.method === 'GET' && (pathname === '/' || pathname === '/index.html')) {
    const file = path.join(__dirname, 'public', 'index.html')
    const content = fs.readFileSync(file)
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' })
    res.end(content)
    return
  }

  if (!token) {
    json(res, 401, { ok: false, error: 'unauthorized' })
    return
  }

  if (req.method === 'GET' && pathname === '/api/suggestions') {
    try {
      if (Date.now() - lastSync > 60 * 1000) await refresh()
      json(res, 200, { ok: true, suggestions: cache })
    } catch (error) {
      json(res, 502, { ok: false, error: String(error) })
    }
    return
  }

  if (req.method === 'POST' && ['/api/reply', '/api/favorite', '/api/archive'].includes(pathname)) {
    try {
      const body = await readBody(req)
      await remotePost(pathname, body)
      json(res, 200, { ok: true })
    } catch (error) {
      json(res, 502, { ok: false, error: String(error) })
    }
    return
  }

  json(res, 404, { ok: false, error: 'not found' })
})

function cleanup() {
  token = null
  cache = []
  console.log('admin cache cleared')
}

process.on('SIGINT', () => { cleanup(); process.exit(0) })
process.on('SIGTERM', () => { cleanup(); process.exit(0) })
process.on('exit', cleanup)

server.listen(PORT, '127.0.0.1', () => {
  console.log(`expo admin UI listening on http://127.0.0.1:${PORT}`)
})
