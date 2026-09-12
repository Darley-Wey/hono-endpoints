# Stored documentation context verification — 2026-09-12

Assessment: `DocumentationManager.storeOriginalElement(...)` is unsuitable as a replacement for the native documentation bridge. It can restore one call's signature, but calls sharing a declaration overwrite each other's rendering context. The overwrite is reproduced with native TypeScript PSI and documentation targets.

## Verification setup

- GitHub Actions on Ubuntu, Java 25, WebStorm 2026.2.2 (`WS-262.10315.144`). Both the test SDK and Plugin Verifier use this WebStorm version.
- Test revision: `745dda878bdf6fa5c467b9d4a752a562135d45a7`.
- Result: 137 tests passed, 0 failures, 0 errors, 0 skipped; all six new characterization tests passed. Plugin Verifier reports compatibility with two deprecated API usages and the same three existing internal API usages.
- [Validation run](https://github.com/Darley-Wey/hono-endpoints/actions/runs/34686574045). The `test-report` artifact contains the JUnit XML and HTML reports; `plugin-verifier-report` contains the separate compatibility report.
- [HonoStoredDocumentationContextTest](../src/test/kotlin/io/github/darleywey/honoendpoints/endpoints/HonoStoredDocumentationContextTest.kt) contains six characterization tests. Their assertions describe the observed platform behavior, including the undesirable overwrite.

The fixture declares an `app.post` property with three call signatures. Two calls resolve to the same property declaration, while their explicit editor contexts produce different native documentation:

    A: post("/ticket/list", context => 1)
       HandlerInterface(path: "/ticket/list", handler: (...) => number): { list: number }

    B: post("/ticket/create", context => "created")
       HandlerInterface(path: "/ticket/create", handler: (...) => string): { created: string }

The tests compare the complete native HTML, before any normalization. The excerpts above omit formatting for readability. No plugin code generates or rewrites documentation HTML.

The test references stay unmarked, so the existing Hono bridge cannot repair the requests under examination. The test-only `psiDocumentationTargets(reference, null)` calls reproduce the dispatch used by the platform's Endpoints panel. These test calls are not part of the distributed plugin.

## Observed behavior

| Case | Result |
| --- | --- |
| Save the original identifier on the method reference | The resolved native target still shows the short property documentation. The stored value does not follow reference resolution. |
| Save the identifier on the resolved declaration | A single call renders exactly like its explicit editor context, including after recreating the target through its pointer. |
| Store A, then B, and alternate three times | All existing targets read the last stored context. Re-rendering A after storing B returns B's complete native HTML. An editor target originally created with A's explicit context is also overridden. |
| Create separate native pointers for A and B | The pointer objects are distinct, but both dereference and render using B after B overwrites the shared declaration. |
| Change A's path from `/ticket/list` to `/ticket/update` and commit the document | The stored smart pointer follows the edit and the retained target renders the updated overload. This single-call success does not isolate it from B. |
| Delete the call whose identifier was stored | The original pointer becomes invalid, while the documentation target for the surviving declaration remains valid and falls back to the short property documentation. |

## Cause and API boundary

Inspection of the installed IntelliJ IDEA 2026.2.2 platform (`IU-262.10315.125`) shows:

1. `DocumentationManager.storeOriginalElement(project, original, target)` delegates to `DocumentationTargetFinder`, which writes one smart pointer into `target`'s `ORIGINAL_ELEMENT_KEY` user data.
2. `PsiElementDocumentationTarget.localDocParts` reads that key when documentation is computed. A valid stored element takes precedence over the target's own explicit source element.
3. The documentation pointer retains its target and explicit source pointers. It does not capture a separate copy of the stored original-element value.

The native WebStorm tests reproduce the resulting overwrite. It is visible with sequential operations; simultaneous execution is not required. A provider that merely writes the key and returns control to the native chain cannot guarantee which value a retained or later-rendered target will read.

The inspected `storeOriginalElement` method is public and has no Internal annotation, but `DocumentationManager` is deprecated with `forRemoval = true`. Public visibility does not make this mutable storage a suitable mechanism for independent Endpoints requests.

## Decision and limits

Keep this candidate out of the production bridge. The required replacement must carry the method-reference PSI and the original identifier with each native documentation target. A public delegation API, or a platform change preserving that context in Endpoints, remains necessary for the current native-target design. The existing feedback to JetBrains is associated with review request 9141703.

This run verifies a controlled native PSI fixture. It does not use a live TypeScript service, reproduce the real project's hover-and-scroll sequence, or establish the cause of the earlier IdempotenceChecker report. The shared-key behavior is a possible contributor to hover-dependent results, not proof of the historical incident's cause.

Production documentation code is unchanged by this investigation. The packaged plugin still contains the previously reported internal API usages, so the Marketplace rejection is unresolved. The cloud compatibility check must not be interpreted as Marketplace approval.
