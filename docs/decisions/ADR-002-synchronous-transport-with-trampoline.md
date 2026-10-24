# ADR-002: Synchronous HTTP/JSON transport with a trampoline loop

## Status
Proposed

## Date
2026-10-24

## Context
In Classic DevMode, Java code running on the JVM calls JavaScript in the page (DOM access, JSNI) and expects the result immediately, as a return value; JavaScript calls back into Java the same way. GWT-generated code and application JSNI are written synchronously. The page's JavaScript thread therefore has to block until the JVM answers, and the JVM has to block while the page runs a call on its behalf. Calls nest arbitrarily (Java → JS → Java → …).

Browsers offer almost no way to block the page thread while waiting for I/O. Sequential calls are very frequent (thousands of DOM operations in one Java method), so the transport must not consume stack per call.

## Decision
- Every round trip is a synchronous `XMLHttpRequest` (`POST /pluginless-devmode/session/<id>`) whose body is a JSON array of browser messages and whose response is a JSON array of commands ([PROTOCOL.md](../PROTOCOL.md)).
- Requests use `Content-Type: text/plain`, a CORS "simple" request, so there is no preflight round trip.
- A trampoline loop (`transact` in `devmode-bridge.js`) runs requests from the JVM (`invoke`, `get`, `set`) and sends their results in the next request, instead of recursing. The stack grows only with real nesting.
- Asynchronous messages (`free`, `stats`) are batched in front of the next request.
- On page unload the session is closed with `navigator.sendBeacon`, because synchronous XHR is not allowed during unload.
- The transport is pluggable: in the Electron debug browser, synchronous IPC replaces synchronous XHR ([ADR-005](ADR-005-electron-debug-browser-as-maven-artifacts.md)).

## Alternatives Considered

### WebSocket, fetch or other asynchronous transports
- Pros: modern, not deprecated, lower per-message overhead.
- Cons: the page thread cannot wait for an asynchronous response; GWT-generated code and JSNI would have to become asynchronous, which is not possible.
- Rejected: incompatible with synchronous Java ↔ JS calls.

### `SharedArrayBuffer` + `Atomics.wait`
- Pros: a real blocking wait on shared memory.
- Cons: browsers do not allow `Atomics.wait` on the main thread, where GWT code runs; `SharedArrayBuffer` also requires cross-origin isolation (COOP/COEP headers) on the application's pages.
- Rejected: not available on the main thread.

### One recursive request per call (no trampoline)
- Pros: simpler client.
- Cons: every sequential Java → JS call adds JS stack frames; loops with many DOM operations overflow the stack.
- Rejected: the trampoline keeps 100,000 sequential calls at constant stack depth (covered by the JavaScript tests).

## Consequences
- Each round trip costs about 0.45 ms; DOM-heavy code is noticeably slower than compiled JavaScript. `BridgeStats` reports per-phase timings to make this visible.
- Synchronous XHR on the main thread is deprecated. It still works in Chrome and Firefox, but may be removed; the Electron debug browser does not depend on it.
- While Java is suspended at a breakpoint, the page is frozen; browsers may offer to close an unresponsive tab.
- Values need an explicit JSON encoding with tags for `undefined`, non-finite numbers and object references.
- Session errors are returned as HTTP 200 with an `error` command, so the page can display them instead of failing with an opaque XHR error.
