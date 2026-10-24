# ADR-001: Replace only the browser plugin, keep the gwt-dev DevMode runtime

## Status
Proposed

## Date
2026-10-24

## Context
GWT Classic DevMode runs client code on the JVM, which lets developers debug it as Java: breakpoints, stepping, watches and Evaluate Expression in the IDE. The browser reaches the JVM through a native NPAPI/XPCOM plugin that speaks the `BrowserChannel` protocol to the DevMode code server. Browsers removed support for such plugins in 2014, so Classic DevMode no longer works in any current browser.

The JVM side, however, is still part of `gwt-dev` (2.12.2 at the time of writing): the code server, `CompilingClassLoader`, the JSO class rewriter and JSNI dispatch all work. Only the browser end of `BrowserChannel` is missing.

Requirements:
- Debug client code as Java on the JVM, as in original Classic DevMode.
- Work in current browsers, without native plugins.
- Behave like original Classic DevMode, so existing GWT applications run unchanged.
- No changes to the GWT dependency an application uses.

## Decision
Replace only the browser plugin. A JavaScript client (`devmode-bridge.js`) provides the `__gwt_HostedModePlugin` object that GWT's `<module>.devmode.js` bootstrap expects, and a local bridge (`BridgeServer`) translates its messages 1:1 into `BrowserChannel` v3 for the unchanged DevMode code server. `BridgeLinker`, registered under the linker name `xsiframe`, prepends the client to the bootstrap script, so GWT's own `<when-linker-added name="xsiframe"/>` rules keep applying.

## Alternatives Considered

### Super Dev Mode
- Pros: official, maintained, works in current browsers.
- Cons: code is compiled to JavaScript and debugged in the browser through source maps; there is no JVM, so no Java debugger and no Java Evaluate Expression.
- Rejected: JVM debugging is the whole point of this project.

### Fork or reimplement the DevMode runtime
- Pros: full control over the JVM side.
- Cons: a large, complex code base to maintain; applications would have to replace their `gwt-dev` dependency; behaviour could drift from the original.
- Rejected: the existing runtime works; only the plugin is missing.

### Browser extension with native messaging in place of the plugin
- Pros: closest to the original plugin architecture.
- Cons: extension and native messaging APIs are asynchronous, while GWT-generated code needs every Java call to return synchronously (see [ADR-002](ADR-002-synchronous-transport-with-trampoline.md)); one extension per browser to build and distribute.
- Rejected: cannot provide a synchronous channel to the page.

## Consequences
- DevMode behaviour (class loading, JSNI, exceptions, object identity) is the original one; the project's surface is the plugin protocol only, specified in [PROTOCOL.md](../PROTOCOL.md).
- The client must replicate the semantics of the NPAPI plugin, e.g. Java objects as `Proxy` wrappers with `WeakRef`/`FinalizationRegistry` to release references.
- Classic DevMode constraints remain: the Swing DevMode window is required (no headless mode).
- Bugs in the `gwt-dev` runtime that never surfaced with Java 8 still affect users; fixing them requires touching `gwt-dev` internals ([ADR-004](ADR-004-patch-gwt-dev-internals-via-reflection.md)).
