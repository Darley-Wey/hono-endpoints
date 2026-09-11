# Endpoints smoke test

Open this directory as a project in WebStorm with Hono Endpoints installed, and wait for indexing to finish. No HTTP server or cloud deployment is needed.

Open **View → Tool Windows → Endpoints**, clear any existing search/module/type filters, and select the **Hono** framework. Expect:

```text
GET   /hono-smoke/esm
POST  /hono-smoke/items
GET   /hono-smoke/cjs
```

Double-click a row (or press F4) to open its route call in `app.ts` or `app.cjs`. Edit a literal path and verify the list updates after PSI is committed; the refresh button can also request a rescan.

The `/child` mount does not prefix the subsequent parent routes. Full resolution of routes defined inside mounted child apps remains out of scope for the current bootstrap.
