const http = require('http')
const fs = require('fs')
const os = require('os')
const path = require('path')
const { spawn } = require('child_process')

const PORT = 37796
const TUNNEL_PORT = Number(process.env.EXPO_ADMIN_TUNNEL_PORT || 37795)
const REMOTE = `http://127.0.0.1:${TUNNEL_PORT}`

let token = null
let cache = []
let lastSync = 0
let tunnelProcess = null

class UnauthorizedError extends Error {
  constructor() { super('unauthorized') }
}

function startTunnel() {
  if (process.env.EXPO_ADMIN_NO_TUNNEL === '1') return
  const sshKey = process.env.EXPO_ADMIN_SSH_KEY
    || path.join(process.env.USERPROFILE || '', '.ssh', 'gptvoice_id_ed25519')
  tunnelProcess = spawn('ssh', [
    '-N',
    '-L', `${TUNNEL_PORT}:127.0.0.1:${TUNNEL_PORT}`,
    'root@101.37.21.166',
    '-i', sshKey,
    '-o', 'BatchMode=yes',
    '-o', 'StrictHostKeyChecking=accept-new',
    '-o', `UserKnownHostsFile=${path.join(os.tmpdir(), 'expo-admin-known-hosts')}`,
  ], { stdio: 'ignore' })
  tunnelProcess.on('exit', () => { tunnelProcess = null })
}

function stopTunnel() {
  if (tunnelProcess) {
    tunnelProcess.kill()
    tunnelProcess = null
  }
}

function json(res, code, body) {
  res.writeHead(code, {
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': 'no-store',
    'X-Content-Type-Options': 'nosniff',
    'X-Frame-Options': 'DENY',
  })
  res.end(JSON.stringify(body))
}

function readBody(req, res) {
  return new Promise((resolve, reject) => {
    let raw = ''
    let size = 0
    req.setEncoding('utf8')
    req.on('data', chunk => {
      size += Buffer.byteLength(chunk)
      if (size > 64 * 1024) {
        json(res, 413, { ok: false, error: 'body too large' })
        req.destroy()
        return
      }
      raw += chunk
    })
    req.on('end', () => {
      if (res.writableEnded) return
      try { resolve(JSON.parse(raw || '{}')) } catch (error) { reject(error) }
    })
    req.on('error', reject)
  })
}

function adoptRenewedToken(response) {
  const renewed = response.headers.get('x-expo-token')
  if (renewed) token = renewed
}

async function refresh() {
  const response = await fetch(`${REMOTE}/admin/suggestions`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  if (response.status === 401) {
    token = null
    throw new UnauthorizedError()
  }
  if (!response.ok) throw new Error('remote fetch failed')
  adoptRenewedToken(response)
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
  if (response.status === 401) {
    token = null
    throw new UnauthorizedError()
  }
  const payload = await response.json()
  if (!response.ok || !payload.ok) throw new Error(payload.error || 'remote update failed')
  adoptRenewedToken(response)
  await refresh()
}

function errorResponse(res, error) {
  if (error instanceof UnauthorizedError) {
    json(res, 401, { ok: false, error: 'unauthorized' })
  } else {
    json(res, 502, { ok: false, error: String(error) })
  }
}

const server = http.createServer(async (req, res) => {
  const url = new URL(req.url, `http://127.0.0.1:${PORT}`)
  const pathname = url.pathname

  if (req.method === 'POST' && pathname === '/api/login') {
    try {
      const body = await readBody(req, res)
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
          res.writeHead(200, {
        'Content-Type': 'text/html; charset=utf-8',
        'Cache-Control': 'no-store',
        'X-Content-Type-Options': 'nosniff',
        'X-Frame-Options': 'DENY',
      })
    res.end(content)
    return
  }

  if (req.headers['x-expo-admin'] !== '1') {
    json(res, 403, { ok: false, error: 'forbidden' })
    return
  }

  if (!token) {
    json(res, 401, { ok: false, error: 'unauthorized' })
    return
  }

  if (req.method === 'GET' && pathname === '/api/suggestions') {
    try {
      const page = Number(url.searchParams.get('page')) || 1
      const limit = Number(url.searchParams.get('limit')) || 50
      const response = await fetch(`${REMOTE}/admin/suggestions?page=${page}&limit=${limit}`, {
        headers: { Authorization: `Bearer ${token}` },
      })
      if (response.status === 401) {
        token = null
        throw new UnauthorizedError()
      }
      if (!response.ok) throw new Error('remote fetch failed')
      adoptRenewedToken(response)
      json(res, 200, await response.json())
    } catch (error) {
      errorResponse(res, error)
    }
    return
  }

  if (req.method === 'POST' && ['/api/reply', '/api/favorite', '/api/archive'].includes(pathname)) {
    try {
      const body = await readBody(req, res)
      await remotePost(pathname.replace('/api/', '/admin/'), body)
      json(res, 200, { ok: true })
    } catch (error) {
      errorResponse(res, error)
    }
    return
  }

  json(res, 404, { ok: false, error: 'not found' })
})

function cleanup() {
  token = null
  cache = []
  stopTunnel()
  console.log('admin cache cleared')
}

process.on('SIGINT', () => { cleanup(); process.exit(0) })
process.on('SIGTERM', () => { cleanup(); process.exit(0) })
process.on('uncaughtException', () => { cleanup(); process.exit(1) })
process.on('unhandledRejection', () => { cleanup(); process.exit(1) })
process.on('exit', cleanup)

startTunnel()
server.listen(PORT, '127.0.0.1', () => {
  console.log(`expo admin UI listening on http://127.0.0.1:${PORT}`)
  console.log(`ssh tunnel to 101.37.21.166:${TUNNEL_PORT}`)
})
