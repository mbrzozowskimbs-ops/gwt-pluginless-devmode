# ADR-005: Bundle an Electron debug browser, distributed as Maven artifacts

## Status
Proposed

## Date
2026-10-24

## Context
The synchronous transport ([ADR-002](ADR-002-synchronous-transport-with-trampoline.md)) relies on synchronous XHR on the main thread, which browsers have deprecated and may remove. Regular browsers also show unresponsive-page warnings while Java is suspended at a breakpoint, and the developer has to append `?gwt.codesvr=<bridge port>` to the URL by hand (the DevMode window suggests the code server port, not the bridge port).

The target users are Java developers using Maven; requiring Node.js or npm on their machines, or network downloads at DevMode startup, adds friction and breaks offline and corporate environments.

## Decision
- Provide a debug browser, `pluginless-devmode-electron`: Chromium (Electron) whose preload script exposes a synchronous IPC transport (`ipcRenderer.sendSync`) to the page; the main process performs the HTTP request to the bridge and accepts only bridge session URLs on the local machine. `devmode-bridge.js` uses this transport when it finds it in the window hierarchy, and synchronous XHR otherwise. A toolbar runs as a separate view, so it never touches the application DOM.
- `PluginlessDevMode` opens the first `-startupUrl` in the debug browser after the modules are linked, with `gwt.codesvr` set to the bridge port, and closes it with DevMode.
- Distribute Electron as Maven artifacts: `pluginless-devmode-electron-dist` repackages the official Electron zips into one jar per platform (classifiers `darwin-arm64`, `darwin-x64`, `win32-x64`, `linux-x64`), verified by SHA-256 checksums in the pom and versioned like Electron. The app files are bundled in the `pluginless-devmode-bridge` jar.
- On first use, `ElectronDistribution` unpacks Electron into `~/.pluginless-devmode` under a file lock with a completion marker (preserving symlinks and exec bits), and the app files into a content-hashed directory.
- For work on the browser itself, `pluginless.electron` points at a `pluginless-devmode-electron` directory with npm packages installed, and takes precedence.

## Alternatives Considered

### Regular browsers only
- Pros: nothing to download or maintain.
- Cons: depends entirely on deprecated synchronous XHR; manual URL handling; unresponsive-page warnings.
- Rejected as the only option; Chrome and Firefox remain supported.

### Browser extension providing the transport
- Pros: uses the developer's own browser.
- Cons: extension messaging is asynchronous, so it cannot replace synchronous XHR; one extension per browser, with store distribution.
- Rejected: does not remove the dependency on synchronous XHR.

### Require Node.js/npm and install Electron through npm
- Pros: standard Electron workflow.
- Cons: extra tooling for Java developers; npm downloads at install time.
- Rejected for users; kept for developing the browser itself (`pluginless.electron`).

### Download Electron at DevMode startup
- Pros: small artifacts.
- Cons: network access on first start; no Maven caching, mirrors or checksum verification in the build.
- Rejected: Maven artifacts reuse existing repository, mirror and proxy infrastructure.

## Consequences
- The Electron transport does not depend on synchronous XHR, and the debugging session starts with one click.
- Large artifacts: about 115–160 MB per platform jar and about 260 MB unpacked in the cache. Applications add only the jar for the developer's platform, typically in an OS-activated Maven profile with `provided` scope.
- Upgrading Electron means updating the version and checksums in the `pluginless-devmode-electron-dist` pom and `pluginless-devmode-electron/package.json`. Adding an app file means updating `ElectronDistribution.APP_FILES` and the resource includes of the bridge.
- On Linux, Electron is started with `--no-sandbox`, because it does not start without a setuid `chrome-sandbox`.
- Electron and Chromium are redistributed under their own licenses, included in each `electron.zip`.
