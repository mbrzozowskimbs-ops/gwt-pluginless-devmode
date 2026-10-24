# ADR-004: Patch gwt-dev internals via reflection, pinned to one gwt-dev version

## Status
Proposed

## Date
2026-10-24

## Context
Classic DevMode was last widely used on Java 8. On current JDKs the unchanged `gwt-dev` runtime ([ADR-001](ADR-001-replace-only-the-browser-plugin.md)) has two defects that break ordinary client code:

1. **Nestmates (Java 11+).** `HostedModeClassRewriter` splits every `JavaScriptObject` subtype `C` into an interface `C` and an implementation class `C$`, but predates the `NestHost`/`NestMembers` attributes. The rewritten nest is inconsistent, and the JVM rejects private access inside it with `IllegalAccessError … not a nest member` (e.g. `Style$Unit$1`).
2. **Platform modules (Java 9+).** `CompilingClassLoader` looks JRE classes up in a parentless class loader that sees only boot modules. A class of a platform module such as `java.sql.Timestamp` falls back to GWT emulation and fails with `SecurityException: Prohibited package name`; afterwards no JSNI of later classes reaches the browser.

The affected state is private: the `classRewriter` field of each `CompilingClassLoader` and the static `BRIDGE_CLASS_NAMES` map.

## Decision
Fix both defects from `PluginlessDevMode` ([ADR-003](ADR-003-bridge-inside-the-devmode-jvm.md)), without modifying `gwt-dev`:
- `NestRepairingBrowserWidgetHost` decorates DevMode's `browserHost`; for every new module it installs `NestRepairingClassRewriter` into the module's `CompilingClassLoader` (replacing the private `classRewriter` field via reflection) before the first class loads. Every rewritten class then passes through `JsoNestRepair` (ASM), which makes the nest consistent again.
- `PlatformJavaClasses` adds the `java.*` classes of the platform modules to `CompilingClassLoader.BRIDGE_CLASS_NAMES` via reflection, before any module loads, so client code gets the JDK classes as on Java 8.
- The `gwt-dev` version the project is built against is filtered into `bridge.properties`; `GwtVersionCheck` prints a warning at startup when a different version is on the classpath.

## Alternatives Considered

### Patched or forked gwt-dev
- Pros: direct source changes, no reflection.
- Cons: applications must replace their `gwt-dev` dependency with a custom build; the fork has to follow every GWT release.
- Rejected: contradicts "no changes to the GWT dependency an application uses".

### Fix upstream in GWT
- Pros: the proper long-term fix; removes the need for this code.
- Cons: does not help applications on current GWT versions.
- Not chosen as the only route; can be pursued in parallel, and these patches dropped for GWT versions that include a fix.

### Bytecode instrumentation through a Java agent
- Pros: can change any method, independent of field names.
- Cons: an extra `-javaagent` option; method-level bytecode patches depend on `gwt-dev` internals even more tightly than field access.
- Rejected: decorating existing extension points and replacing two fields is smaller and easier to test.

## Consequences
- Client code compiled with `-sourceLevel` 11+ and code using platform-module JRE classes work, but only when DevMode is started through `PluginlessDevMode`.
- The project depends on private names (`classRewriter`, `BRIDGE_CLASS_NAMES`) and the behaviour of one `gwt-dev` version (2.12.2). Upgrading GWT requires checking these internals, running `JsoNestRepairTest` and `PlatformJavaClassesTest`, and rebuilding; users on another version get a startup warning.
- If the private fields disappear, the fixes fail with an `IllegalStateException` naming the missing field (at startup for `BRIDGE_CLASS_NAMES`, when a module loads for `classRewriter`) instead of silently misbehaving.
