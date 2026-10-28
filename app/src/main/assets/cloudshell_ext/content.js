(function() {
  'use strict';

  var NATIVE_APP = 'cloudshell';
  var lastPinggy = '';
  var port = null;

  var IGNORED_HOSTS = {
    'dashboard.pinggy.io': true,
    'www.pinggy.io': true,
    'pinggy.io': true,
    'a.pinggy.io': true,
    'docs.pinggy.io': true,
    'blog.pinggy.io': true
  };

  function injectAntiZoomStyle() {
    try {
      if (!document.head || document.getElementById('cs-antizoom-style')) return;
      var st = document.createElement('style');
      st.id = 'cs-antizoom-style';
      st.textContent = '*, .xterm, .xterm-rows, .xterm-screen { -webkit-user-select: text !important; user-select: text !important; } input, textarea, select, .xterm-helper-textarea { font-size: 16px !important; }';
      document.head.appendChild(st);
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
    if (!str || str.toLowerCase().indexOf('pinggy') === -1) return null;
    var clean = str.replace(/\x1b\[[0-9;?]*[a-zA-Z]/g, '').replace(/\x1b\][^\x07]*\x07/g, '');

    var tcpMatch = clean.match(/tcp:\/\/[a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-]+)*\.pinggy(?:-[a-zA-Z0-9]+)?\.(?:link|io|online):\d+/gi);
    if (tcpMatch && tcpMatch.length > 0) {
      return tcpMatch[tcpMatch.length - 1];
    }

    var hostPortMatch = clean.match(/[a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-]+)+\.pinggy(?:-[a-zA-Z0-9]+)?\.(?:link|io|online):\d+/gi);
    if (hostPortMatch && hostPortMatch.length > 0) {
      return 'tcp://' + hostPortMatch[hostPortMatch.length - 1];
    }

    var urlMatch = clean.match(/(?:tcp|https?):\/\/[a-zA-Z0-9-]+(?:\.[a-zA-Z0-9-]+)*\.pinggy(?:-[a-zA-Z0-9]+)?\.(?:link|io|online)(?::\d+)?/gi);
    if (urlMatch && urlMatch.length > 0) {
      for (var i = urlMatch.length - 1; i >= 0; i--) {
        var candidate = urlMatch[i];
        if (candidate.indexOf('@') !== -1) continue;
        var hostPart = candidate.replace(/^(?:tcp|https?):\/\//i, '').split(':')[0].split('/')[0].toLowerCase();
        if (!IGNORED_HOSTS[hostPart]) {
          return candidate;
        }
      }
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

  function scanDomForPinggyAndSelection() {
    var sel = '';
    try {
      sel = window.getSelection ? window.getSelection().toString().trim() : '';
    } catch (e) {}

    var acc = '';
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

  injectAntiZoomStyle();

  setInterval(function() {
    try {
      var xtermRows = document.querySelector('.xterm-rows, .xterm-accessibility');
      if (xtermRows) {
        var text = xtermRows.innerText || xtermRows.textContent || '';
        if (text && text.toLowerCase().indexOf('pinggy') !== -1) {
          var found = extractPinggy(text);
          if (found) {
            notifyPinggy(found);
          }
        }
      }
    } catch (e) {}
  }, 2500);
})();
