(function () {
  'use strict';
  var KEY = 'pd_version';
  var fired = 0;

  function target(v) { return '版本 ' + v; }

  function paint(v) {
    if (!v) return;
    var txt = target(v);
    var els = document.querySelectorAll('span.version');
    for (var i = 0; i < els.length; i++) {
      if (els[i].textContent !== txt) els[i].textContent = txt;
    }
  }

  function exec(cmd) {
    return new Promise(function (resolve, reject) {
      if (!window.ksu || !window.ksu.exec) { reject(new Error('no ksu')); return; }
      var name = 'pd_ver_cb_' + Date.now() + '_' + Math.floor(Math.random() * 1e6);
      var t = setTimeout(function () { try { delete window[name]; } catch (e) {} reject(new Error('timeout')); }, 15000);
      window[name] = function (errno, stdout) {
        clearTimeout(t); try { delete window[name]; } catch (e) {}
        resolve(String(stdout || ''));
      };
      try { window.ksu.exec(cmd, '{}', name); } catch (e) { clearTimeout(t); reject(e); }
    });
  }

  var cached = null;
  try { cached = localStorage.getItem(KEY); } catch (e) {}

  var mo = null;
  function stopWatch() { if (mo) { mo.disconnect(); mo = null; } }

  mo = new MutationObserver(function () {
    if (!cached) { stopWatch(); return; }
    if (++fired > 200) { stopWatch(); return; }
    var el = document.querySelector('span.version');
    if (!el) return;
    if (el.textContent === target(cached)) return;
    paint(cached);
  });

  function start() {
    var root = document.getElementById('root') || document.body;
    mo.observe(root, { childList: true, subtree: true });
    paint(cached);
    exec('for d in /data/adb/modules/*; do [ -f "$d/module.prop" ] && grep -q "^id=powerd_freqcap\|^id=colorFC" "$d/module.prop" && { sh "$d/webroot/api.sh" about; break; }; done')
      .then(function (out) {
        var m = out.match(/^version=(.+)$/m);
        if (!m) return;
        cached = m[1].trim();
        try { localStorage.setItem(KEY, cached); } catch (e) {}
        paint(cached);
      })
      .catch(function () {});
  }

  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
  else start();
})();
