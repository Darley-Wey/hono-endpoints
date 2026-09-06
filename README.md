# Hono Endpoints

JetBrains IDE support for discovering and navigating [Hono](https://hono.dev/) routes in the built-in **Endpoints** tool window.

> Early development. The goal is first-class Hono framework support in WebStorm without requiring generated OpenAPI files.

## Current bootstrap

The plugin is implemented in Kotlin and keeps Hono analysis separate from the JetBrains adapter:

- Registers a native `Hono` provider in the built-in Endpoints tool window
- Uses cached manifest and word-index probes for provider availability, without scanning routes in `getStatus()`
- Recognizes Hono dependencies in workspace `package.json` files and direct imports in source-only projects
- Discovers `get`, `post`, `put`, `patch`, `delete`, `options`, and `head` with static string-literal paths
- Resolves named Hono imports and aliases through JavaScript/TypeScript PSI, including `import { Hono as App }`
- Supports CommonJS destructuring, including `const { Hono: App } = require('hono')`, while rejecting shadowed `require` calls
- Rejects shadowed constructors, unrelated imports, type-only imports, and unsupported default imports
- Supports local router aliases, method chains, and ordinary parent routes after `.route(prefix, child)`
- Caches project results until PSI, project roots, file structure, or indexing state changes
- Excludes dependency/library sources and defers analysis during indexing
- Navigates from an endpoint back to the route call in source

Example currently supported:

```ts
import { Hono as App } from 'hono'

const app = new App()
  .get('/users', listUsers)
  .post('/users', createUser)

app.get('/users/:id', getUser)
```

### Current limitations

This is a **local route scanner**, not yet a composed route graph. Receiver resolution stops at `basePath()` and `mount()` calls. For example, `new Hono().basePath('/api').get('/users', handler)` is omitted rather than incorrectly reported as `/users`.

`route(prefix, child)` returns the parent app, so subsequent parent routes are discovered without inheriting the mount prefix. Routes declared separately on a child app are still local definitions, not resolved mount contexts. Do not rely on this version for effective URLs of mounted child applications.

Cross-file routers, constant path evaluation, `all()` / `on()` discovery, and validator/schema metadata are not implemented. Caching is project-wide; file-level incremental analysis is a later milestone.

## Architecture

```text
JavaScript / TypeScript PSI
          ↓
    HonoProjectScanner
          ↓
       route model
          ↓
    HonoProjectModel
          ↓
 HonoEndpointsProvider
          ↓
   Endpoints tool window
```

Source lives under `src/main/kotlin/io/github/darleywey/honoendpoints/`:

- `analysis/`: source discovery and semantic router resolution
- `framework/`: Hono symbols and cheap framework detection
- `model/`: endpoint data classes, independent of the Endpoints API
- `project/`: project cache and invalidation
- `endpoints/`: presentation and navigation adapter

Future `zValidator`, OpenAPI, or schema support should enrich the route model rather than becoming the source of truth for route discovery.

## Next milestones

- Build a route graph with router definitions and mount contexts
- Resolve cross-file `route()` composition and propagate `basePath()` prefixes
- Support imported routers across files and constant paths
- Add `all()` and `on()` endpoint expansion
- Add file-level caches and incremental graph updates
- Add request metadata contributors for `zValidator` and Standard Schema validators

## Development

Requirements:

- JDK 25
- Gradle 9.7+

The project targets WebStorm `2026.2.0.1`, uses IntelliJ Platform Gradle Plugin `2.18.1`, and compiles Kotlin `2.4.0` to JVM 25. The plugin uses the IDE's bundled Kotlin standard library rather than shipping another copy.

Run the JUnit tests, including real WebStorm JavaScript/TypeScript PSI fixtures:

```bash
gradle test
```

Build the installable plugin ZIP in `build/distributions/`:

```bash
gradle buildPlugin
```

Launch the development IDE:

```bash
gradle runIde
```

Tests cover ESM/CommonJS import aliases, shadowing, `.route()` parent semantics, conservative `basePath()` handling, source navigation targets, cache reuse and invalidation, file creation/deletion, excluded roots, source-only projects, and recovery after indexing. Provider integration tests check content-only projects without source roots and SDK scope filters.

For interactive verification, open [examples/smoke](examples/smoke) in WebStorm; its README lists the three expected paths and navigation checks. This is separate from automated PSI/provider tests.

To test against an installed IDE instead of downloading the default SDK:

```bash
gradle test -PlocalIdePath=/path/to/WebStorm.app
```

## License

Apache-2.0
