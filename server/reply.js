const fs = require('fs')
const path = require('path')

const DATA_FILE = '/var/lib/expo-suggestions/suggestions.jsonl'
const id = process.argv[2]
const reply = process.argv[3]
if (!id || !reply) {
  console.error('usage: node reply.js <suggestion-id> <reply text>')
  process.exit(2)
}

const lines = fs.readFileSync(DATA_FILE, 'utf8').split('\n')
let updated = false
const next = lines.map(line => {
  if (!line.trim()) return line
  const record = JSON.parse(line)
  if (record.id !== id) return line
  record.reply = reply.trim()
  record.repliedAt = Date.now()
  updated = true
  return JSON.stringify(record)
})

if (!updated) {
  console.error('suggestion not found:', id)
  process.exit(3)
}

const tmp = DATA_FILE + '.tmp'
const stats = fs.statSync(DATA_FILE)
fs.writeFileSync(tmp, next.join('\n'))
fs.renameSync(tmp, DATA_FILE)
fs.chownSync(DATA_FILE, stats.uid, stats.gid)
console.log('replied:', id)
