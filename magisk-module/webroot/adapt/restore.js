/* 配置备份 / 还原入口
 * 安装升级不覆盖 powerd.conf，包内新配置存为 powerd.conf.bak；
 * 这里在 WebUI「配置」页底部挂一张卡片，点按钮即可把 .bak 还原成生效配置。
 * 纯 DOM 注入，不依赖打包产物，前端更新后最多是这张卡片不显示。
 */
(function () {
  'use strict';
  var MOD = '';

  function exec(cmd) {
    return new Promise(function (resolve, reject) {
      if (!window.ksu || !window.ksu.exec) { reject(new Error('非 KernelSU WebUI')); return; }
      var name = 'pd_restore_cb_' + Date.now() + '_' + Math.floor(Math.random() * 1e6);
      var timer = setTimeout(function () {
        try { delete window[name]; } catch (e) {}
        reject(new Error('执行超时'));
      }, 20000);
      window[name] = function (errno, stdout, stderr) {
        clearTimeout(timer);
        try { delete window[name]; } catch (e) {}
        if (Number(errno) !== 0 && !stdout) {
          reject(new Error(String(stderr || ('errno ' + errno)).trim()));
          return;
        }
        resolve(String(stdout || ''));
      };
      try {
        window.ksu.exec(cmd, '{}', name);
      } catch (e) {
        clearTimeout(timer);
        try { delete window[name]; } catch (e2) {}
        reject(e);
      }
    });
  }

  function api(cmd) { return exec('sh ' + MOD + '/webroot/api.sh ' + cmd); }

  function resolveMod() {
    return exec('for d in /data/adb/modules/*; do [ -f "$d/module.prop" ] && grep -q "^id=powerd_freqcap\|^id=colorFC" "$d/module.prop" && echo "$d"; done')
      .then(function (out) {
        var line = String(out).trim().split('\n')[0];
        if (line) MOD = line;
      })
      .catch(function () {});
  }

  function build() {
    if (!MOD) return;
    // 各 Tab 都常驻 DOM，必须从「配置」页特有的滑杆往上找容器，并确认它当前可见
    var slider = document.querySelector('.slider-row');
    if (!slider) return;
    var host = slider.closest ? slider.closest('.container') : null;
    if (!host) return;
    if (host.offsetParent === null) return;                       // 当前不在配置页
    if (host.querySelector('#pd-restore-card')) return;           // 已经挂过

    var card = document.createElement('div');
    card.className = 'card';
    card.id = 'pd-restore-card';
    card.innerHTML =
      '<div class="card-header"><div class="card-title">配置备份</div></div>' +
      '<div class="card-desc">安装/升级不会覆盖当前 powerd.conf，新包自带的配置保存在 ' +
      'powerd.conf.bak。想回到包内默认值时点下面的按钮，当前配置会另存为 powerd.conf.prev。</div>' +
      '<div class="btn-row"><button class="btn btn-primary" id="pd-restore-btn">还原为包内配置</button></div>' +
      '<div class="hint" id="pd-restore-hint"></div>';
    host.appendChild(card);

    var btn = card.querySelector('#pd-restore-btn');
    var hint = card.querySelector('#pd-restore-hint');

    api('conf_bak').then(function (r) {
      if (String(r).trim() !== 'yes') {
        btn.disabled = true;
        hint.textContent = '当前没有 powerd.conf.bak（未升级过或已经还原过）';
      }
    }).catch(function () {});

    btn.addEventListener('click', function () {
      if (!confirm('用 powerd.conf.bak 覆盖当前配置并重启守护进程？\n当前配置会另存为 powerd.conf.prev')) return;
      btn.disabled = true;
      hint.textContent = '正在还原…';
      api('conf_restore')
        .then(function () {
          hint.textContent = '已还原，正在重启守护进程…';
          return api('restart');
        })
        .then(function () {
          setTimeout(function () { location.reload(); }, 800);
        })
        .catch(function (e) {
          btn.disabled = false;
          hint.textContent = '还原失败：' + (e && e.message ? e.message : e);
        });
    });
  }

  function tick() { try { build(); } catch (e) {} }

  resolveMod().then(function () {
    if (!MOD) return;
    setInterval(tick, 1000);
    tick();
  });
})();
