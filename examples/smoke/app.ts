import { Hono as App } from 'hono'

const child = new App()
export const app = new App()
  .route('/child', child)
  .get('/hono-smoke/esm', (c) => c.text('hello'))
  .post('/hono-smoke/items', (c) => c.json({ ok: true }))
