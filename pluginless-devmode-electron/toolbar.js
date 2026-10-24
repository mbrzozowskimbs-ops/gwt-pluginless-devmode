'use strict';
/*
 * Toolbar of the Pluginless DevMode debug browser; window.devModeToolbar comes from toolbar-preload.js.
 */
const toolbar = window.devModeToolbar;
const back = document.getElementById('back');
const forward = document.getElementById('forward');
const reload = document.getElementById('reload');
const address = document.getElementById('address');
let loading = false;

back.addEventListener('click', () => toolbar.send('back'));
forward.addEventListener('click', () => toolbar.send('forward'));
reload.addEventListener('click', () => toolbar.send(loading ? 'stop' : 'reload'));
document.getElementById('devtools').addEventListener('click', () => toolbar.send('devTools'));

document.getElementById('address-form').addEventListener('submit', (event) => {
  event.preventDefault();
  if (address.value.trim()) {
    toolbar.send('navigate', address.value);
    address.blur();
  }
});
address.addEventListener('focus', () => address.select());
address.addEventListener('keydown', (event) => {
  if (event.key === 'Escape') {
    address.value = address.dataset.url || '';
    address.blur();
  }
});

toolbar.onPageState((state) => {
  loading = state.loading;
  back.disabled = !state.canGoBack;
  forward.disabled = !state.canGoForward;
  reload.textContent = loading ? '✕' : '↻';
  reload.title = loading ? 'Stop' : 'Reload';
  reload.setAttribute('aria-label', reload.title);
  address.dataset.url = state.url;
  // Keeps what is being typed
  if (document.activeElement !== address) {
    address.value = state.url;
  }
});

toolbar.onFocusAddress(() => {
  address.focus();
  address.select();
});
