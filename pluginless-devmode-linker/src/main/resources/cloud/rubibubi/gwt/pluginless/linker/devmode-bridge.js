/*
 * Pluginless DevMode bridge client: a pure JavaScript replacement for the GWT Developer Mode browser plugin.
 *
 * devmode.js looks the plugin up as the global __gwt_HostedModePlugin and calls init(window) and
 * connect(url, sessionKey, codeServer, moduleName, hostedHtmlVersion) on it. This object talks to the Pluginless DevMode bridge
 * (codeServer = host:port of the bridge) with synchronous XHR; the bridge speaks BrowserChannel to the DevMode JVM.
 *
 * Wire protocol: docs/PROTOCOL.md. Behaviour of Java object wrappers follows the NPAPI plugin (gwt-plugins, npapi/).
 */
(function (factory) {
  /*
   * The Pluginless DevMode debug browser (pluginless-devmode-electron) exposes a synchronous IPC transport as devModeElectron on the top
   * window; GWT runs the module in an iframe of the same origin, so look it up through the parents.
   */
  function electronTransport(win) {
    // The top window is its own parent
    for (var w = win; ; w = w.parent) {
      try {
        if (w.devModeElectron) {
          var electron = w.devModeElectron;
          return function (body, endpoint) {
            return electron.transport(endpoint, body);
          };
        }
      } catch (e) {
        return undefined;
      }
      if (w === w.parent) {
        return undefined;
      }
    }
  }

  if (typeof module === 'object' && module.exports) {
    module.exports = factory;
  } else {
    window.__gwt_HostedModePlugin = factory(window, {transport: electronTransport(window)});
  }
})(function createBridgePlugin(initialWindow, options) {
  'use strict';

  var TOSTRING_DISPATCH_ID = 0;
  var SESSION_PATH = '/pluginless-devmode/session/';

  var win = initialWindow;
  var transport = options.transport || syncXhrTransport;
  var sendBeacon = options.sendBeacon || defaultSendBeacon;
  var endpoint = null;
  var connected = false;

  // Java objects held by the code server: id -> WeakRef(proxy) and proxy -> id
  var javaRefs = new Map();
  var javaIds = new WeakMap();
  var javaIdsToFree = new Set();
  var javaFinalizer = typeof FinalizationRegistry === 'function'
      ? new FinalizationRegistry(onJavaProxyCollected) : null;

  // JavaScript objects passed to the code server: id -> object and object -> id
  var jsObjects = new Map();
  var jsIds = new WeakMap();
  var nextJsId = 1;

  // Time spent per phase (exclusive, in ms), reported to the bridge with every request for its round trip statistics
  var PHASES = ['page', 'bridge', 'xhr', 'js', 'jsni'];
  var now = typeof performance !== 'undefined' && performance.now ? performance.now.bind(performance) : Date.now;
  var phaseTotals = {};
  PHASES.forEach(function (phase) {
    phaseTotals[phase] = 0;
  });
  var phaseStack = ['page'];
  var phaseStart = now();

  function syncXhrTransport(body) {
    var xhr = new XMLHttpRequest();
    xhr.open('POST', endpoint, false);
    // text/plain keeps the cross-origin request "simple", so the browser sends no CORS preflight
    xhr.setRequestHeader('Content-Type', 'text/plain;charset=UTF-8');
    xhr.send(body);
    if (xhr.status !== 200) {
      throw new Error('Pluginless DevMode bridge answered HTTP ' + xhr.status + ': ' + xhr.responseText);
    }
    return xhr.responseText;
  }

  function defaultSendBeacon(url, body) {
    if (typeof navigator !== 'undefined' && navigator.sendBeacon) {
      navigator.sendBeacon(url, body);
    }
  }

  /*
   * The innermost phase owns the time: 'js' around page JavaScript, 'xhr' around a request, 'jsni' around loading
   * JSNI, 'bridge' for this client's own code within a transaction and 'page' for everything outside transactions.
   */
  function enterPhase(phase) {
    var time = now();
    phaseTotals[phaseStack[phaseStack.length - 1]] += time - phaseStart;
    phaseStack.push(phase);
    phaseStart = time;
  }

  function exitPhase() {
    var time = now();
    phaseTotals[phaseStack.pop()] += time - phaseStart;
    phaseStart = time;
  }

  function phaseStats() {
    return ['stats', PHASES.map(function (phase) {
      return Math.round(phaseTotals[phase] * 1000);
    })];
  }

  function newSessionId() {
    if (typeof crypto !== 'undefined' && crypto.randomUUID) {
      return crypto.randomUUID();
    }
    return Date.now().toString(36) + '-' + Math.random().toString(36).slice(2);
  }

  /* ---------------------------------------------------------------------------------------------------------------
   * Transport
   * ------------------------------------------------------------------------------------------------------------- */

  function post(message) {
    if (!connected) {
      throw new Error('Pluginless DevMode: not connected to the code server');
    }
    var batch = [phaseStats()];
    if (javaIdsToFree.size > 0) {
      batch.push(['free', Array.from(javaIdsToFree)]);
      javaIdsToFree.clear();
    }
    batch.push(message);
    var body = JSON.stringify(batch);
    var response;
    enterPhase('xhr');
    try {
      response = transport(body, endpoint);
    } catch (e) {
      disconnected('Pluginless DevMode bridge unreachable', String(e && e.message || e));
      throw e;
    } finally {
      exitPhase();
    }
    return JSON.parse(response);
  }

  /*
   * Sends a message the code server answers with RETURN and returns {isException, value}.
   *
   * While waiting, the code server may ask the browser to invoke JavaScript or access properties; those requests are
   * served in this loop, so any number of sequential Java -> JS calls runs at constant stack depth. The stack grows
   * only with real nesting: JavaScript that calls back into Java re-enters transact().
   */
  function transact(message) {
    enterPhase('bridge');
    try {
      return transactLoop(message);
    } finally {
      exitPhase();
    }
  }

  function transactLoop(message) {
    var outgoing = message;
    for (;;) {
      var commands = post(outgoing);
      var request = runAsyncCommands(commands);
      switch (request[0]) {
        case 'return':
          return {isException: request[1], value: decode(request[2])};
        case 'invoke':
          outgoing = invokeJs(request[1], request[2], request[3]);
          break;
        case 'get':
          outgoing = getJsProperty(request[1], request[2]);
          break;
        case 'set':
          outgoing = setJsProperty(request[1], request[2], request[3]);
          break;
        case 'quit':
          throw failure('GWT Code Server Disconnected', 'Development Mode closed the session.',
              'the code server closed the session');
        case 'error':
          throw failure('Pluginless DevMode bridge error', request[1], request[1]);
        default:
          throw failure('Pluginless DevMode bridge error', 'Unknown command ' + request[0], 'unknown command ' + request[0]);
      }
    }
  }

  // Shows the session as lost and returns the error to throw
  function failure(summary, details, message) {
    disconnected(summary, details);
    return new Error('Pluginless DevMode: ' + message);
  }

  function runAsyncCommands(commands) {
    if (!commands.length) {
      throw new Error('Pluginless DevMode: empty response from the bridge');
    }
    for (var i = 0; i < commands.length - 1; i++) {
      var command = commands[i];
      if (command[0] === 'jsni') {
        loadJsni(command[1]);
      } else if (command[0] === 'free') {
        freeJsObjects(command[1]);
      } else {
        throw new Error('Pluginless DevMode: command ' + command[0] + ' must be the last one in a response');
      }
    }
    return commands[commands.length - 1];
  }

  function loadJsni(code) {
    enterPhase('jsni');
    try {
      // Indirect eval: JSNI declarations must land in the module window's global scope
      win.eval(code);
    } catch (e) {
      log('error', 'Pluginless DevMode: failed to load JSNI code', e);
    } finally {
      exitPhase();
    }
  }

  /* ---------------------------------------------------------------------------------------------------------------
   * Code server -> browser requests
   * ------------------------------------------------------------------------------------------------------------- */

  function invokeJs(methodName, thisJson, argsJson) {
    var args = [decode(thisJson), methodName].concat(argsJson.map(decode));
    return callJs(function () {
      // __gwt_jsInvoke (devmode.js) returns [isException, value]
      return win.__gwt_jsInvoke.apply(null, args);
    });
  }

  function getJsProperty(objectId, key) {
    return callJs(function () {
      return [false, jsObject(objectId)[key]];
    });
  }

  function setJsProperty(objectId, key, valueJson) {
    return callJs(function () {
      jsObject(objectId)[key] = decode(valueJson);
      return [false, undefined];
    });
  }

  /*
   * Runs page JavaScript for the code server and answers with a return message. The call returns
   * [isException, value]; a thrown exception becomes an exceptional return.
   */
  function callJs(call) {
    var result;
    enterPhase('js');
    try {
      result = call();
    } catch (e) {
      return returnMessage(true, e);
    } finally {
      exitPhase();
    }
    return returnMessage(!!result[0], result[1]);
  }

  /*
   * The code server waits for this answer, so a value without a Java form (BigInt, Symbol) becomes an exception
   * instead of leaving the request unanswered.
   */
  function returnMessage(isException, value) {
    try {
      return ['return', isException, encode(value)];
    } catch (e) {
      return ['return', true, encode(e)];
    }
  }

  /* ---------------------------------------------------------------------------------------------------------------
   * Values
   * ------------------------------------------------------------------------------------------------------------- */

  function encode(value) {
    switch (typeof value) {
      case 'undefined':
        return ['u'];
      case 'boolean':
      case 'string':
        return value;
      case 'number':
        return isFinite(value) ? value : ['d', String(value)];
      case 'object':
        if (value === null) {
          return null;
        }
        if (Object.prototype.toString.call(value) === '[object String]') {
          return String(value);
        }
        return encodeReference(value);
      case 'function':
        return encodeReference(value);
      default:
        throw new TypeError('Pluginless DevMode: a ' + typeof value + ' value cannot be passed to Java');
    }
  }

  function encodeReference(object) {
    var javaId = javaIds.get(object);
    if (javaId !== undefined) {
      return ['j', javaId];
    }
    return ['o', jsObjectId(object)];
  }

  function decode(json) {
    if (json === null || typeof json !== 'object') {
      return json;
    }
    switch (json[0]) {
      case 'u':
        return undefined;
      case 'd':
        return Number(json[1]);
      case 'j':
        return javaObject(json[1]);
      case 'o':
        return jsObject(json[1]);
      default:
        throw new Error('Pluginless DevMode: unknown value tag ' + json[0]);
    }
  }

  function jsObjectId(object) {
    var id = jsIds.get(object);
    if (id === undefined) {
      id = nextJsId++;
      jsIds.set(object, id);
      jsObjects.set(id, object);
    }
    return id;
  }

  function jsObject(id) {
    // Only objects and functions are stored, so undefined means unknown
    var object = jsObjects.get(id);
    if (object === undefined) {
      throw new Error('Pluginless DevMode: unknown JavaScript object id ' + id);
    }
    return object;
  }

  function freeJsObjects(ids) {
    for (var i = 0; i < ids.length; i++) {
      var object = jsObjects.get(ids[i]);
      jsObjects.delete(ids[i]);
      if (object !== undefined && jsIds.get(object) === ids[i]) {
        jsIds.delete(object);
      }
    }
  }

  /* ---------------------------------------------------------------------------------------------------------------
   * Java object wrappers
   * ------------------------------------------------------------------------------------------------------------- */

  function javaObject(id) {
    var ref = javaRefs.get(id);
    var existing = ref && ref.deref();
    if (existing) {
      return existing;
    }
    // The code server handed out this id again before the browser reported it as unused
    javaIdsToFree.delete(id);
    // A bound function is callable and, unlike a plain function, has no non-configurable 'prototype' property
    // that would break the proxy invariants of the 'has' trap
    var proxy = new Proxy(function () {}.bind(null), javaObjectHandler(id));
    javaRefs.set(id, new WeakRef(proxy));
    javaIds.set(proxy, id);
    if (javaFinalizer) {
      javaFinalizer.register(proxy, id);
    }
    return proxy;
  }

  function onJavaProxyCollected(id) {
    var ref = javaRefs.get(id);
    if (ref && ref.deref()) {
      // A newer wrapper for the same id is alive
      return;
    }
    javaRefs.delete(id);
    if (connected) {
      javaIdsToFree.add(id);
    }
  }

  function javaDispatchId(key) {
    return typeof key === 'string' && /^\d+$/.test(key) ? Number(key) : -1;
  }

  function javaObjectHandler(id) {
    return {
      get: function (target, key) {
        if (key === '__gwt_ObjectId') {
          return id;
        }
        if (key === 'toString') {
          return win.__gwt_makeTearOff(null, TOSTRING_DISPATCH_ID, 0);
        }
        var dispId = javaDispatchId(key);
        if (dispId < 0) {
          // Symbols (Symbol.toPrimitive, Symbol.iterator, ...) and other names have no Java counterpart
          return undefined;
        }
        return unwrap(transact(['getJava', id, dispId]));
      },
      set: function (target, key, value) {
        var dispId = javaDispatchId(key);
        if (dispId < 0) {
          return false;
        }
        unwrap(transact(['setJava', id, dispId, encode(value)]));
        return true;
      },
      has: function (target, key) {
        return key === '__gwt_ObjectId' || key === 'toString' || javaDispatchId(key) >= 0;
      },
      // JSNI calls Java through __static(dispId, thisObj, args...) and tear-offs; the result is wrapped by
      // __gwt_makeResult and unwrapped by the generated caller. A thisObj that is not a Java object means "this".
      apply: function (target, thisArg, args) {
        var dispId = args[0];
        var thisId = javaIds.get(args[1]);
        var javaArgs = args.slice(2).map(encode);
        var result = transact(['invoke', dispId, ['j', thisId === undefined ? id : thisId], javaArgs]);
        return win.__gwt_makeResult(result.isException ? 1 : 0, result.value);
      }
    };
  }

  function unwrap(result) {
    if (result.isException) {
      throw result.value;
    }
    return result.value;
  }

  /* ---------------------------------------------------------------------------------------------------------------
   * Session lifecycle
   * ------------------------------------------------------------------------------------------------------------- */

  function disconnected(summary, details) {
    if (!connected) {
      return;
    }
    connected = false;
    log('error', 'Pluginless DevMode: ' + summary + ' - ' + details);
    if (win.__gwt_displayGlassMessage) {
      win.__gwt_displayGlassMessage(escapeHtml(summary), escapeHtml(details));
    }
  }

  function escapeHtml(text) {
    return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
  }

  function log(level, message, detail) {
    if (typeof console !== 'undefined' && console[level]) {
      if (detail === undefined) {
        console[level](message);
      } else {
        console[level](message, detail);
      }
    }
  }

  return {
    init: function (window) {
      win = window;
      return true;
    },

    connect: function (url, sessionKey, codeServer, moduleName, hostedHtmlVersion) {
      endpoint = 'http://' + codeServer + SESSION_PATH + newSessionId();
      var result;
      try {
        // Like the original plugin, connect() returns only after onModuleLoad() has finished on the JVM
        connected = true;
        result = transact(['connect', hostedHtmlVersion, url, '', sessionKey, moduleName,
          typeof navigator !== 'undefined' ? navigator.userAgent : '']);
      } catch (e) {
        disconnected('Cannot connect to the Pluginless DevMode bridge at ' + codeServer, String(e && e.message || e));
        // The failure is already on screen; false would add devmode.js's "missing plugin" message on top
        return true;
      }
      if (result.isException) {
        disconnected('Module ' + moduleName + ' failed to load', String(result.value)
            + ' (details in the DevMode log)');
      }
      return true;
    },

    disconnect: function () {
      if (!connected) {
        return;
      }
      connected = false;
      // Synchronous XHR is not allowed while the page unloads; a beacon is
      sendBeacon(endpoint, JSON.stringify([['quit']]));
    }
  };
});
