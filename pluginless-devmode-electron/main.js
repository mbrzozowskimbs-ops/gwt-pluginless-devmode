'use strict';
/*
 * Pluginless DevMode debug browser: Chromium (Electron) whose pages reach the Pluginless DevMode bridge through a synchronous IPC call
 * (preload.js) instead of a synchronous XHR. The main process forwards each call to the bridge over a kept-alive
 * HTTP connection, so the bridge protocol stays the same.
 *
 * The window holds two views: a toolbar (toolbar.html: back, forward, reload, address bar, DevTools) and the page
 * below it. The toolbar is a separate web contents, so it never touches the page DOM.
 *
 * Usage: electron . <url> [--pluginless-devmode-transport=xhr]
 *   url                       page to open, with ?gwt.codesvr=<bridge host:port>
 *   --pluginless-devmode-transport=xhr   keep the synchronous XHR, for comparison
 */
const {app, BaseWindow, Menu, WebContentsView, ipcMain} = require('electron');
const http = require('http');
const path = require('path');

const TOOLBAR_HEIGHT = 40;

const args = process.argv.slice(app.isPackaged ? 1 : 2);
const xhrTransport = args.includes('--pluginless-devmode-transport=xhr');
const url = args.find((arg) => !arg.startsWith('-') && arg !== '.');
if (!url) {
  console.error('Usage: electron . <url> [--pluginless-devmode-transport=xhr]');
  process.exit(2);
}

const agent = new http.Agent({keepAlive: true, maxSockets: 4});

// Like the bridge, which listens on the loopback interface only (OriginPolicy.isLoopbackHost)
const LOOPBACK_HOSTS = new Set(['localhost', '127.0.0.1', '[::1]']);

// Any page can call the transport, so it reaches nothing but bridge sessions; returns the reason for a refusal
function refuseEndpoint(endpoint) {
  let parsed;
  try {
    parsed = new URL(endpoint);
  } catch (e) {
    return 'not a URL: ' + endpoint;
  }
  if (parsed.protocol !== 'http:' || !LOOPBACK_HOSTS.has(parsed.hostname)
      || !parsed.pathname.startsWith('/pluginless-devmode/session/')) {
    return 'not a Pluginless DevMode bridge session on this machine: ' + endpoint;
  }
  return null;
}

// The renderer blocks in ipcRenderer.sendSync until returnValue is set, so every path has to set it exactly once.
// There is no timeout: the bridge holds the request while a breakpoint stops the Java code it runs.
ipcMain.on('pluginless-devmode-post', (event, endpoint, body) => {
  let settled = false;
  const settle = (result) => {
    if (!settled) {
      settled = true;
      event.returnValue = result;
    }
  };
  const fail = (error) => settle({error: String(error && error.message || error)});
  // A connection closed in the middle of the response ends neither with 'end' nor always with 'error'
  const closed = () => fail('Pluginless DevMode bridge closed the connection before the response was complete');
  try {
    const refusal = refuseEndpoint(endpoint);
    if (refusal) {
      fail('Pluginless DevMode transport refused ' + refusal);
      return;
    }
    const origin = new URL(event.senderFrame.url).origin;
    const request = http.request(endpoint, {
      method: 'POST',
      agent,
      headers: {'Content-Type': 'text/plain;charset=UTF-8', 'Origin': origin}
    }, (response) => {
      const chunks = [];
      response.on('data', (chunk) => chunks.push(chunk));
      response.on('end', () => {
        const text = Buffer.concat(chunks).toString('utf8');
        settle(response.statusCode === 200
            ? {body: text}
            : {error: 'Pluginless DevMode bridge answered HTTP ' + response.statusCode + ': ' + text});
      });
      response.on('error', fail);
      response.on('close', closed);
    });
    request.on('error', fail);
    request.on('close', closed);
    request.end(body);
  } catch (error) {
    fail(error);
  }
});

// What was typed into the address bar; without a scheme it is an http address ("localhost:8888/x" has none)
function addressToUrl(address) {
  const trimmed = address.trim();
  return /^([a-z][a-z0-9+.-]*:\/\/|about:|data:|file:)/i.test(trimmed) ? trimmed : 'http://' + trimmed;
}

function createWindow() {
  const window = new BaseWindow({
    width: 1400,
    height: 900,
    title: 'Pluginless DevMode debug browser',
    // The toolbar has the same actions; Alt shows the menu bar on Windows and Linux
    autoHideMenuBar: true
  });

  const toolbar = new WebContentsView({
    webPreferences: {
      preload: path.join(__dirname, 'toolbar-preload.js'),
      contextIsolation: true,
      sandbox: true
    }
  });
  const page = new WebContentsView({
    webPreferences: {
      preload: path.join(__dirname, 'preload.js'),
      contextIsolation: true,
      // The preload reads its command line flag through process.argv
      sandbox: false,
      additionalArguments: xhrTransport ? ['--pluginless-devmode-transport=xhr'] : []
    }
  });
  window.contentView.addChildView(toolbar);
  window.contentView.addChildView(page);

  const layout = () => {
    const {width, height} = window.getContentBounds();
    toolbar.setBounds({x: 0, y: 0, width, height: TOOLBAR_HEIGHT});
    page.setBounds({x: 0, y: TOOLBAR_HEIGHT, width, height: Math.max(0, height - TOOLBAR_HEIGHT)});
  };
  layout();
  window.on('resize', layout);

  const contents = page.webContents;
  const sendState = () => {
    if (toolbar.webContents.isDestroyed()) {
      return;
    }
    toolbar.webContents.send('page-state', {
      url: contents.getURL(),
      loading: contents.isLoading(),
      canGoBack: contents.navigationHistory.canGoBack(),
      canGoForward: contents.navigationHistory.canGoForward()
    });
  };
  for (const name of ['did-navigate', 'did-navigate-in-page', 'did-start-loading', 'did-stop-loading']) {
    contents.on(name, sendState);
  }
  toolbar.webContents.on('did-finish-load', sendState);
  contents.on('page-title-updated', (event, title) => window.setTitle(title + ' - Pluginless DevMode debug browser'));

  const actions = {
    back: () => contents.navigationHistory.goBack(),
    forward: () => contents.navigationHistory.goForward(),
    reload: () => contents.reload(),
    forceReload: () => contents.reloadIgnoringCache(),
    stop: () => contents.stop(),
    devTools: () => contents.toggleDevTools(),
    focusAddress: () => {
      toolbar.webContents.focus();
      toolbar.webContents.send('focus-address');
    }
  };
  ipcMain.on('toolbar', (event, action, value) => {
    if (event.sender !== toolbar.webContents) {
      return;
    }
    if (action === 'navigate') {
      contents.loadURL(addressToUrl(value));
      contents.focus();
    } else if (Object.hasOwn(actions, action)) {
      actions[action]();
    }
  });

  // A login (e.g. CAS) returns to the application server itself; go back to the page behind the bridge. Only for
  // navigations the page started: the address bar, back/forward and loadURL have no initiator and are left alone.
  const target = new URL(url);
  let pageInitiated = false;
  contents.on('did-start-navigation', (details) => {
    if (details.isMainFrame && !details.isSameDocument) {
      pageInitiated = Boolean(details.initiator);
    }
  });
  contents.on('did-navigate', (event, navigated) => {
    const current = new URL(navigated);
    if (pageInitiated && current.origin !== target.origin && current.pathname === target.pathname) {
      contents.loadURL(url);
    }
  });

  toolbar.webContents.loadFile(path.join(__dirname, 'toolbar.html'));
  contents.loadURL(url);
  return actions;
}

// The view shortcuts act on the page even when the address bar has the focus
function createMenu(actions) {
  const isMac = process.platform === 'darwin';
  const hidden = {visible: false, acceleratorWorksWhenHidden: true};
  return Menu.buildFromTemplate([
    ...(isMac ? [{role: 'appMenu'}] : []),
    // Cut, copy and paste in the address bar and page inputs on macOS need these roles
    {role: 'editMenu'},
    {
      label: 'View',
      submenu: [
        {label: 'Back', accelerator: isMac ? 'Cmd+[' : 'Alt+Left', click: actions.back},
        {label: 'Forward', accelerator: isMac ? 'Cmd+]' : 'Alt+Right', click: actions.forward},
        {type: 'separator'},
        {label: 'Reload', accelerator: 'CmdOrCtrl+R', click: actions.reload},
        {label: 'Reload', accelerator: 'F5', click: actions.reload, ...hidden},
        {label: 'Force Reload', accelerator: 'Shift+CmdOrCtrl+R', click: actions.forceReload},
        {type: 'separator'},
        {label: 'Open Location', accelerator: 'CmdOrCtrl+L', click: actions.focusAddress},
        {label: 'Toggle Developer Tools', accelerator: isMac ? 'Alt+Cmd+I' : 'Ctrl+Shift+I', click: actions.devTools},
        {label: 'Toggle Developer Tools', accelerator: 'F12', click: actions.devTools, ...hidden},
        {type: 'separator'},
        {role: 'resetZoom'},
        {role: 'zoomIn'},
        {role: 'zoomOut'},
        {type: 'separator'},
        {role: 'togglefullscreen'}
      ]
    },
    {role: 'windowMenu'}
  ]);
}

app.whenReady().then(() => {
  Menu.setApplicationMenu(createMenu(createWindow()));
});

app.on('window-all-closed', () => app.quit());
