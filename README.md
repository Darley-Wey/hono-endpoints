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
- Recovers scoped value-import bindings when TypeScript resolves a constructor directly to its class declaration, with or without import provenance
- Navigates to each route's path string and keeps the complete call for documentation
- Supports CommonJS destructuring, including `const { Hono: App } = require('hono')`, while rejecting shadowed `require` calls
- Rejects shadowed constructors, unrelated imports, type-only imports, and unsupported default imports
- Supports local router aliases, method chains, and parent routes after `.route(prefix, child)`
- Composes static `basePath()` and `.route()` prefixes, including nested and repeated mounts across files
- Supplies HTTP Client, OpenAPI, and documentation side-panel data from `EndpointsUrlTargetProvider`
- Caches project results until PSI, project roots, file structure, or indexing state changes
- Excludes dependency and explicitly excluded sources, but keeps project files also indexed as TypeScript library roots
- Defers analysis during indexing

Example currently supported:

```ts
import { Hono as App } from 'hono'

const app = new App()
  .get('/users', listUsers)
  .post('/users', createUser)

app.get('/users/:id', getUser)
```

### Current limitations

This is a static route graph, not a runtime interpreter. `route(prefix, child)` copies the child's routes as they exist at that call and does not apply the child prefix to later parent routes. `basePath()` creates a new view that shares the same route table, so only later registrations on that view receive the prefix.

Dynamic prefixes, unresolved routers, and external `mount()` remain unresolved rather than guessed. `all()` / `on()` discovery, constant evaluation, and validator/schema metadata are not implemented. Caching is project-wide; file-level incremental analysis is a later milestone.

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
- `diagnostics/`: read-only file indexing and PSI diagnostics

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

Tests cover ESM/CommonJS import aliases, class-target reference results, nested shadowing, composed `basePath()` and `.route()` prefixes, source navigation targets, cache reuse and invalidation, file creation/deletion, excluded roots, library/content overlaps, source-only projects, and recovery after indexing. Provider integration tests check content-only projects without source roots, SDK scope filters, real PSI references seeded with class-target results in the SDK's JavaScript resolution cache, and navigation offsets anchored to path-literal PSI elements.

For interactive verification, open [examples/smoke](examples/smoke) in WebStorm; its README lists the three expected paths and navigation checks. This is separate from automated PSI/provider tests.

To test against an installed IDE instead of downloading the default SDK:

```bash
gradle test -PlocalIdePath=/path/to/WebStorm.app
```

## Troubleshooting missing routes

Project files can also belong to JavaScript/TypeScript library roots. Discovery uses the project **content** scope and does not reject a file just because it has a library flag. Explicitly excluded directories, dependency-only roots, and `node_modules` remain excluded.

If an expected file is still missing, open it in the editor and use **Find Action → Copy Hono File Diagnostics**. The command copies its index flags, direct-analysis count, project-model count, constructor PSI kinds, resolve-result types, import provenance, local binding kinds, and navigation targets (method, PSI class, and source text-range offsets) to the clipboard. It runs read-only in the background and does not copy source text, handler bodies, or route strings. The report includes the file path, so review it before sharing.

A `TypeScriptClassImpl` constructor target is supported: the analyzer recovers the original value-import binding through the SDK's lexical scope resolver. A class name or a same-name import elsewhere in the file is not sufficient evidence.

- `File analysis routes > 0`, `Project model routes in file = 0`: investigate file discovery, roots, exclusions, or caching.
- Both counts are `0`: inspect the constructor resolution details and whether the route syntax is supported.
- Both counts are positive but the tool window is empty: check the active Endpoints filters or adapter/UI behavior.

## License

Apache-2.0
