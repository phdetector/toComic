const { DatabaseSync } = require('node:sqlite')

const DB_FILE = '/var/lib/expo-suggestions/suggestions.db'
const id = process.argv[2]
const reply = process.argv[3]
if (!id || !reply) {
  console.error('usage: node reply.js <suggestion-id> <reply text>')
  process.exit(2)
}

const db = new DatabaseSync(DB_FILE)
const result = db.prepare('UPDATE suggestions SET reply = ?, repliedAt = ?, processed = 1 WHERE id = ?')
  .run(reply.trim(), Date.now(), id)
if (result.changes === 0) {
  console.error('suggestion not found:', id)
  process.exit(3)
}
console.log('replied:', id)
