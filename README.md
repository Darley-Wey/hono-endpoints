# Hono Endpoints

JetBrains IDE support for discovering and navigating [Hono](https://hono.dev/) routes in the built-in **Endpoints** tool window.

> Early development. The first milestone is native Hono route discovery for WebStorm, including composed routes and source navigation.

## Planned MVP

- Detect Hono applications in JavaScript and TypeScript
- Discover `get`, `post`, `put`, `patch`, `delete`, and `options` routes
- Support chained route declarations
- Resolve `route()` composition and `basePath()` prefixes
- Show resolved routes in the built-in JetBrains Endpoints tool window
- Navigate from an endpoint back to its Hono route declaration

## Development

The plugin targets WebStorm 2026.2 and uses the IntelliJ Platform Gradle Plugin 2.x.

More setup and architecture documentation will be added as the first provider is implemented.

## License

Apache-2.0
