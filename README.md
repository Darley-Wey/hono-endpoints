# Hono Endpoints

JetBrains IDE support for discovering and navigating [Hono](https://hono.dev/) routes in the built-in **Endpoints** tool window.

> Early development. The goal is first-class Hono framework support in WebStorm without requiring generated OpenAPI files.

## Current bootstrap

The first implementation is intentionally conservative and focuses on proving the JetBrains integration end to end:

- Registers a native `Hono` provider in the built-in Endpoints tool window
- Scans JavaScript and TypeScript PSI for Hono route calls
- Detects `get`, `post`, `put`, `patch`, `delete`, `options`, and `head`
- Accepts static string-literal paths
- Resolves local router variables back to `new Hono()`
- Supports chained declarations such as `new Hono().get(...).post(...)`
- Navigates from an endpoint back to the route call in source

Example currently supported:

```ts
import { Hono } from 'hono'

const app = new Hono()
  .get('/users', listUsers)
  .post('/users', createUser)

app.get('/users/:id', getUser)
```

## Next milestones

- Build a route graph instead of storing only local paths
- Resolve cross-file `route()` composition
- Propagate `basePath()` prefixes
- Support imported/aliased routers and aliased `Hono` constructors
- Resolve constant paths
- Add `all()` and `on()` endpoint expansion
- Add incremental project/file caches
- Add request metadata contributors for `zValidator` and Standard Schema validators
- Add tests using realistic Hono TypeScript fixtures

## Architecture

The provider is deliberately thin:

```text
JavaScript / TypeScript PSI
          ↓
    Hono analyzer
          ↓
     route model
          ↓
 JetBrains adapter
          ↓
   Endpoints tool window
```

Future `zValidator`, OpenAPI, or schema support should enrich the route model rather than becoming the source of truth for route discovery.

## Development

Requirements:

- JDK 25
- Gradle 9+

The project targets WebStorm 2026.2 and uses IntelliJ Platform Gradle Plugin 2.x.

```bash
gradle build
gradle runIde
```

## License

Apache-2.0
