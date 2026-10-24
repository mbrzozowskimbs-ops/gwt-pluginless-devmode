'use strict';
/*
 * Exposes window.devModeElectron.transport(endpoint, body) to the page; devmode-bridge.js uses it instead of a
 * synchronous XHR when it finds it in its own window or in a parent window (GWT runs modules in an iframe).
 */
const {contextBridge, ipcRenderer} = require('electron');

if (!process.argv.includes('--pluginless-devmode-transport=xhr')) {
  contextBridge.exposeInMainWorld('devModeElectron', {
    transport(endpoint, body) {
      const result = ipcRenderer.sendSync('pluginless-devmode-post', endpoint, body);
      if (result.error) {
        throw new Error(result.error);
      }
      return result.body;
    }
  });
}
