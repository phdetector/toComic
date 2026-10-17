#!/usr/bin/env node
// 端到端自检：临时库 + 临时端口，覆盖鉴权、校验、双账号状态隔离、级联删除、CSRF、登出、
// 连续失败锁、同一 IP 每小时失败上限、成功登录不重置每小时窗口。
// 用法：node tools/e2e.js        退出码非 0 即失败。

const fs = require('fs')
const os = require('os')
const path = require('path')
const { spawn } = require('child_process')

const BASE_PORT = Number(process.env.E2E_PORT || 14551)
const WEB_DIR = path.join(__dirname, '..')

// 会被 startServer 改写，使 call()/waitForHealth() 始终指向当前实例
let PORT = BASE_PORT
let BASE = `http://127.0.0.1:${PORT}`

const servers = []

async function startServer(port, extraEnv) {
  const tempDir = fs.mkdtempSync(path.join(os.tmpdir(), 'tocomic-e2e-'))
  const child = spawn(process.execPath, ['server.js'], {
    cwd: WEB_DIR,
    env: Object.assign({}, process.env, {
      WEB_PORT: String(port),
      WEB_HOST: '127.0.0.1',
      WEB_DB: path.join(tempDir, 'trip.db'),
      WEB_ACCOUNT_PREFIX: 'e2e',
      WEB_SESSION_SECRET: 'e2e-fixed-secret',
    }, extraEnv || {}),
    stdio: ['ignore', 'pipe', 'pipe'],
  })
  let log = ''
  child.stdout.on('data', chunk => { log += chunk.toString() })
  child.stderr.on('data', chunk => { log += chunk.toString() })
  servers.push({ child, tempDir })

  PORT = port
  BASE = `http://127.0.0.1:${port}`
  if (!(await waitForHealth())) {
    console.log(`服务未能在预期时间内启动（port ${port}）。输出：`)
    console.log(log)
    process.exit(1)
  }
  return { tempDir, accounts: readAccounts(tempDir) }
}

async function stopServers() {
  for (const server of servers) server.child.kill()
  await sleep(250)
  for (const server of servers) {
    try {
      fs.rmSync(server.tempDir, { recursive: true, force: true })
    } catch (_error) { /* 忽略清理失败 */ }
  }
}

let passed = 0
let failed = 0

function ok(label, condition, detail) {
  if (condition) {
    passed += 1
    console.log('  PASS  ' + label)
  } else {
    failed += 1
    console.log('  FAIL  ' + label + (detail === undefined ? '' : '  → ' + JSON.stringify(detail)))
  }
}

function sleep(ms) {
  return new Promise(resolve => setTimeout(resolve, ms))
}

function extractCookie(response) {
  const list = response.headers.getSetCookie ? response.headers.getSetCookie() : [response.headers.get('set-cookie')]
  for (const item of list || []) {
    if (!item) continue
    const match = /(?:^|;\s*)sid=([^;]*)/.exec(item)
    if (match) return 'sid=' + match[1]
  }
  return null
}

async function call(route, options = {}) {
  const headers = {}
  if (options.body !== undefined) headers['Content-Type'] = 'application/json'
  if (options.cookie) headers['Cookie'] = options.cookie
  if (options.origin) headers['Origin'] = options.origin
  const response = await fetch(BASE + route, {
    method: options.method || 'GET',
    headers,
    body: options.body !== undefined ? JSON.stringify(options.body) : undefined,
    redirect: 'manual',
  })
  let payload = null
  try {
    payload = await response.json()
  } catch (_error) {
    payload = null
  }
  return { status: response.status, payload, cookie: extractCookie(response) }
}

async function waitForHealth() {
  for (let attempt = 0; attempt < 60; attempt += 1) {
    try {
      const response = await fetch(BASE + '/api/health')
      if (response.ok) return true
    } catch (_error) { /* 还没起来 */ }
    await sleep(250)
  }
  return false
}

function readAccounts(tempDir) {
  const file = path.join(tempDir, 'accounts.txt')
  const raw = fs.readFileSync(file, 'utf8')
  const pairs = []
  for (const line of raw.split('\n')) {
    if (!line.trim() || line.startsWith('#')) continue
    const [username, password] = line.split('\t')
    if (username && password) pairs.push({ username, password: password.trim() })
  }
  return pairs
}

async function main() {
  try {
    const primary = await startServer(BASE_PORT, {})
    const accounts = primary.accounts
    ok('首次启动生成 10 组账号', accounts.length === 10, accounts.length)
    const userA = accounts[0]
    const userB = accounts[1]

    console.log('\n[1] 未登录访问')
    const anonDays = await call('/api/days')
    ok('未登录 GET /api/days → 401', anonDays.status === 401, anonDays.status)
    const anonPost = await call('/api/days', { method: 'POST', body: { date: '2026-11-23', location: '东京' } })
    ok('未登录 POST /api/days → 401', anonPost.status === 401, anonPost.status)

    console.log('\n[2] 登录')
    const badLogin = await call('/api/login', { method: 'POST', body: { username: userA.username, password: 'wrong-password' } })
    ok('错误密码 → 401', badLogin.status === 401, badLogin.status)
    const loginA = await call('/api/login', { method: 'POST', body: userA })
    ok('正确密码 → 200', loginA.status === 200, loginA.status)
    ok('登录下发 sid Cookie', Boolean(loginA.cookie), loginA.cookie)
    const cookieA = loginA.cookie
    const meA = await call('/api/me', { cookie: cookieA })
    ok('/api/me 回显用户名', meA.payload && meA.payload.username === userA.username, meA.payload)

    console.log('\n[3] 新增日期校验')
    const badDate = await call('/api/days', { method: 'POST', cookie: cookieA, body: { date: '2026-13-40', location: '东京' } })
    ok('非法日期 → 400', badDate.status === 400, badDate.status)
    const noLocation = await call('/api/days', { method: 'POST', cookie: cookieA, body: { date: '2026-11-23', location: '   ' } })
    ok('空地点 → 400', noLocation.status === 400, noLocation.status)
    const dayOne = await call('/api/days', { method: 'POST', cookie: cookieA, body: { date: '2026-11-23', location: '东京', note: '第一天' } })
    ok('新增日期 → 200', dayOne.status === 200 && dayOne.payload.day, dayOne.status)
    const dayId = dayOne.payload.day.id
    const dayTwo = await call('/api/days', { method: 'POST', cookie: cookieA, body: { date: '2026-11-24', location: '京都' } })
    const dayIdTwo = dayTwo.payload.day.id

    console.log('\n[4] 新增活动校验')
    const noTime = await call(`/api/days/${dayId}/activities`, {
      method: 'POST', cookie: cookieA, body: { location: 'XX机场', detail: '' },
    })
    ok('缺时间 → 400', noTime.status === 400, noTime.status)
    const inverted = await call(`/api/days/${dayId}/activities`, {
      method: 'POST', cookie: cookieA, body: { location: 'XX机场', startTime: '18:00', endTime: '09:00' },
    })
    ok('开始晚于结束 → 400', inverted.status === 400, inverted.status)
    const badHour = await call(`/api/days/${dayId}/activities`, {
      method: 'POST', cookie: cookieA, body: { location: 'XX机场', startTime: '25:00', endTime: '26:00' },
    })
    ok('小时越界 → 400', badHour.status === 400, badHour.status)
    const noLoc = await call(`/api/days/${dayId}/activities`, {
      method: 'POST', cookie: cookieA, body: { location: '', startTime: '09:00', endTime: '10:00' },
    })
    ok('空地点 → 400', noLoc.status === 400, noLoc.status)

    const actOk = await call(`/api/days/${dayId}/activities`, {
      method: 'POST', cookie: cookieA,
      body: { location: 'XX机场', startTime: '14:30', endTime: '16:00', detail: 'T2航站楼' },
    })
    ok('新增活动 → 200', actOk.status === 200 && actOk.payload.activity, actOk.status)
    const activityId = actOk.payload.activity.id
    const actEnd2400 = await call(`/api/days/${dayId}/activities`, {
      method: 'POST', cookie: cookieA, body: { location: '夜市', startTime: '20:00', endTime: '24:00' },
    })
    ok('结束 24:00 允许', actEnd2400.status === 200, actEnd2400.status)
    const actBad2400 = await call(`/api/days/${dayId}/activities`, {
      method: 'POST', cookie: cookieA, body: { location: '夜市', startTime: '24:00', endTime: '24:00' },
    })
    ok('开始 24:00 被拒', actBad2400.status === 400, actBad2400.status)

    console.log('\n[5] 每人状态')
    const stateA = await call(`/api/activities/${activityId}/state`, {
      method: 'PUT', cookie: cookieA, body: { note: 'A的备注', favorite: true, completed: true },
    })
    ok('A 写入状态 → 200', stateA.status === 200 && stateA.payload.state.favorite === true, stateA.payload)

    const loginB = await call('/api/login', { method: 'POST', body: userB })
    const cookieB = loginB.cookie
    ok('B 登录成功', loginB.status === 200, loginB.status)

    const daysAsB = await call('/api/days', { cookie: cookieB })
    const actAsB = daysAsB.payload.days.flatMap(day => day.activities).find(item => item.id === activityId)
    ok('B 能看到同一份共享行程', Boolean(actAsB), null)
    ok('B 的状态是默认值（隔离）',
      actAsB && actAsB.state.note === '' && actAsB.state.favorite === false && actAsB.state.completed === false,
      actAsB && actAsB.state)

    await call(`/api/activities/${activityId}/state`, { method: 'PUT', cookie: cookieB, body: { favorite: true, note: 'B的备注' } })
    const daysAsA = await call('/api/days', { cookie: cookieA })
    const actAsA = daysAsA.payload.days.flatMap(day => day.activities).find(item => item.id === activityId)
    ok('A 的状态未被 B 覆盖',
      actAsA && actAsA.state.note === 'A的备注' && actAsA.state.favorite === true && actAsA.state.completed === true,
      actAsA && actAsA.state)

    console.log('\n[6] 共享字段编辑')
    const patchByB = await call(`/api/activities/${activityId}`, {
      method: 'PATCH', cookie: cookieB, body: { location: 'XX机场 T2' },
    })
    ok('B 可以改共享字段（权限相同）', patchByB.status === 200, patchByB.status)
    const patchBad = await call(`/api/activities/${activityId}`, {
      method: 'PATCH', cookie: cookieB, body: { startTime: '20:00', endTime: '10:00' },
    })
    ok('PATCH 时间倒置 → 400', patchBad.status === 400, patchBad.status)

    console.log('\n[7] 路径与安全')
    const notFound = await call(`/api/activities/does-not-exist/state`, { method: 'PUT', cookie: cookieA, body: { favorite: true } })
    ok('不存在的活动 → 404', notFound.status === 404, notFound.status)
    const csrf = await call('/api/days', {
      method: 'POST', cookie: cookieA, origin: 'http://evil.example', body: { date: '2026-12-01', location: 'x' },
    })
    ok('跨站 Origin → 403', csrf.status === 403, csrf.status)

    console.log('\n[8] 级联删除')
    const delDay = await call(`/api/days/${dayId}`, { method: 'DELETE', cookie: cookieA })
    ok('删除日期 → 200', delDay.status === 200, delDay.status)
    const afterDelete = await call('/api/days', { cookie: cookieA })
    ok('该日期下活动一并删除',
      afterDelete.payload.days.every(day => day.id !== dayId) &&
      afterDelete.payload.days.flatMap(day => day.activities).every(item => item.id !== activityId),
      null)
    const touchDeleted = await call(`/api/activities/${activityId}/state`, { method: 'PUT', cookie: cookieA, body: { favorite: true } })
    ok('已删活动的状态接口 → 404', touchDeleted.status === 404, touchDeleted.status)
    ok('另一日期仍在', afterDelete.payload.days.some(day => day.id === dayIdTwo), null)

    console.log('\n[9] 登出')
    await call('/api/logout', { method: 'POST', cookie: cookieA, body: {} })
    const afterLogout = await call('/api/days', { cookie: 'sid=' })
    ok('登出后 /api/days → 401', afterLogout.status === 401, afterLogout.status)

    console.log('\n[10] 连续失败锁（本实例最后一项按 IP 计数的检查）')
    let lockStatus = null
    for (let attempt = 0; attempt < 5; attempt += 1) {
      const response = await call('/api/login', { method: 'POST', body: { username: userA.username, password: 'nope' } })
      lockStatus = response.status
    }
    ok('前 5 次错误密码 → 401', lockStatus === 401, lockStatus)
    const locked = await call('/api/login', { method: 'POST', body: userA })
    ok('第 6 次（即使密码正确）→ 429 锁定', locked.status === 429, locked.status)

    console.log('\n[11] 静态资源')
    for (const route of ['/', '/style.css', '/app.js', '/favicon.svg']) {
      const response = await fetch(BASE + route)
      ok(`GET ${route} → 200`, response.ok, response.status)
    }

    console.log('\n[12] 同一 IP 每小时失败上限（独立实例：把连续失败锁放到 9999，只留每小时 10 次）')
    const alt = await startServer(BASE_PORT + 1, { WEB_LOGIN_MAX_FAILURES: '9999' })
    const altUser = alt.accounts[0]
    let allowed = 0
    for (let attempt = 0; attempt < 10; attempt += 1) {
      const response = await call('/api/login', { method: 'POST', body: { username: altUser.username, password: 'nope' } })
      if (response.status === 401) allowed += 1
    }
    ok('前 10 次失败尝试 → 401（额度内不拦）', allowed === 10, allowed)
    const eleventh = await call('/api/login', { method: 'POST', body: { username: altUser.username, password: 'nope' } })
    ok('第 11 次失败尝试 → 429', eleventh.status === 429, eleventh.status)
    ok('429 文案指向每小时上限', Boolean(eleventh.payload && /每小时/.test(eleventh.payload.error)), eleventh.payload)
    const evenCorrect = await call('/api/login', { method: 'POST', body: altUser })
    ok('额度用尽后即使密码正确 → 429', evenCorrect.status === 429, evenCorrect.status)

    console.log('\n[13] 成功登录不重置每小时窗口')
    const solo = await startServer(BASE_PORT + 2, { WEB_LOGIN_MAX_FAILURES: '9999' })
    const soloUser = solo.accounts[0]
    for (let attempt = 0; attempt < 4; attempt += 1) {
      await call('/api/login', { method: 'POST', body: { username: soloUser.username, password: 'nope' } })
    }
    const good = await call('/api/login', { method: 'POST', body: soloUser })
    ok('第 5 次用正确密码 → 200', good.status === 200, good.status)
    for (let attempt = 0; attempt < 6; attempt += 1) {
      await call('/api/login', { method: 'POST', body: { username: soloUser.username, password: 'nope' } })
    }
    const capped = await call('/api/login', { method: 'POST', body: { username: soloUser.username, password: 'nope' } })
    ok('4 次失败 + 成功 + 6 次失败 = 10 次后才封 → 429', capped.status === 429, capped.status)
  } finally {
    await stopServers()
  }

  console.log(`\n结果：PASS ${passed} / FAIL ${failed}`)
  process.exit(failed === 0 ? 0 : 1)
}

main().catch(error => {
  console.error('自检异常：', error)
  process.exit(1)
})
