#!/usr/bin/env node
// 账号管理：列表 / 重置单个密码 / 重新生成 10 组。
// 用法：
//   node tools/accounts.js --list
//   node tools/accounts.js --reset user03
//   node tools/accounts.js --regen
//   node tools/accounts.js --list --db D:\path\trip.db

const args = process.argv.slice(2)

function takeFlag(name) {
  const index = args.indexOf(name)
  if (index < 0) return null
  const value = args[index + 1]
  args.splice(index, value && !value.startsWith('--') ? 2 : 1)
  return value && !value.startsWith('--') ? value : true
}

const dbFlag = takeFlag('--db')
if (typeof dbFlag === 'string') process.env.WEB_DB = dbFlag

const wantList = takeFlag('--list')
const wantRegen = takeFlag('--regen')
const resetUser = takeFlag('--reset')

const { db, userCount, generateAccounts, setPassword, randomPassword } = require('../server.js')

function listUsers() {
  const rows = db.prepare('SELECT username, created_at FROM users ORDER BY username').all()
  console.log(`共 ${rows.length} 个账号：`)
  for (const row of rows) console.log('  ' + row.username)
}

if (wantRegen) {
  const pairs = generateAccounts({ force: true })
  console.log('已重新生成 ' + pairs.length + ' 组账号（请立即保存，随后删除 data/accounts.txt）：')
  for (const pair of pairs) console.log('  ' + pair.username + '\t' + pair.password)
  process.exit(0)
}

if (typeof resetUser === 'string') {
  const row = db.prepare('SELECT username FROM users WHERE username = ?').get(resetUser)
  if (!row) {
    console.error('账号不存在: ' + resetUser)
    process.exit(2)
  }
  const password = randomPassword()
  setPassword(resetUser, password)
  console.log(`${resetUser}\t${password}`)
  process.exit(0)
}

if (wantList) {
  listUsers()
  process.exit(0)
}

if (userCount() === 0) {
  console.error('当前没有任何账号，请先执行 node tools/accounts.js --regen')
  process.exit(1)
}

console.log('用法：')
console.log('  node tools/accounts.js --list             只列出用户名')
console.log('  node tools/accounts.js --reset <username> 重置单个账号密码并打印')
console.log('  node tools/accounts.js --regen            清空并重新生成 10 组账号')
console.log('  可选 --db <path> 指定数据库文件')
listUsers()
