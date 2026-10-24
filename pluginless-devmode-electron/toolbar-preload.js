'use strict';
/*
 * Exposes window.devModeToolbar to toolbar.html: actions on the page view and updates of its state.
 */
const {contextBridge, ipcRenderer} = require('electron');

contextBridge.exposeInMainWorld('devModeToolbar', {
  send(action, value) {
    ipcRenderer.send('toolbar', action, value);
  },
  onPageState(listener) {
    ipcRenderer.on('page-state', (event, state) => listener(state));
  },
  onFocusAddress(listener) {
    ipcRenderer.on('focus-address', () => listener());
  }
});
