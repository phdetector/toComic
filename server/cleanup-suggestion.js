const { DatabaseSync } = require('node:sqlite')
const db = new DatabaseSync('/var/lib/expo-suggestions/suggestions.db')
console.log(db.prepare('DELETE FROM suggestions WHERE id = ?').run(process.argv[2]).changes)
