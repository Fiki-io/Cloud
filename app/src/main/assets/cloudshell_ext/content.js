(function() {
  'use strict';

  var NATIVE_APP = 'cloudshell';
  var streamTail = '';
  var lastPinggy = '';
  var port = null;
  var utf8Decoder = typeof TextDecoder !== 'undefined' ? new TextDecoder('utf-8') : null;

  function enforceDesktopViewport() {
    if (window !== window.top) return;
    try {
      var head = document.head || document.documentElement;
      if (!head) return;
      var meta = document.querySelector('meta[name="viewport"]');
      var desktopContent = 'width=1280, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
      if (!meta) {
        meta = document.createElement('meta');
        meta.name = 'viewport';
        meta.content = desktopContent;
        head.appendChild(meta);
      } else if (meta.content !== desktopContent) {
        meta.content = desktopContent;
      }
    } catch (e) {}
  }

  function focusTerminalElement() {
    try {
      var ta = document.querySelector('.xterm-helper-textarea');
      if (ta) {
        window.focus();
        ta.focus();
      }
    } catch (e) {}
  }

  function extractPinggy(str) {
    if (!str || str.toLowerCase().indexOf('pinggy.') === -1) return null;
    var clean = str.replace(/\x1b\[[0-9;?]*[a-zA-Z]/g, '').replace(/\x1b\][^\x07]*\x07/g, '');
    var tcpMatch = clean.match(/tcp:\/\/[a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-]+)*\.pinggy\.(?:link|io):\d+/gi);
    if (tcpMatch && tcpMatch.length > 0) {
      return tcpMatch[tcpMatch.length - 1];
    }
    var urlMatch = clean.match(/(?:tcp|https?):\/\/[a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-]+)*\.pinggy\.(?:link|io)(?::\d+)?/gi);
    if (urlMatch && urlMatch.length > 0) {
      for (var i = urlMatch.length - 1; i >= 0; i--) {
        if (urlMatch[i].indexOf('@') === -1) return urlMatch[i];
      }
    }
    var hostPortMatch = clean.match(/[a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-]+)+\.pinggy\.(?:link|io):\d+/gi);
    if (hostPortMatch && hostPortMatch.length > 0) {
      return 'tcp://' + hostPortMatch[hostPortMatch.length - 1];
    }
    return null;
  }

  function notifyPinggy(url) {
    if (!url || url === lastPinggy) return;
    lastPinggy = url;
    try {
      if (port) {
        port.postMessage({ type: 'pinggy', url: url });
      }
    } catch (e) {}
    try {
      browser.runtime.sendNativeMessage(NATIVE_APP, { type: 'pinggy', url: url });
    } catch (e) {}
  }

  function inspectTextChunk(chunk) {
    if (typeof chunk !== 'string' || chunk.length === 0 || chunk.length > 65536) return;
    streamTail = (streamTail + chunk).slice(-8192);
    if (streamTail.toLowerCase().indexOf('pinggy.') !== -1) {
      var found = extractPinggy(streamTail);
      if (found) {
        notifyPinggy(found);
      }
    }
  }

  function handleWsData(data) {
    try {
      if (typeof data === 'string') {
        inspectTextChunk(data);
      } else if (utf8Decoder && (data instanceof ArrayBuffer || ArrayBuffer.isView(data))) {
        var decoded = utf8Decoder.decode(data, { stream: true });
        if (decoded) inspectTextChunk(decoded);
      } else if (typeof Blob !== 'undefined' && data instanceof Blob && typeof data.text === 'function') {
        data.text().then(function(t) {
          if (t) inspectTextChunk(t);
        }).catch(function() {});
      }
    } catch (e) {}
  }

  function scanDomForPinggyAndSelection() {
    var sel = '';
    try {
      sel = window.getSelection ? window.getSelection().toString().trim() : '';
    } catch (e) {}

    var acc = streamTail;
    try {
      var rows = document.querySelectorAll('.xterm-rows, .xterm-accessibility, .xterm-screen');
      for (var i = 0; i < rows.length; i++) {
        acc += '\n' + (rows[i].innerText || rows[i].textContent || '');
      }
      if (document.body) {
        acc += '\n' + (document.body.innerText || '');
      }
    } catch (e) {}

    var found = extractPinggy(sel) || extractPinggy(acc) || lastPinggy;
    if (found) {
      notifyPinggy(found);
    }
    return {
      type: 'scan_result',
      pinggy: found || '',
      selection: sel,
      raw: acc.slice(-8192)
    };
  }

  try {
    port = browser.runtime.connectNative(NATIVE_APP);
    port.onMessage.addListener(function(msg) {
      if (!msg) return;
      if (msg.action === 'scan_copy') {
        var res = scanDomForPinggyAndSelection();
        try {
          port.postMessage(res);
        } catch (e) {}
      } else if (msg.action === 'focus_terminal') {
        focusTerminalElement();
      }
    });
  } catch (e) {}

  enforceDesktopViewport();
  document.addEventListener('DOMContentLoaded', enforceDesktopViewport, { once: true });

  // Hook page-level APIs via wrappedJSObject (bypasses CSP completely in GeckoView)
  try {
    var pageWin = window.wrappedJSObject || window;

    if (pageWin.WebSocket && pageWin.WebSocket.prototype) {
      var origWsSend = pageWin.WebSocket.prototype.send;
      var onWsMessage = function(ev) {
        try {
          if (ev && ev.data) {
            handleWsData(ev.data);
          }
        } catch (e) {}
      };
      var exportedOnWsMessage = typeof exportFunction === 'function'
        ? exportFunction(onWsMessage, pageWin)
        : onWsMessage;

      var wrappedWsSend = function() {
        try {
          if (!this._csBridgeAttached && typeof this.addEventListener === 'function') {
            this._csBridgeAttached = true;
            this.addEventListener('message', exportedOnWsMessage);
          }
        } catch (e) {}
        return origWsSend.apply(this, arguments);
      };
      if (typeof exportFunction === 'function') {
        pageWin.WebSocket.prototype.send = exportFunction(wrappedWsSend, pageWin);
      } else {
        pageWin.WebSocket.prototype.send = wrappedWsSend;
      }
    }

    if (pageWin.TextDecoder && pageWin.TextDecoder.prototype) {
      var origDecode = pageWin.TextDecoder.prototype.decode;
      var wrappedDecode = function() {
        var res = origDecode.apply(this, arguments);
        try {
          if (typeof res === 'string' && res.length > 0) {
            inspectTextChunk(res);
          }
        } catch (e) {}
        return res;
      };
      if (typeof exportFunction === 'function') {
        pageWin.TextDecoder.prototype.decode = exportFunction(wrappedDecode, pageWin);
      } else {
        pageWin.TextDecoder.prototype.decode = wrappedDecode;
      }
    }

    if (pageWin.CanvasRenderingContext2D && pageWin.CanvasRenderingContext2D.prototype) {
      var origFillText = pageWin.CanvasRenderingContext2D.prototype.fillText;
      var wrappedFillText = function(text) {
        try {
          if (typeof text === 'string' && text.length > 0) {
            inspectTextChunk(text);
          }
        } catch (e) {}
        return origFillText.apply(this, arguments);
      };
      if (typeof exportFunction === 'function') {
        pageWin.CanvasRenderingContext2D.prototype.fillText = exportFunction(wrappedFillText, pageWin);
      } else {
        pageWin.CanvasRenderingContext2D.prototype.fillText = wrappedFillText;
      }
    }

    Object.defineProperty(pageWin.document, 'hidden', {
      get: typeof exportFunction === 'function' ? exportFunction(function() { return false; }, pageWin) : function() { return false; },
      configurable: true
    });
    Object.defineProperty(pageWin.document, 'visibilityState', {
      get: typeof exportFunction === 'function' ? exportFunction(function() { return 'visible'; }, pageWin) : function() { return 'visible'; },
      configurable: true
    });
  } catch (e) {}

  window.addEventListener('visibilitychange', function(e) {
    e.stopImmediatePropagation();
  }, true);
})();
