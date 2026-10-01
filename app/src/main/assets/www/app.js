(function () {
  'use strict';

  var CFG = window.FC_CONFIG || {};
  var AND = window.Android || null;
  var STORE_KEY = 'fc_settings_v1';
  var HARI = ['Minggu', 'Senin', 'Selasa', 'Rabu', 'Kamis', 'Jumat', 'Sabtu'];
  var BULAN = ['Januari', 'Februari', 'Maret', 'April', 'Mei', 'Juni', 'Juli', 'Agustus', 'September', 'Oktober', 'November', 'Desember'];

  // Fields that the remote page is allowed to change
  var REMOTE_FIELDS = ['textColor', 'cardColor', 'bgColor', 'accent', 'custom', 'customText'];

  var PRESETS = [
    { id: 'dark', label: 'Gelap', v: { textColor: '#ECEAE4', cardColor: '#1C1C1F', bgColor: '#0E0E0F' } },
    { id: 'light', label: 'Terang', v: { textColor: '#F4F2EC', cardColor: '#1C1C1F', bgColor: '#E7E6E2' } },
    { id: 'amoled', label: 'AMOLED', v: { textColor: '#FFFFFF', cardColor: '#0A0A0A', bgColor: '#000000' } }
  ];

  var PALETTES = {
    textColor: [['Putih gading', '#ECEAE4'], ['Putih', '#FFFFFF'], ['Amber', '#F2A33A'], ['Biru muda', '#5AC8FA'], ['Hijau', '#7ED9A0'], ['Merah', '#FF6B5E']],
    cardColor: [['Arang', '#1C1C1F'], ['Hitam', '#0A0A0A'], ['Biru tua', '#1F2A3A'], ['Coklat tua', '#3A2A20'], ['Hijau tua', '#1E3328'], ['Putih', '#F4F2EC']],
    bgColor: [['Hitam arang', '#0E0E0F'], ['Hitam pekat', '#000000'], ['Abu terang', '#E7E6E2'], ['Biru malam', '#0F1A24'], ['Merah marun', '#2A1212'], ['Abu tua', '#2A2A2E']],
    accent: [['Amber', '#F2A33A'], ['Biru muda', '#5AC8FA'], ['Hijau', '#7ED9A0'], ['Merah', '#FF6B5E'], ['Putih gading', '#ECEAE4'], ['Ungu', '#B98CFF']]
  };
  var COLOR_GROUPS = [
    ['textColor', 'Warna huruf'],
    ['cardColor', 'Warna kartu'],
    ['bgColor', 'Warna latar (background)'],
    ['accent', 'Warna detik / aksen']
  ];
  var TOGGLES = [
    ['f24', 'Format 24 jam', 'Matikan untuk 12 jam + AM/PM'],
    ['sec', 'Tampilkan detik', 'Kartu kecil di samping menit'],
    ['date', 'Tampilkan tanggal', 'Hari dan tanggal di bawah jam'],
    ['sound', 'Suara flip', 'Bunyi klik saat angka berganti'],
    ['awake', 'Layar tetap menyala', 'Layar tidak mati sendiri selama aplikasi terbuka'],
    ['night', 'Redup otomatis malam', 'Pukul 22.00–05.00 layar jadi lebih gelap']
  ];

  // ---------------------------------------------------------------- state
  function makeKey() {
    var chars = 'abcdefghjkmnpqrstuvwxyz23456789';
    var out = '';
    var arr = new Uint32Array(24);
    (window.crypto || window.msCrypto).getRandomValues(arr);
    for (var i = 0; i < arr.length; i++) out += chars.charAt(arr[i] % chars.length);
    return out;
  }

  var DEFAULTS = {
    f24: true, sec: true, date: true, sound: false, awake: true, night: true,
    brightManual: false, bright: 80,
    textColor: '#ECEAE4', cardColor: '#1C1C1F', bgColor: '#0E0E0F', accent: '#F2A33A',
    custom: false, customText: '',
    remote: true, name: 'Tablet', dbUrl: '', remoteUrl: '', key: ''
  };

  var S = {};
  (function load() {
    var saved = {};
    try { saved = JSON.parse(localStorage.getItem(STORE_KEY) || '{}') || {}; } catch (e) { saved = {}; }
    for (var k in DEFAULTS) S[k] = saved.hasOwnProperty(k) ? saved[k] : DEFAULTS[k];
    if (!S.key || S.key.length < 20) S.key = makeKey();
    save();
  })();

  function save() {
    try { localStorage.setItem(STORE_KEY, JSON.stringify(S)); } catch (e) { }
  }

  // ---------------------------------------------------------------- helpers
  function $(id) { return document.getElementById(id); }
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function isHex(c) { return typeof c === 'string' && /^#[0-9a-fA-F]{6}$/.test(c); }
  function lum(hex) {
    var h = String(hex || '#000000').replace('#', '');
    if (h.length === 3) h = h.charAt(0) + h.charAt(0) + h.charAt(1) + h.charAt(1) + h.charAt(2) + h.charAt(2);
    var r = parseInt(h.substr(0, 2), 16) / 255, g = parseInt(h.substr(2, 2), 16) / 255, b = parseInt(h.substr(4, 2), 16) / 255;
    return 0.2126 * r + 0.7152 * g + 0.0722 * b;
  }
  function dbBase() { return String(S.dbUrl || CFG.FIREBASE_DB_URL || '').trim().replace(/\/+$/, ''); }
  function remotePage() { return String(S.remoteUrl || CFG.REMOTE_PAGE_URL || '').trim(); }
  function devCode() { return (S.key.substr(0, 4) + '-' + S.key.substr(4, 4)).toUpperCase(); }

  // ---------------------------------------------------------------- flip cards
  function FlipCard(el) {
    this.el = el;
    el.innerHTML =
      '<div class="h top"><span></span></div>' +
      '<div class="h bot"><span></span></div>' +
      '<div class="leaf ltop"><span></span></div>' +
      '<div class="leaf lbot"><span></span></div>' +
      '<div class="split"></div><div class="notch l"></div><div class="notch r"></div>';
    var sp = el.getElementsByTagName('span');
    this.sTop = sp[0]; this.sBot = sp[1]; this.lTop = sp[2]; this.lBot = sp[3];
    this.val = null; this.t = null;
  }
  FlipCard.prototype.set = function (v, animate) {
    if (v === this.val) return false;
    var old = this.val;
    this.val = v;
    var self = this;
    if (!animate || old === null) {
      clearTimeout(this.t);
      this.el.classList.remove('flip');
      this.sTop.textContent = this.sBot.textContent = this.lTop.textContent = this.lBot.textContent = v;
      return false;
    }
    this.sTop.textContent = v;
    this.sBot.textContent = old;
    this.lTop.textContent = old;
    this.lBot.textContent = v;
    this.el.classList.remove('flip');
    void this.el.offsetWidth; // restart animation
    this.el.classList.add('flip');
    clearTimeout(this.t);
    this.t = setTimeout(function () {
      self.sBot.textContent = v;
      self.el.classList.remove('flip');
    }, 600);
    return true;
  };

  var cardH = new FlipCard($('cardH'));
  var cardM = new FlipCard($('cardM'));
  var cardS = new FlipCard($('cardS'));

  // ---------------------------------------------------------------- sound
  var audioCtx = null;
  function click() {
    if (!S.sound) return;
    try {
      if (!audioCtx) audioCtx = new (window.AudioContext || window.webkitAudioContext)();
      if (audioCtx.state === 'suspended') audioCtx.resume();
      var len = Math.floor(audioCtx.sampleRate * 0.035);
      var buf = audioCtx.createBuffer(1, len, audioCtx.sampleRate);
      var d = buf.getChannelData(0);
      for (var i = 0; i < len; i++) d[i] = (Math.random() * 2 - 1) * Math.pow(1 - i / len, 4);
      var src = audioCtx.createBufferSource();
      src.buffer = buf;
      var f = audioCtx.createBiquadFilter();
      f.type = 'bandpass'; f.frequency.value = 2200; f.Q.value = 0.8;
      var g = audioCtx.createGain();
      g.gain.value = 0.6;
      src.connect(f); f.connect(g); g.connect(audioCtx.destination);
      src.start();
    } catch (e) { }
  }

  // ---------------------------------------------------------------- render
  var root = document.documentElement;
  var clockEl = $('clock');

  function applyTheme() {
    root.style.setProperty('--bg', S.bgColor);
    root.style.setProperty('--card', S.cardColor);
    root.style.setProperty('--text', S.textColor);
    root.style.setProperty('--accent', S.accent);
    var light = lum(S.bgColor) > 0.5;
    root.style.setProperty('--info', light ? '#4A4A4F' : '#9A9AA0');
    root.style.setProperty('--custom', light ? '#1C1C1F' : '#ECEAE4');
    document.body.style.background = S.bgColor;
  }

  function hasCustom() { return !!S.custom && String(S.customText || '').trim().length > 0; }

  function layout() {
    var stage = $('stage');
    var vw = stage.clientWidth, vh = stage.clientHeight;
    if (!vw || !vh) return;
    var land = window.innerWidth >= window.innerHeight;
    document.body.classList.toggle('land', land);
    document.body.classList.toggle('port', !land);
    clockEl.className = (vw >= vh * 0.9) ? 'land' : 'port';
    var isLand = clockEl.className === 'land';
    var base = Math.min(vw, vh);

    var info = $('info'), dateEl = $('dateLine'), custEl = $('customLine');
    dateEl.style.display = S.date ? '' : 'none';
    custEl.style.display = hasCustom() ? '' : 'none';
    custEl.textContent = hasCustom() ? String(S.customText).trim() : '';
    dateEl.style.fontSize = (base * (isLand ? 0.042 : 0.04)) + 'px';
    custEl.style.fontSize = (base * (isLand ? 0.068 : 0.062)) + 'px';
    custEl.style.maxWidth = (vw * 0.9) + 'px';
    info.style.gap = (base * 0.025) + 'px';
    var showInfo = S.date || hasCustom();
    info.style.display = showInfo ? '' : 'none';
    var infoH = showInfo ? info.offsetHeight : 0;
    var infoGap = showInfo ? base * 0.055 : 0;
    clockEl.style.gap = infoGap + 'px';

    $('secwrap').style.display = S.sec ? '' : 'none';
    var ampm = $('ampm');
    ampm.style.display = S.f24 ? 'none' : '';

    var W, H, gap;
    if (isLand) {
      gap = vw * 0.028;
      var availH = vh * 0.86 - infoH - infoGap;
      var availW = vw * 0.92 - gap * (S.sec ? 2 : 1);
      W = Math.min(availW / (2 + (S.sec ? 0.3 : 0)), availH / 0.89);
      H = W * 0.89;
    } else {
      gap = vh * 0.022;
      var sRow = S.sec ? 1 : 0;
      var aH = vh * 0.88 - infoH - infoGap - gap - (sRow ? gap : 0);
      W = Math.min(aH / (1.9 + (sRow ? 0.27 : 0)), vw * 0.84);
      H = W * 0.95;
    }
    W = Math.max(40, W); H = Math.max(36, H);
    $('cards').style.gap = gap + 'px';
    var sW = W * 0.3, sH = sW * 0.88;
    var sw = $('secwrap');
    sw.style.gap = (sH * 0.18) + 'px';
    ampm.style.fontSize = Math.max(11, sH * 0.22) + 'px';

    root.style.setProperty('--W', W + 'px');
    root.style.setProperty('--H', H + 'px');
    root.style.setProperty('--fs', (H * 0.8) + 'px');
    root.style.setProperty('--r', (W * 0.057) + 'px');
    root.style.setProperty('--split', Math.max(2, H * 0.012) + 'px');
    root.style.setProperty('--nw', Math.max(6, H * 0.048) + 'px');
    root.style.setProperty('--nh', Math.max(12, H * 0.096) + 'px');
    root.style.setProperty('--sW', sW + 'px');
    root.style.setProperty('--sH', sH + 'px');
    root.style.setProperty('--sfs', (sH * 0.72) + 'px');
  }

  function tick(animate) {
    var d = new Date();
    var h = d.getHours();
    var ap = h < 12 ? 'AM' : 'PM';
    if (!S.f24) { h = h % 12; if (h === 0) h = 12; }
    var changed = false;
    changed = cardH.set(pad(h), animate) || changed;
    changed = cardM.set(pad(d.getMinutes()), animate) || changed;
    if (S.sec) changed = cardS.set(pad(d.getSeconds()), animate) || changed;
    $('ampm').textContent = ap;
    var dl = HARI[d.getDay()] + ' · ' + d.getDate() + ' ' + BULAN[d.getMonth()] + ' ' + d.getFullYear();
    var dateEl = $('dateLine');
    if (dateEl.textContent !== dl) { dateEl.textContent = dl; layout(); }
    if (changed) click();
    applyNight(d);
  }

  var lastNight = null;
  function applyNight(d) {
    var hr = d.getHours();
    var isNight = !!S.night && (hr >= 22 || hr < 5);
    if (isNight === lastNight) return;
    lastNight = isNight;
    $('dim').classList.toggle('on', isNight);
    applyBrightness();
  }

  function applyBrightness() {
    if (!AND || !AND.setBrightness) return;
    var lvl = S.brightManual ? S.bright / 100 : -1;
    if (lastNight) lvl = Math.min(lvl < 0 ? 0.15 : lvl, 0.15);
    AND.setBrightness(lvl);
  }

  function applyAwake() {
    if (AND && AND.setKeepScreenOn) AND.setKeepScreenOn(!!S.awake);
  }

  function renderAll() {
    applyTheme();
    cardH.val = cardM.val = cardS.val = null;
    tick(false);
    layout();
  }

  // ---------------------------------------------------------------- clock loop
  function loop() {
    tick(true);
    var ms = 1000 - (Date.now() % 1000);
    setTimeout(loop, ms + 5);
  }

  // ---------------------------------------------------------------- remote (Firebase REST + SSE)
  var es = null, retryT = null, hbT = null, pushT = null;
  var netState = 'off', lastOk = 0;

  function setNet(st) {
    netState = st;
    if (st === 'online') lastOk = Date.now();
    updateNetUi();
  }

  function updateNetUi() {
    var dot = $('netDotSmall'), txt = $('netText');
    var big = $('netdot');
    if (!S.remote) {
      dot.className = 'sdot'; txt.textContent = 'Remote dimatikan';
    } else if (!dbBase()) {
      dot.className = 'sdot err'; txt.textContent = 'Alamat Firebase belum diisi (lihat Pengaturan lanjutan)';
    } else if (netState === 'online') {
      dot.className = 'sdot ok'; txt.textContent = 'Terhubung ke server';
    } else if (netState === 'connecting') {
      dot.className = 'sdot wait'; txt.textContent = 'Menghubungkan…';
    } else {
      dot.className = 'sdot err'; txt.textContent = 'Terputus — mencoba lagi otomatis';
    }
    var showBig = S.remote && !!dbBase() && netState !== 'online' && (Date.now() - lastOk > 30000);
    big.classList.toggle('on', showBig);
  }

  function stopRemote() {
    if (es) { try { es.close(); } catch (e) { } es = null; }
    clearTimeout(retryT); retryT = null;
    clearInterval(hbT); hbT = null;
  }

  function scheduleRetry() {
    if (es) { try { es.close(); } catch (e) { } es = null; }
    clearTimeout(retryT);
    retryT = setTimeout(startRemote, 8000);
  }

  function startRemote() {
    stopRemote();
    if (!S.remote || !dbBase()) { setNet('off'); return; }
    setNet('connecting');
    var url = dbBase() + '/clocks/' + S.key + '/config.json';
    try { es = new EventSource(url); } catch (e) { setNet('error'); scheduleRetry(); return; }

    function onData(ev, isPatch) {
      var msg;
      try { msg = JSON.parse(ev.data); } catch (e) { return; }
      if (!msg) return;
      setNet('online');
      applyRemote(msg.path, msg.data, isPatch);
    }
    es.addEventListener('put', function (ev) { onData(ev, false); });
    es.addEventListener('patch', function (ev) { onData(ev, true); });
    es.addEventListener('keep-alive', function () { setNet('online'); });
    es.addEventListener('cancel', function () { setNet('error'); scheduleRetry(); });
    es.addEventListener('auth_revoked', function () { setNet('error'); scheduleRetry(); });
    es.onopen = function () { setNet('online'); };
    es.onerror = function () {
      setNet('error');
      if (!es || es.readyState === 2) scheduleRetry();
    };

    heartbeat();
    hbT = setInterval(heartbeat, 60000);
  }

  function applyRemote(path, data, isPatch) {
    var changed = false;
    if (path === '/' || path === '' || !path) {
      if (data === null) { pushConfig(true); return; }
      if (typeof data === 'object') {
        for (var i = 0; i < REMOTE_FIELDS.length; i++) {
          var f = REMOTE_FIELDS[i];
          if (data.hasOwnProperty(f)) changed = setRemoteField(f, data[f]) || changed;
        }
      }
    } else {
      var field = path.replace(/^\//, '').split('/')[0];
      if (REMOTE_FIELDS.indexOf(field) >= 0) changed = setRemoteField(field, data) || changed;
    }
    if (changed) {
      save();
      renderAll();
      if (panelOpen) syncPanel();
    }
  }

  function setRemoteField(f, v) {
    if (f === 'custom') { v = !!v; }
    else if (f === 'customText') { if (typeof v !== 'string') return false; v = v.substr(0, 60); }
    else { if (!isHex(v)) return false; v = v.toUpperCase(); }
    if (S[f] === v) return false;
    S[f] = v;
    return true;
  }

  function remotePayload() {
    var o = {};
    for (var i = 0; i < REMOTE_FIELDS.length; i++) o[REMOTE_FIELDS[i]] = S[REMOTE_FIELDS[i]];
    o.updatedAt = { '.sv': 'timestamp' };
    o.by = 'tablet';
    return o;
  }

  function pushConfig(now) {
    if (!S.remote || !dbBase()) return;
    clearTimeout(pushT);
    pushT = setTimeout(function () {
      fetch(dbBase() + '/clocks/' + S.key + '/config.json', {
        method: 'PUT', body: JSON.stringify(remotePayload())
      }).catch(function () { });
    }, now ? 0 : 900);
  }

  function heartbeat() {
    if (!S.remote || !dbBase()) return;
    fetch(dbBase() + '/clocks/' + S.key + '/status.json', {
      method: 'PUT',
      body: JSON.stringify({ name: S.name || 'Tablet', lastSeen: { '.sv': 'timestamp' }, ver: '1.0' })
    }).then(function (r) {
      if (r.ok) { if (netState !== 'online') setNet('online'); }
    }).catch(function () { });
  }

  // ---------------------------------------------------------------- settings panel
  var panelOpen = false;
  var panel = $('panel');

  function openPanel() {
    panelOpen = true;
    panel.classList.remove('hidden');
    document.body.classList.add('panel-open');
    hideGear();
    syncPanel();
    setTimeout(layout, 30);
  }
  function closePanel() {
    panelOpen = false;
    var a = document.activeElement;
    if (a && a.blur) a.blur();
    panel.classList.add('hidden');
    document.body.classList.remove('panel-open');
    setTimeout(layout, 30);
  }
  window.onBack = function () {
    if (panelOpen) { closePanel(); return true; }
    return false;
  };

  function changed(remoteField) {
    save();
    renderAll();
    syncPanel();
    if (remoteField) pushConfig(false);
  }

  function buildPanel() {
    // theme presets
    var seg = $('themeSeg');
    PRESETS.forEach(function (p) {
      var b = document.createElement('button');
      b.textContent = p.label;
      b.setAttribute('data-id', p.id);
      b.addEventListener('click', function () {
        for (var k in p.v) S[k] = p.v[k];
        changed(true);
      });
      seg.appendChild(b);
    });

    // color groups
    var box = $('colorBox');
    COLOR_GROUPS.forEach(function (g) {
      var key = g[0];
      var wrap = document.createElement('div');
      wrap.className = 'cgroup';
      wrap.innerHTML = '<div class="chead"><div class="rl"></div><div class="chex"></div></div><div class="swatches"></div>';
      wrap.querySelector('.rl').textContent = g[1];
      var sws = wrap.querySelector('.swatches');
      PALETTES[key].forEach(function (c) {
        var b = document.createElement('button');
        b.className = 'swatch';
        b.style.background = c[1];
        b.setAttribute('aria-label', g[1] + ': ' + c[0]);
        b.setAttribute('data-hex', c[1]);
        b.addEventListener('click', function () { S[key] = c[1]; changed(true); });
        sws.appendChild(b);
      });
      var cp = document.createElement('input');
      cp.type = 'color';
      cp.className = 'cpick';
      cp.setAttribute('aria-label', g[1] + ' kustom');
      cp.addEventListener('input', function () { S[key] = cp.value.toUpperCase(); save(); renderAll(); });
      cp.addEventListener('change', function () { S[key] = cp.value.toUpperCase(); changed(true); });
      sws.appendChild(cp);
      wrap.setAttribute('data-key', key);
      box.appendChild(wrap);
    });

    // toggles
    var tb = $('toggleBox');
    TOGGLES.forEach(function (t) {
      var lab = document.createElement('label');
      lab.className = 'row';
      lab.innerHTML = '<div class="rtxt"><div class="rl"></div><div class="rd"></div></div><input type="checkbox" class="sw">';
      lab.querySelector('.rl').textContent = t[1];
      lab.querySelector('.rd').textContent = t[2];
      var cb = lab.querySelector('input');
      cb.setAttribute('data-key', t[0]);
      cb.addEventListener('change', function () {
        S[t[0]] = cb.checked;
        if (t[0] === 'awake') applyAwake();
        if (t[0] === 'night') { lastNight = null; }
        if (t[0] === 'sound' && cb.checked) click();
        changed(false);
      });
      tb.appendChild(lab);
    });

    // custom text
    $('customOn').addEventListener('change', function () { S.custom = this.checked; changed(true); });
    $('customText').addEventListener('input', function () {
      S.customText = this.value.substr(0, 60);
      save(); renderAll(); pushConfig(false);
    });

    // remote
    $('remoteOn').addEventListener('change', function () {
      S.remote = this.checked; save(); syncPanel(); startRemote();
    });
    $('devName').addEventListener('change', function () {
      S.name = this.value.trim() || 'Tablet'; save(); syncPanel(); heartbeat();
    });
    $('dbUrl').addEventListener('change', function () {
      S.dbUrl = this.value.trim(); save(); syncPanel(); startRemote();
    });
    $('remoteUrl').addEventListener('change', function () {
      S.remoteUrl = this.value.trim(); save(); syncPanel();
    });
    $('newKeyBtn').addEventListener('click', function () {
      if (!window.confirm('Buat kode perangkat baru? Link/QR lama tidak akan berfungsi lagi.')) return;
      S.key = makeKey(); save(); syncPanel(); startRemote(); pushConfig(true);
    });

    // brightness
    $('brightOn').addEventListener('change', function () {
      S.brightManual = this.checked; save(); syncPanel(); applyBrightness();
    });
    $('bright').addEventListener('input', function () {
      S.bright = parseInt(this.value, 10) || 80;
      $('brightVal').textContent = S.bright + '%';
      save(); applyBrightness();
    });

    // autostart
    $('autoOn').addEventListener('change', function () {
      if (AND && AND.setAutostart) AND.setAutostart(this.checked);
      syncPanel();
    });
    $('overlayBtn').addEventListener('click', function () {
      if (AND && AND.requestOverlayPermission) AND.requestOverlayPermission();
    });

    $('closeBtn').addEventListener('click', closePanel);
  }

  var lastQr = '';
  function syncPanel() {
    // theme
    var btns = $('themeSeg').getElementsByTagName('button');
    for (var i = 0; i < btns.length; i++) {
      var p = PRESETS[i];
      var on = p.v.textColor === S.textColor && p.v.cardColor === S.cardColor && p.v.bgColor === S.bgColor;
      btns[i].classList.toggle('on', on);
    }
    // colors
    var groups = $('colorBox').getElementsByClassName('cgroup');
    for (var g = 0; g < groups.length; g++) {
      var key = groups[g].getAttribute('data-key');
      groups[g].querySelector('.chex').textContent = String(S[key]).toUpperCase();
      var sws = groups[g].getElementsByClassName('swatch');
      for (var s = 0; s < sws.length; s++) {
        sws[s].classList.toggle('on', sws[s].getAttribute('data-hex').toUpperCase() === String(S[key]).toUpperCase());
      }
      var cp = groups[g].querySelector('.cpick');
      if (document.activeElement !== cp) cp.value = String(S[key]).toLowerCase();
    }
    // toggles
    var cbs = $('toggleBox').getElementsByTagName('input');
    for (var c = 0; c < cbs.length; c++) cbs[c].checked = !!S[cbs[c].getAttribute('data-key')];
    // custom
    $('customOn').checked = !!S.custom;
    var ct = $('customText');
    ct.disabled = !S.custom;
    if (document.activeElement !== ct) ct.value = S.customText || '';
    // remote
    $('remoteOn').checked = !!S.remote;
    $('remoteDetail').style.display = S.remote ? '' : 'none';
    var dn = $('devName');
    if (document.activeElement !== dn) dn.value = S.name || '';
    $('devCode').textContent = devCode();
    var du = $('dbUrl');
    if (document.activeElement !== du) du.value = dbBase();
    var ru = $('remoteUrl');
    if (document.activeElement !== ru) ru.value = remotePage();
    updateQr();
    updateNetUi();
    // brightness
    $('brightOn').checked = !!S.brightManual;
    $('bright').value = S.bright;
    $('bright').disabled = !S.brightManual;
    $('brightVal').textContent = S.bright + '%';
    $('bright').parentNode.classList.toggle('off', !S.brightManual);
    // autostart
    var auto = AND && AND.getAutostart ? AND.getAutostart() : true;
    $('autoOn').checked = !!auto;
    var canBoot = AND && AND.canAutostartFromBoot ? AND.canAutostartFromBoot() : true;
    $('overlayWarn').classList.toggle('hidden', !auto || canBoot);
  }

  function remoteLink() {
    var page = remotePage();
    if (!page || !dbBase()) return '';
    return page + '#db=' + encodeURIComponent(dbBase()) + '&k=' + S.key + '&n=' + encodeURIComponent(S.name || 'Tablet');
  }

  function updateQr() {
    var link = remoteLink();
    if (link === lastQr) return;
    lastQr = link;
    var box = $('qr');
    if (!link) { box.textContent = 'Isi alamat Firebase dulu'; return; }
    try {
      var qr = window.qrcode(0, 'M');
      qr.addData(link);
      qr.make();
      box.innerHTML = qr.createSvgTag({ cellSize: 4, margin: 0, scalable: true });
    } catch (e) {
      box.textContent = 'QR gagal dibuat';
    }
  }

  // ---------------------------------------------------------------- gear button
  var gearT = null;
  function showGear() {
    $('gear').classList.add('show');
    clearTimeout(gearT);
    gearT = setTimeout(hideGear, 4000);
  }
  function hideGear() { $('gear').classList.remove('show'); }

  $('stage').addEventListener('click', function (e) {
    if (panelOpen) { closePanel(); return; }
    if (e.target.closest && e.target.closest('#gear')) return;
    showGear();
    // unlock audio on first tap
    if (S.sound && audioCtx && audioCtx.state === 'suspended') audioCtx.resume();
  });
  $('gear').addEventListener('click', function (e) { e.stopPropagation(); openPanel(); });

  // ---------------------------------------------------------------- start
  window.addEventListener('resize', function () { layout(); });
  window.onAppResume = function () {
    renderAll();
    applyAwake();
    lastNight = null;
    applyNight(new Date());
    if (S.remote && dbBase() && (!es || es.readyState === 2)) startRemote();
  };
  window.addEventListener('online', function () { if (S.remote) startRemote(); });
  setInterval(updateNetUi, 10000);

  buildPanel();
  renderAll();
  applyAwake();
  applyBrightness();
  if (document.fonts && document.fonts.ready) document.fonts.ready.then(layout);
  setTimeout(loop, 1000 - (Date.now() % 1000) + 5);
  startRemote();
})();
