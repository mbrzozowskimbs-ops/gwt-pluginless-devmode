# gwt-pluginless-devmode

GWT Classic DevMode without a browser plugin. Client-side GWT code runs on the JVM and you debug it as plain Java in your IDE (breakpoints, watches, Evaluate Expression); the browser (Chrome, Firefox or the bundled Electron debug browser) only serves as a remote DOM.

Unofficial project, not affiliated with the GWT Project or Google.

## Why

Classic DevMode stopped working when browsers removed the native NPAPI/XPCOM plugins it relied on (2014). The JVM side of DevMode is still part of `gwt-dev` (this project targets 2.12.2) and works fine. Pluginless DevMode replaces only the browser plugin, with a JavaScript client and a local HTTP bridge, so DevMode itself runs unchanged.

## How it works

```
Browser
  page ?gwt.codesvr=127.0.0.1:9876
  └─ <module>.devmode.js + devmode-bridge.js   (stands in for __gwt_HostedModePlugin)
        │  synchronous HTTP, JSON                docs/PROTOCOL.md
        ▼
BridgeServer :9876                              (pluginless-devmode-bridge)
        │  BrowserChannel v3, TCP
        ▼
GWT DevMode -nosuperDevMode, code server :9997
        │  JDWP
        ▼
IDE — Java debugger
```

1. `BridgeLinker` prepends `devmode-bridge.js` to the `<module>.devmode.js` bootstrap script that DevMode generates.
2. GWT's bootstrap looks for the browser plugin and finds the object provided by `devmode-bridge.js` instead.
3. Every Java ↔ JS round trip becomes a synchronous HTTP request to `BridgeServer`, which translates it 1:1 into the native `BrowserChannel` protocol spoken by the DevMode code server.

The wire format is specified in [docs/PROTOCOL.md](docs/PROTOCOL.md).

### Design decisions

The full context, alternatives and consequences of each decision are recorded as [Architecture Decision Records](docs/decisions/README.md).

- **Keep `gwt-dev` unchanged, replace only the plugin.** The JVM runtime (class rewriting, JSNI dispatch, the code server) is reused as is, so behaviour matches original Classic DevMode.
- **Synchronous transport.** Java code expects JS calls to return a value immediately, so the page thread must block during a round trip: synchronous XHR in regular browsers, synchronous IPC in the Electron debug browser. A trampoline loop in `devmode-bridge.js` keeps the JS stack flat for sequential calls (see [PROTOCOL.md](docs/PROTOCOL.md#trampoline-loop-browser)).
- **Bridge in the DevMode JVM.** `PluginlessDevMode` is a drop-in main class that starts the bridge in-process; a separate `BridgeServer` process is supported for other launch setups.
- **Targeted fixes to `gwt-dev` internals.** `PluginlessDevMode` repairs nestmate attributes broken by GWT's JSO class rewriter and exposes JDK platform-module classes to client code (see [Known limitations](#known-limitations)). These rely on a specific `gwt-dev` version, checked at startup.
- **Loopback only.** The bridge executes arbitrary code in the JVM and the browser, so it listens on loopback only and checks `Host` (against DNS rebinding) and `Origin`.

## Quick start

Requirements: JDK 17, Maven 3.9+, Linux, macOS or Windows. You do not need to install Node.js (see [Build notes](#build-notes)).

```bash
git clone https://github.com/mbrzozowskimbs-ops/gwt-pluginless-devmode.git
cd gwt-pluginless-devmode
mvn install
mvn -pl pluginless-devmode-sample exec:exec
```

The last command starts DevMode (Swing window, code server on :9997, Jetty on :8888) with the bridge on :9876, and once the module is linked it opens the sample page in the Electron debug browser. The browser closes when DevMode exits. The sample exercises Java ↔ JS calls, nested calls, exceptions and 10,000 DOM operations; the overall result is written to the `#sample-result` element.

In IntelliJ IDEA use the **"Sample - Classic DevMode"** run configuration (from `.run/`) with Run or Debug, and set breakpoints in `SampleEntryPoint`.

## Commands

| Command | Description |
|---|---|
| `mvn install` | Full build with Java and JavaScript tests |
| `mvn install -Dskip.js.tests=true -Dskip.electron=true` | Build without downloading Node.js and Electron |
| `mvn -pl pluginless-devmode-bridge test` | Java tests of the bridge |
| `mvn -pl pluginless-devmode-linker test` | JavaScript tests of `devmode-bridge.js` |
| `mvn install -Pelectron-dist` | Also build the `pluginless-devmode-electron-dist` jars (4 platforms) |
| `mvn -pl pluginless-devmode-sample exec:exec` | Run the sample (requires a prior `mvn install`) |
| `mvn -pl pluginless-devmode-electron frontend:npm@start "-Dpluginless.url=<url>"` | Run the Electron debug browser alone |

### Build notes

`frontend-maven-plugin` downloads Node.js (version `node.version` in the root `pom.xml`) into `target/node` of `pluginless-devmode-linker` (for the `devmode-bridge.js` tests) and `pluginless-devmode-electron`. The `electron` npm package downloads Chromium for the current platform once (about 100 MB, into the user cache). Behind a corporate proxy, Node.js uses the proxy from Maven's `settings.xml`; Electron may also need `ELECTRON_MIRROR` or an npm proxy.

## Modules

| Module | Contents |
|---|---|
| `pluginless-devmode-bridge` | `BridgeServer`: HTTP/JSON ↔ `BrowserChannel` bridge on the JDK `HttpServer`, loopback only; `PluginlessDevMode` main class |
| `pluginless-devmode-linker` | GWT module `cloud.rubibubi.gwt.pluginless.PluginlessDevMode` and `BridgeLinker` (registered as `xsiframe`), which prepends `devmode-bridge.js` to `<module>.devmode.js` |
| `pluginless-devmode-electron` | Debug browser: Chromium (Electron) with synchronous IPC to the bridge instead of synchronous XHR |
| `pluginless-devmode-electron-dist` | Official Electron builds repackaged as jars, one per platform (profile `electron-dist`) |
| `pluginless-devmode-sample` | Sample GWT module used for manual and integration checks |

## Using it in your application

1. In the GWT module you use for development, add `<inherits name="cloud.rubibubi.gwt.pluginless.PluginlessDevMode"/>` and a dependency on `cloud.rubibubi.gwt.pluginless:pluginless-devmode-linker`.
2. Add a dependency on `cloud.rubibubi.gwt.pluginless:pluginless-devmode-bridge` and start `cloud.rubibubi.gwt.pluginless.bridge.PluginlessDevMode -nosuperDevMode -noserver …` instead of `com.google.gwt.dev.DevMode`, with the same arguments and these JVM options:
   ```
   --add-opens java.base/java.lang=ALL-UNNAMED --add-opens java.base/java.util=ALL-UNNAMED --add-exports java.desktop/com.apple.eawt=ALL-UNNAMED
   ```
   DevMode uses reflection on JRE classes referenced from JSNI, e.g. `LinkedHashMap.accessOrder` in the GWT-RPC serializer.
3. Your application server must serve the `<module>.devmode.js` generated by DevMode at the module URL (point `-war` at the exploded application, or copy the file). Alternatively, let the bridge front your server with `pluginless.proxy` (see [Application server proxy](#application-server-proxy)).
4. If the page is not served from `localhost`, set `-Dpluginless.allowOrigin=http://app.local:8080`.
5. Open the application with `?gwt.codesvr=127.0.0.1:9876`, or add `-Dpluginless.browser=electron`, a `-startupUrl` and a `pluginless-devmode-electron-dist` dependency (see [Debug browser](#debug-browser-electron)) to have the page opened automatically.

When debugging, set breakpoints to **Suspend: Thread** (right-click a breakpoint → Thread → Make Default). With "Suspend: All" the bridge threads stop too, and an *Evaluate Expression* that touches the DOM hangs.

## Configuration

### System properties

With `PluginlessDevMode`, the code server port and the `-war` directory come from the DevMode arguments; everything else comes from system properties:

| Property | Meaning | Default |
|---|---|---|
| `pluginless.port` | Bridge port (the `gwt.codesvr` value) | `9876` |
| `pluginless.proxy` | Application server for DevMode with `-noserver`, e.g. `http://localhost:8080` | none |
| `pluginless.warContext` | URL prefix of the `-war` directory, e.g. `/myapp` | `/` |
| `pluginless.allowOrigin` | Allowed page origins, comma separated | any loopback origin |
| `pluginless.trace` | `true` prints every protocol message | `false` |
| `pluginless.statsFile` | File for round trip statistics | `pluginless-devmode-stats.log` in `-logdir` |
| `pluginless.browser` | `electron` opens `-startupUrl` in the debug browser from `pluginless-devmode-electron-dist` on the classpath | none |
| `pluginless.cacheDir` | Directory the debug browser is unpacked into | `~/.pluginless-devmode` |
| `pluginless.electron` | A `pluginless-devmode-electron` directory with npm packages installed; takes precedence over `pluginless.browser` | none |

### Standalone bridge

A separate `BridgeServer` process (IntelliJ configuration "Pluginless DevMode bridge") is only needed when DevMode is started some other way:

```
--port <port>               HTTP port for the browser = the gwt.codesvr value (default 9876)
--code-server <host:port>   DevMode code server (default 127.0.0.1:9997)
--allow-origin <origin>     allowed page origin, repeatable (default: any loopback origin)
--proxy <url>               forward page requests to an application server, e.g. http://localhost:8080
--war <dir>                 DevMode -war directory: serve <module>.devmode.js and /policies/*.gwt.rpc
--war-context <path>        URL prefix of the -war directory, e.g. /myapp (default /)
--trace                     print every protocol message
```

### Application server proxy

With `--proxy` and `--war` (or `pluginless.proxy` with `PluginlessDevMode`), the bridge sits in front of your application server (DevMode with `-noserver`). It serves the files generated by DevMode itself and forwards everything else with the original `Host` header. An application server started with `-Dgwt.codeserver.port=<bridge port>` fetches missing GWT-RPC policies from the bridge's `/policies/` (the `RemoteServiceServlet` mechanism intended for Super Dev Mode).

## Debug browser (Electron)

`pluginless-devmode-electron` is Chromium in which round trips to the bridge use synchronous IPC instead of synchronous XHR. `PluginlessDevMode` opens the first `-startupUrl` in it after the modules are linked: the URL is resolved against the DevMode server (`-port`) and `gwt.codesvr=127.0.0.1:<bridge port>` is appended. On Linux it adds `--no-sandbox`, because Electron does not start without a setuid `chrome-sandbox`.

Where the browser comes from:

- **`-Dpluginless.browser=electron`** (for applications): Electron from the `pluginless-devmode-electron-dist` jar for the current platform on the classpath, and the app files (`main.js`, toolbar) from the `pluginless-devmode-bridge` jar. On first start they are unpacked into `~/.pluginless-devmode` (`-Dpluginless.cacheDir`): `electron/<version>-<platform>` (about 260 MB, a few seconds, with symlinks and exec bits) and `app/<hash>`. Later starts reuse the cache. No Node.js or npm needed.
- **`-Dpluginless.electron=<pluginless-devmode-electron directory>`** (for working on the browser itself): Electron from that directory's `node_modules` after `mvn install`, app files straight from the directory. Takes precedence over `pluginless.browser`.

The `electron-dist` profile builds the `pluginless-devmode-electron-dist` jars (version = Electron version; classifiers `darwin-arm64`, `darwin-x64`, `win32-x64`, `linux-x64`). It downloads the official Electron zips (`-Delectron.mirror=<url>` for a mirror with the same layout), verifies them against the SHA-256 checksums in the pom and keeps them in the local Maven repository cache, so they are downloaded only once:

```bash
mvn install -Pelectron-dist
```

An application only needs the jar for the developer's platform, e.g. in a Maven profile activated by `<os>`:

```xml
<dependency>
    <groupId>cloud.rubibubi.gwt.pluginless</groupId>
    <artifactId>pluginless-devmode-electron-dist</artifactId>
    <version>45.0.0</version>
    <classifier>darwin-arm64</classifier>
    <scope>provided</scope>
</dependency>
```

A toolbar above the page offers back, forward, reload/stop, an address field (adds `http://` when no scheme is given) and the page's DevTools. The toolbar is a separate view, so it never touches the application DOM. Shortcuts act on the page even when the toolbar has focus:

| Action | macOS | Windows / Linux |
|---|---|---|
| Reload | `Cmd+R`, `F5` | `Ctrl+R`, `F5` |
| Reload ignoring cache | `Shift+Cmd+R` | `Shift+Ctrl+R` |
| Back / forward | `Cmd+[` / `Cmd+]` | `Alt+←` / `Alt+→` |
| Address field | `Cmd+L` | `Ctrl+L` |
| DevTools | `Alt+Cmd+I`, `F12` | `Ctrl+Shift+I`, `F12` |

On Windows and Linux the menu bar is hidden; press `Alt` to show it.

To use another browser (Chrome, Firefox), leave out `pluginless.electron` and `pluginless.browser` and open `http://localhost:8888/sample/Sample.html?gwt.codesvr=127.0.0.1:9876` yourself. The DevMode window suggests `gwt.codesvr=…:9997`; change the port to the bridge port (9876).

## Known limitations

- **Performance:** each Java ↔ JS round trip is one synchronous HTTP request, about 0.45 ms. Per-session statistics are written to `pluginless-devmode-stats.log` in the `-logdir` directory.
- **Breakpoints freeze the tab:** while Java is stopped at a breakpoint the page cannot run; after a few dozen seconds Chrome may offer to close it.
- **Swing window:** Classic DevMode needs its Swing window (it does not run with `java.awt.headless=true`).
- **One tab, one session:** each tab is one session with its own connection to the code server.
- **Synchronous XHR** is deprecated in browsers. It still works in Chrome and Firefox; the Electron debug browser does not depend on it.
- **Nestmates (Java 11+):** the JSO class rewriter in `gwt-dev` does not know the `NestHost`/`NestMembers` attributes, so with the stock `DevMode` client code compiled with `-sourceLevel` 11+ fails with `IllegalAccessError … not a nest member` (e.g. `Style$Unit$1`). `PluginlessDevMode` repairs these attributes (`JsoNestRepair`); the fix is not active when starting through `com.google.gwt.dev.DevMode`.
- **JRE classes from platform modules (Java 9+):** the stock `DevMode` cannot load e.g. `java.sql.Timestamp` in client code (`SecurityException: Prohibited package name: java.sql`), and after that error stops sending the JSNI of subsequent classes (`window[methodName] is undefined`). `PluginlessDevMode` gives client code the JDK classes instead (`PlatformJavaClasses`).
- **GWT version:** the project is built against a specific `gwt-dev` version (`gwt.version` in the root `pom.xml`) because `PluginlessDevMode` uses its internals (class rewriter, class loader). With a different version on the classpath it prints `WARNING: Pluginless DevMode is built with GWT …` at startup; rebuild the project with the GWT version your application uses.
- **Security:** the bridge executes arbitrary code in the JVM and the browser. Use it only locally, in a development environment.

## Contributing

- Code, comments, Javadoc and documentation are written in English.
- Java: four-space indentation, JUnit 5 + AssertJ tests named `*Test`. JavaScript: two-space indentation, `node:test` tests in `pluginless-devmode-linker/src/test/js/*.test.cjs`.
- Add a regression test for every behaviour change. Bridge tests use `FakeCodeServer` instead of a real DevMode.
- Keep [docs/PROTOCOL.md](docs/PROTOCOL.md) in sync when changing messages or value encoding.
- Record significant design decisions as ADRs in [docs/decisions](docs/decisions/README.md).
- For changes that span the JVM and the browser, run the sample and check `#sample-result`.
- Branches: `main` (releases) and `develop` (integration) are protected; work on feature branches and open pull requests against `develop`.

## License

Copyright 2026 Marek Brzozowski

Licensed under the [Apache License, Version 2.0](LICENSE).

The `pluginless-devmode-electron-dist` jars repackage official Electron builds, which are distributed under their own licenses (Electron: MIT; Chromium and its dependencies: see `LICENSES.chromium.html` inside each `electron.zip`).
