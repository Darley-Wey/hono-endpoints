const { Hono: App } = require('hono')

const app = new App()
app.get('/hono-smoke/cjs', (c) => c.text('hello'))

module.exports = app
