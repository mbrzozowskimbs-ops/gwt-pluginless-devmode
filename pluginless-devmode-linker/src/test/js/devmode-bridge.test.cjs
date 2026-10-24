'use strict';

const test = require('node:test');
const assert = require('node:assert/strict');
const path = require('node:path');

const createBridgePlugin = require(
    path.join(__dirname, '../../main/resources/cloud/rubibubi/gwt/pluginless/linker/devmode-bridge.js'));

/** Minimal stand-in for the module window with the helpers devmode.js defines. */
function fakeWindow() {
  const win = {
    evaluated: [],
    glass: [],
    eval(code) {
      win.evaluated.push(code);
    },
    __gwt_jsInvoke(thisObj, methodName, ...args) {
      try {
        return [0, win[methodName].apply(thisObj, args)];
      } catch (e) {
        return [1, e];
      }
    },
    __gwt_makeResult(isException, value) {
      return [isException, value];
    },
    __gwt_makeTearOff(proxy, dispId) {
      return function (...args) {
        const result = win.__static(dispId, this, ...args);
        if (result[0]) {
          throw result[1];
        }
        return result[1];
      };
    },
    __gwt_displayGlassMessage(summary, details) {
      win.glass.push([summary, details]);
    }
  };
  return win;
}

/**
 * Plugin wired to a scripted bridge: respond(message, batch) returns the commands for the last message of a batch.
 * The phase statistics leading every batch are kept apart in stats, so requests and batch hold the protocol messages.
 */
function pluginWithBridge(win, respond) {
  const requests = [];
  const stats = [];
  const beacons = [];
  const plugin = createBridgePlugin(win, {
    transport(body) {
      const batch = JSON.parse(body);
      assert.equal(batch[0][0], 'stats');
      stats.push(batch[0]);
      const messages = batch.slice(1);
      requests.push(messages);
      return JSON.stringify(respond(messages[messages.length - 1], messages));
    },
    sendBeacon(url, body) {
      beacons.push({url, body: JSON.parse(body)});
    }
  });
  assert.equal(plugin.init(win), true);
  return {plugin, requests, stats, beacons};
}

function connect(plugin) {
  return plugin.connect('http://localhost:8888/sample/Sample.html', 'session-key', '127.0.0.1:9876', 'sample', '2.1');
}

const DONE = [['return', false, ['u']]];

test('connect sends connect, evaluates JSNI and returns after the module has loaded', () => {
  const win = fakeWindow();
  const {plugin, requests} = pluginWithBridge(win, () => [['jsni', 'var a = 1;'], ['jsni', 'var b = 2;'], ...DONE]);

  assert.equal(connect(plugin), true);

  assert.equal(requests.length, 1);
  const message = requests[0][0];
  assert.deepEqual(message.slice(0, 6),
      ['connect', '2.1', 'http://localhost:8888/sample/Sample.html', '', 'session-key', 'sample']);
  assert.deepEqual(win.evaluated, ['var a = 1;', 'var b = 2;']);
  assert.deepEqual(win.glass, []);
});

test('every request reports cumulative phase times in microseconds', () => {
  const win = fakeWindow();
  win.slow = () => {
    const end = Date.now() + 5;
    while (Date.now() < end) {
      // Busy page JavaScript, counted as phase js
    }
  };
  const {plugin, stats} = pluginWithBridge(win, (message) =>
      message[0] === 'connect' ? [['invoke', 'slow', null, []]] : DONE);

  assert.equal(connect(plugin), true);

  assert.equal(stats.length, 2);
  for (const [tag, micros] of stats) {
    assert.equal(tag, 'stats');
    assert.equal(micros.length, 5);
    assert.ok(micros.every(Number.isInteger));
  }
  // page, bridge, xhr, js, jsni: the second request follows the busy JavaScript
  assert.ok(stats[1][1][3] >= 4000, 'js phase ' + stats[1][1][3]);
});

test('100 000 sequential Java -> JS calls run at constant stack depth', () => {
  const win = fakeWindow();
  const total = 100000;
  let calls = 0;
  win.noop = () => 1;
  const {plugin} = pluginWithBridge(win, (message) => {
    if (message[0] !== 'connect') {
      assert.deepEqual(message, ['return', false, 1]);
      calls++;
    }
    return calls < total ? [['invoke', 'noop', null, []]] : DONE;
  });

  assert.equal(connect(plugin), true);

  assert.equal(calls, total);
  assert.deepEqual(win.glass, []);
});

test('JS called from Java can call Java again (Java -> JS -> Java)', () => {
  const win = fakeWindow();
  win.callJava = (dispatcher) => dispatcher(42, null, 5)[1] * 2;
  const {plugin, requests} = pluginWithBridge(win, (message) => {
    switch (message[0]) {
      case 'connect':
        return [['invoke', 'callJava', null, [['j', 7]]]];
      case 'invoke':
        // Static call: no Java "this", so the dispatcher object itself is sent
        assert.deepEqual(message, ['invoke', 42, ['j', 7], [5]]);
        return [['return', false, 10]];
      case 'return':
        assert.deepEqual(message, ['return', false, 20]);
        return DONE;
      default:
        throw new Error('unexpected ' + message[0]);
    }
  });

  assert.equal(connect(plugin), true);
  assert.equal(requests.length, 3);
});

test('Java objects convert to strings through Java toString and ignore symbol keys', () => {
  const win = fakeWindow();
  let result;
  win.defineStatic = (dispatcher) => {
    win.__static = dispatcher;
  };
  win.stringify = (object) => {
    assert.equal(object[Symbol.toPrimitive], undefined);
    assert.equal('__gwt_ObjectId' in object, true);
    assert.equal(object.__gwt_ObjectId, 2);
    result = '' + object + '|' + String(object);
  };
  let step = 0;
  const {plugin} = pluginWithBridge(win, (message) => {
    step++;
    if (step === 1) {
      return [['invoke', 'defineStatic', null, [['j', 1]]]];
    }
    if (step === 2) {
      return [['invoke', 'stringify', null, [['j', 2]]]];
    }
    if (message[0] === 'invoke') {
      assert.deepEqual(message, ['invoke', 0, ['j', 2], []]);
      return [['return', false, 'Customer@2']];
    }
    return DONE;
  });

  assert.equal(connect(plugin), true);
  assert.equal(result, 'Customer@2|Customer@2');
});

test('Java fields are read and written through getJava / setJava', () => {
  const win = fakeWindow();
  win.useFields = (object) => {
    object[5] = 'x';
    return object[3];
  };
  const seen = [];
  const {plugin} = pluginWithBridge(win, (message) => {
    seen.push(message);
    switch (message[0]) {
      case 'connect':
        return [['invoke', 'useFields', null, [['j', 2]]]];
      case 'setJava':
        return [['return', false, ['u']]];
      case 'getJava':
        return [['return', false, 99]];
      default:
        return DONE;
    }
  });

  assert.equal(connect(plugin), true);
  assert.deepEqual(seen.slice(1), [['setJava', 2, 5, 'x'], ['getJava', 2, 3], ['return', false, 99]]);
});

test('JavaScript exceptions are returned to Java as exceptions', () => {
  const win = fakeWindow();
  win.boom = () => {
    throw new Error('boom');
  };
  let reply;
  const {plugin} = pluginWithBridge(win, (message) => {
    if (message[0] === 'connect') {
      return [['invoke', 'boom', null, []]];
    }
    reply = message;
    return DONE;
  });

  assert.equal(connect(plugin), true);
  assert.equal(reply[0], 'return');
  assert.equal(reply[1], true);
  assert.equal(reply[2][0], 'o');
});

test('JavaScript values that cannot be passed to Java are returned to Java as exceptions', () => {
  const win = fakeWindow();
  win.bigint = () => 1n;
  win.throwSymbol = () => {
    throw Symbol('thrown');
  };
  // Passed to Java first, so it gets JavaScript object id 1
  win.holder = () => ({symbol: Symbol('property')});
  const calls = [['invoke', 'holder', null, []], ['invoke', 'bigint', null, []], ['invoke', 'throwSymbol', null, []],
    ['get', 1, 'symbol']];
  const replies = [];
  const {plugin} = pluginWithBridge(win, (message) => {
    if (message[0] === 'return') {
      replies.push(message);
    }
    return calls.length ? [calls.shift()] : DONE;
  });

  assert.equal(connect(plugin), true);

  assert.deepEqual(replies[0], ['return', false, ['o', 1]]);
  assert.equal(replies.length, 4);
  for (const reply of replies.slice(1)) {
    assert.equal(reply[1], true);
    assert.equal(reply[2][0], 'o');
  }
  assert.deepEqual(win.glass, []);
});

test('values without a JSON form are tagged in both directions', () => {
  const win = fakeWindow();
  win.echo = (...args) => args;
  win.special = () => NaN;
  win.nothing = () => undefined;
  const replies = [];
  let step = 0;
  const {plugin} = pluginWithBridge(win, (message) => {
    step++;
    if (step > 1) {
      replies.push(message);
    }
    switch (step) {
      case 1:
        return [['invoke', 'special', null, []]];
      case 2:
        return [['invoke', 'nothing', null, []]];
      case 3:
        return [['invoke', 'echo', null, [['d', '-Infinity'], ['u'], null, true, 'text']]];
      default:
        return DONE;
    }
  });

  assert.equal(connect(plugin), true);
  assert.deepEqual(replies[0], ['return', false, ['d', 'NaN']]);
  assert.deepEqual(replies[1], ['return', false, ['u']]);
  assert.equal(replies[2][2][0], 'o');
});

test('JavaScript objects freed by the code server are forgotten', () => {
  const win = fakeWindow();
  win.makeObject = () => ({x: 1});
  const replies = [];
  let objectId;
  let step = 0;
  const {plugin} = pluginWithBridge(win, (message) => {
    step++;
    if (step > 1) {
      replies.push(message);
    }
    switch (step) {
      case 1:
        return [['invoke', 'makeObject', null, []]];
      case 2:
        objectId = message[2][1];
        return [['get', objectId, 'x']];
      case 3:
        return [['free', [objectId]], ['get', objectId, 'x']];
      default:
        return DONE;
    }
  });

  assert.equal(connect(plugin), true);
  assert.deepEqual(replies[1], ['return', false, 1]);
  assert.equal(replies[2][1], true, 'reading a freed object must fail');
});

test('a bridge error shows a message and stops the session', () => {
  const win = fakeWindow();
  const {plugin} = pluginWithBridge(win, () => [['error', 'Code server connection failed: refused']]);

  assert.equal(connect(plugin), true);
  assert.equal(win.glass.length > 0, true);
  assert.match(win.glass[0][1], /refused/);
});

test('an unreachable bridge shows a message instead of the missing-plugin page', () => {
  const win = fakeWindow();
  const plugin = createBridgePlugin(win, {
    transport() {
      throw new Error('net::ERR_CONNECTION_REFUSED');
    }
  });
  plugin.init(win);

  assert.equal(connect(plugin), true);
  assert.match(win.glass[0][1], /ERR_CONNECTION_REFUSED/);
});

test('disconnect sends quit with a beacon', () => {
  const win = fakeWindow();
  const {plugin, beacons} = pluginWithBridge(win, () => DONE);
  connect(plugin);

  plugin.disconnect();

  assert.equal(beacons.length, 1);
  assert.match(beacons[0].url, /^http:\/\/127\.0\.0\.1:9876\/pluginless-devmode\/session\/.+/);
  assert.deepEqual(beacons[0].body, [['quit']]);
});

test('collected Java wrappers are reported to the code server with the next request',
    {skip: typeof gc !== 'function' && 'needs node --expose-gc'}, async () => {
  const win = fakeWindow();
  // Keeps the dispatcher (id 1) and drops the other Java object (id 77)
  win.setup = (dispatcher) => {
    win.__static = dispatcher;
  };
  let lastBatch;
  const {plugin} = pluginWithBridge(win, (message, batch) => {
    lastBatch = batch;
    if (message[0] === 'connect') {
      return [['invoke', 'setup', null, [['j', 1], ['j', 77]]]];
    }
    if (message[0] === 'invoke') {
      return [['return', false, 5]];
    }
    return DONE;
  });
  connect(plugin);

  for (let i = 0; i < 20; i++) {
    gc();
    await new Promise((resolve) => setImmediate(resolve));
  }
  assert.deepEqual(win.__static(3, null), [0, 5]);

  assert.deepEqual(lastBatch, [['free', [77]], ['invoke', 3, ['j', 1], []]]);
});
