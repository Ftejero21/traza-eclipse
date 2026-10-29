/* Traza: puente entre la webview Angular y el plugin de Eclipse (SWT Browser). Se carga antes que Angular. */
(function () {
  'use strict';
  var uiState;
  var queue = [];
  var timer = null;
  var api = null;

  function flush() {
    if (typeof window.trazaHostPost === 'function') {
      while (queue.length) {
        var message = queue.shift();
        try {
          window.trazaHostPost(message);
        } catch (e) {
          console.warn('[Traza] No se pudo enviar un mensaje al host.');
        }
      }
      if (timer !== null) {
        clearInterval(timer);
        timer = null;
      }
    } else if (timer === null) {
      timer = setInterval(flush, 50);
    }
  }

  window.acquireVsCodeApi = function () {
    if (api === null) {
      api = {
        postMessage: function (message) {
          queue.push(JSON.stringify(message));
          flush();
        },
        getState: function () {
          return uiState;
        },
        setState: function (state) {
          uiState = state;
          return state;
        }
      };
    }
    return api;
  };

  /* El host entrega los mensajes como JSON; la webview los recibe como eventos "message". */
  window.__trazaReceive = function (json) {
    var data;
    try {
      data = JSON.parse(json);
    } catch (e) {
      console.warn('[Traza] Mensaje del host no valido.');
      return;
    }
    window.postMessage(data, '*');
  };

  var dark = true;

  function applyBodyClass() {
    var body = document.body;
    if (!body) {
      return;
    }
    body.classList.remove('vscode-dark', 'vscode-light');
    body.classList.add(dark ? 'vscode-dark' : 'vscode-light');
  }

  /* Tema del IDE: clases vscode-dark/vscode-light en el body y, con theme=ide, las variables --vscode-*. */
  window.__trazaSetTheme = function (isDark, ideVarsCss) {
    dark = !!isDark;
    var style = document.getElementById('traza-ide-vars');
    if (typeof ideVarsCss === 'string') {
      if (!style) {
        style = document.createElement('style');
        style.id = 'traza-ide-vars';
        (document.head || document.documentElement).appendChild(style);
      }
      style.textContent = ideVarsCss;
    }
    applyBodyClass();
  };

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', applyBodyClass);
  } else {
    applyBodyClass();
  }
})();
