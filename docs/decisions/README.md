# Architecture Decision Records

Significant design decisions of gwt-pluginless-devmode, with their context, alternatives and consequences.

| ADR | Decision | Status |
|---|---|---|
| [ADR-001](ADR-001-replace-only-the-browser-plugin.md) | Replace only the browser plugin, keep the `gwt-dev` DevMode runtime | Proposed |
| [ADR-002](ADR-002-synchronous-transport-with-trampoline.md) | Synchronous HTTP/JSON transport with a trampoline loop | Proposed |
| [ADR-003](ADR-003-bridge-inside-the-devmode-jvm.md) | Run the bridge inside the DevMode JVM | Proposed |
| [ADR-004](ADR-004-patch-gwt-dev-internals-via-reflection.md) | Patch `gwt-dev` internals via reflection, pinned to one `gwt-dev` version | Proposed |
| [ADR-005](ADR-005-electron-debug-browser-as-maven-artifacts.md) | Bundle an Electron debug browser, distributed as Maven artifacts | Proposed |

These records were written after the decisions were implemented; their dates are the dates of the records, not of the decisions.

## Writing a new ADR

- Copy the structure of an existing record: Status, Date, Context, Decision, Alternatives Considered, Consequences.
- Number records sequentially (`ADR-006-short-title.md`) and add them to the table above.
- Do not delete or rewrite accepted records. When a decision changes, add a new record that supersedes the old one and set the old one's status to `Superseded by ADR-XXX`.
