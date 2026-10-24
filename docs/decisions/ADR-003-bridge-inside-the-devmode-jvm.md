# ADR-003: Run the bridge inside the DevMode JVM

## Status
Proposed

## Date
2026-10-24

## Context
The bridge ([ADR-001](ADR-001-replace-only-the-browser-plugin.md)) must run while DevMode runs and must know the code server port, which DevMode may choose itself (`-codeServerPort auto`). It also needs DevMode's `-war` directory to serve `<module>.devmode.js` and GWT-RPC policies in proxy mode. Starting, stopping and configuring a second process alongside DevMode adds friction to every debugging session.

## Decision
Provide `PluginlessDevMode`, a drop-in main class that extends `com.google.gwt.dev.DevMode`. It starts `BridgeServer` in the same JVM and then runs DevMode with unchanged arguments:
- the code server port is taken from DevMode once it is bound, and `-war` from the DevMode arguments;
- everything else comes from `pluginless.*` system properties;
- the bridge stops with DevMode, and DevMode opens the debug browser after the modules are linked.

`BridgeServer` remains runnable as a standalone process (`main` with `--port`, `--code-server`, …) for setups where DevMode is started some other way.

## Alternatives Considered

### Standalone bridge process only
- Pros: no coupling to DevMode classes; works with any DevMode launcher.
- Cons: two run configurations; the code server port has to be fixed and kept in sync; lifecycles are independent.
- Rejected as the default, kept as an option.

### Java agent attached to DevMode
- Pros: no change of main class.
- Cons: an extra `-javaagent` option and a separate artifact; lifecycle and access to DevMode state through instrumentation, which is harder to understand and test.
- Rejected: a subclass of `DevMode` reaches the same goal with plain Java.

## Consequences
- One run configuration and one process; switching an existing project means changing the main class and adding JVM options.
- Bridge threads live in the debugged JVM: with breakpoints set to "Suspend: All", they stop too, and an Evaluate Expression that touches the DOM waits forever. Documentation recommends "Suspend: Thread".
- Running in DevMode's JVM makes the fixes of [ADR-004](ADR-004-patch-gwt-dev-internals-via-reflection.md) possible, since they must act on DevMode's own objects before modules load. They are active only when DevMode is started through `PluginlessDevMode`.
