# Native documentation investigation — 2026-09-09

Assessment: missing original call-site PSI is a reproducible cause of short native TypeScript documentation. The separate IdempotenceChecker error has not been reproduced by a controlled test, and has not been established as the cause of short or stale Endpoints documentation.

Environment: WebStorm 2026.2.2, WS-262.10315.144, installed Hono Endpoints 0.2.10-SNAPSHOT; real project `/Users/darley/Projects/ticket-folder`. The diagnostic fixture was run with `-PlocalIdePath=/Users/darley/Applications/WebStorm.app`.

## Live observation

1. In `backend/src/ticket/routes.mts`, place the caret inside the first `.post` at line 114 (the `/ticket/list` route).
2. Invoke native Quick Documentation (Ctrl+J). It initially says it is fetching documentation, then displays the contextual signature, including `/ticket/list`, `TicketListBody`, response records/total, and `+22 overloads`.
3. Close the popup, search `/ticket/list` in Endpoints, and select the exact `/ticket/list [POST]` entry from routes.mts.
4. Following the user's correction, double-click the selected endpoint. Navigation succeeds and focuses the route path at line 115, column 5. The native Endpoints Documentation panel still displays `post: HandlerInterface<BlankEnv, "post", BlankSchema, "/", "/">`.

This is a live editor Quick Documentation versus Endpoints comparison. A literal mouse-hover capture and byte-for-byte equality of all rendered content were not performed. Initial CUA focus/screenshot failures were resolved by raising the window; they were automation limitations, not evidence that the user's Mac was locked.

## Controlled reproduction

`HonoNativeDocumentationContextTest` uses the installed IDE's real TypeScript PSI and `JSDocumentationProvider`. Its minimal fixture declares a property whose type is an interface containing a generic call signature, matching the relevant shape of Hono's method properties. It does not load ticket-folder, parse handlers, render custom HTML, or alter signatures.

For the same resolved property:

- `generateDoc(resolved, null)` returns the short `post: HandlerInterface` definition.
- `generateDoc(resolved, originalMethodIdentifier)` returns the callable signature with `path`, `handler`, and return type.
- Alternating the two calls three more times keeps both outputs unchanged. Rendering contextual documentation first does not repair a later context-free render.

The fixture passes without the IdempotenceChecker error. This establishes that short documentation can occur independently of that error. The minimal test does not establish the exact real-project inferred types or emulate a live tsserver session.

## Installed platform code path

Inspected bytecode from the actual installed build with `javap -p -c`:

- `EndpointsDocumentationSidePanel.loadDefaultDocumentation`'s lambda obtains the provider's documentation PSI and calls `psiDocumentationTargets(element, null)`, then selects the first target.
- `TypeScriptDocumentationTargetProvider` resolves a `JSReferenceExpression` to documentation targets. In the single-result path, it forwards the supplied original element to `createPsiDocumentationTarget`; an original `null` remains `null`.
- `JSDocumentationProvider.getEffectiveElement` calls `adjustAliasedCall`. That method uses the original call identifier to locate a call signature through `JSDeclarationEvaluator` and `TypeScriptSignatureChooser.isCallSignatureOf`. Without the original context, this conversion can leave the property declaration as the documentation subject.
- Both Hono documentation entry points currently return the route's method-reference PSI. That identity alone does not guarantee editor-identical content once the platform resolves it without original context.

Consequently, more quickinfo retries or startup warming cannot supply the missing original element in this native target path. They are not an evidence-based fix for the demonstrated discrepancy.

## SEVERE assessment

The existing 03:03:17.133 stack reports non-equivalent `JSFunctionItemFromTSSignature` results in `TypeScriptSignatureChooser.resolveOverloads`. It includes `JSDocumentationProvider`, `PsiElementDocumentationTarget`, and Translation's wrapper; it has no Hono source frame. Another occurrence exists at 01:19:39.090. This does not identify which plugin initiated the differing computations.

`CachedValueBase.getValueWithLock` can compare a newly computed value with another up-to-date cached value, report a mismatch, and return cached data. `IdempotenceChecker.reportFailure` logs a `PluginException` using `Logger.error` and returns; this stack alone is not proof that documentation generation threw or stopped. Its normal non-test reporting is deduplicated by provider class, so no new log line cannot prove there was no recurrence.

No new IdempotenceChecker report was recorded during this comparison (latest observed log timestamp 03:36:32). No deterministic trigger for that mismatch has been isolated. Stale documentation after source edits has not been reproduced or ruled out. Identical short property descriptions across different calls can look stale, but this observation alone does not prove a stale-cache defect.

## Implemented fix and validation

`HonoMethodDocumentationTargetProvider` now handles only marked Hono endpoint method references received without an original element. It delegates the same method-reference PSI back to the native provider chain with the method identifier as the original context. The provider returns no custom target or HTML, does not resolve handlers, and ignores editor requests that already have context.

The original quickinfo warmup did not restore the missing original element and was not required for documentation parity. It was removed from version 0.2.12-SNAPSHOT: the startup activity, bounded worker, TypeScript quickinfo requests, retry state, and their call sites are gone. This removes startup work rather than restoring the previous startup-freeze behavior.

The full suite against WebStorm 2026.2.2 passed after that removal: 125 tests, 0 failures, 0 errors. Command:

```sh
JAVA_HOME=/path/to/jdk ./gradlew test buildPlugin -PlocalIdePath=/Users/darley/Applications/WebStorm.app --console=plain
```

The 0.2.12-SNAPSHOT plugin archive includes the native documentation target provider and does not include the startup activity registration. No user plugin was disabled.
