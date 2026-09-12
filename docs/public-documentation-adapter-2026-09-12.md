# Public documentation adapter — 2026-09-12

The Endpoints documentation bridge now uses public APIs and gives each route its own call-site context. The implementation is enabled in `1.0.1-SNAPSHOT`. Cloud verification passed; a live IDE comparison remains pending because the workstation was locked.

## Implementation

- `HonoMethodDocumentationTargetProvider` supplies an independent `HonoDocumentationTarget` only for marked endpoint method references without an original element. Editor requests retain their existing native path.
- Each target owns smart pointers to the method reference and original identifier. The asynchronous result uses a cancellable read action and restores these pointers before generating content. Pending requests follow edits and become invalid when their call is deleted.
- Method resolution uses the reference PSI. The public `LanguageDocumentation` registry and `CompositeDocumentationProvider` select the native language providers in declaration, source-file, and base-language order. The explicit original element is passed to the provider; no shared original-element user data is read or written by the adapter.
- The native provider's HTML and definition details are returned unchanged. The plugin does not parse handlers, select or rewrite signatures, or construct documentation HTML.
- `HonoDocumentationLinkHandler` uses the platform's public PSI-link protocol constants and the native provider's `getDocumentationElementForLink` method. It preserves link anchors and leaves external URLs and unrelated editor targets to the platform.
- Diagnostic version information comes from standard JAR package metadata. The build adds `Implementation-Version`, removing the `PluginManagerCore.getPlugin` dependency.
- CI now fails on `INTERNAL_API_USAGES`, in addition to the existing compatibility and OverrideOnly checks. Startup warmup remains absent.

## Automated verification

[GitHub Actions run 34690085178](https://github.com/Darley-Wey/hono-endpoints/actions/runs/34690085178) tested commit `35af77a3eb4eb50f8feefd4fe37d353105140512` using WebStorm 2026.2.2, build `WS-262.10315.144`, and Java 25.

All 146 tests passed, with 0 failures, 0 errors, and 0 skipped tests. The nine public-adapter cases cover:

1. Complete native HTML equality for a generic TypeScript call.
2. Two calls sharing a declaration retaining independent content, even when the declaration contains another call's stored original element.
3. A pending asynchronous result following an edited call path.
4. Deletion invalidating both the target pointer and pending result.
5. Native type links preserving documentation content, presentation, and source-navigation offsets.
6. External URLs, unresolved type links, and unrelated native editor targets.
7. JavaScript documentation equality.
8. JavaScript calls resolving to TypeScript declarations with native language-provider selection.
9. Registration of the new link handler.

The existing Endpoints integration test also confirms that marked requests receive the new target and produce the same complete native HTML as explicit editor context.

Plugin Verifier reports:

    Compatible. 2 usages of deprecated API

There are no reported Internal API, OverrideOnly, or scheduled-for-removal usages. The two remaining deprecated usages are the existing `EndpointsProvider.getEndpointData` override and its superclass call. No `DocumentationManager` dependency remains in production code.

## Artifact

- Archive: `build/distributions/hono-endpoints-1.0.1-SNAPSHOT.zip`
- Size: 97,567 bytes
- SHA-256: `f20e294f602811ea8fb7ddba3b746907ab34299f9095b032bfb6a81f7ca0f818`
- The descriptor and JAR implementation version both identify `1.0.1-SNAPSHOT`.
- The archive includes the target and link-handler registrations and excludes test classes and startup warmup classes.

The ZIP was downloaded from the successful CI run; it was not rebuilt locally. The run's test-report and plugin-verifier-report artifacts provide the detailed evidence.

## Remaining validation

These are native PSI and documentation-provider tests, not a live TypeScript-service or mouse-hover test. They cover local generated documentation and PSI links; automatic external-document fetching and relative-image behavior are outside this validation.

The local IDE is IntelliJ IDEA 2026.2.2. Live project validation could not proceed because the computer-use tool reported that the Mac was locked. Installation, real-project hover comparison, and interactive link checks remain pending. This preview has not been uploaded to Marketplace, and cloud compatibility verification is not Marketplace approval.
