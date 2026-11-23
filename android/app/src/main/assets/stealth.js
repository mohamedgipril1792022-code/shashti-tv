// Sajo TV — runs at document start in every frame (page and players).
// 1) generic "stealth": anti-adblock detectors see an ordinary browser
// 2) the uBlock Origin scriptlets the filter lists name for this site (+js rules), in our own implementation
(function sajo() {
  'use strict';
  // On some boxes the app's bridge (window.Android) appears a moment after the page starts:
  // wait for it instead of giving up (giving up switched every helper off for the whole page)
  // ------------------------------------------------------------------ YouTube (only on youtube.com — nothing else changes)
  // Like Brave/uBlock: the ads are taken out of the player's data before the player reads it, the ad boxes are hidden,
  // and if an ad still starts it is skipped at once.
  if (!window.__sajoYT && /(^|\.)youtube(-nocookie)?\.com$/.test(location.hostname)) (function () {
    window.__sajoYT = true;   // runs at the very start, without waiting for the app's bridge (YouTube sets its data at once)
    var AD_KEYS = ['adPlacements', 'playerAds', 'adSlots', 'adBreakHeartbeatParams'];
    function strip(o, depth) {
      if (!o || typeof o !== 'object' || depth > 6) return o;
      for (var i = 0; i < AD_KEYS.length; i++) if (AD_KEYS[i] in o) { try { delete o[AD_KEYS[i]]; } catch (e) { } }
      if (o.playerResponse) strip(o.playerResponse, depth + 1);
      if (Array.isArray(o)) for (var j = 0; j < o.length && j < 10; j++) strip(o[j], depth + 1);
      return o;
    }
    // 1) the first player data on the page
    ['ytInitialPlayerResponse', 'ytplayer'].forEach(function (k) {
      var val;
      try {
        Object.defineProperty(window, k, { configurable: true, get: function () { return val; }, set: function (v) { val = strip(v, 0); } });
      } catch (e) { }
    });
    // 2) every later JSON the page reads (next videos, mobile site)
    var jp = JSON.parse;
    JSON.parse = function () { var r = jp.apply(this, arguments); try { if (r && typeof r === 'object' && (r.adPlacements || r.playerAds || r.adSlots || r.playerResponse)) strip(r, 0); } catch (e) { } return r; };
    // 3) the player API answers (fetch / XHR to /youtubei/v1/player)
    if (window.fetch) {
      var of = window.fetch;
      window.fetch = function (input, init) {
        var url = typeof input === 'string' ? input : (input && input.url) || '';
        var p = of.apply(this, arguments);
        if (!/\/youtubei\/v1\/(player|next)/.test(url)) return p;
        return p.then(function (res) {
          return res.clone().text().then(function (t) {
            if (!/adPlacements|playerAds|adSlots/.test(t)) return res;
            try {
              var o = strip(jp(t), 0);
              return new Response(JSON.stringify(o), { status: res.status, statusText: res.statusText, headers: res.headers });
            } catch (e) { return res; }
          }, function () { return res; });
        });
      };
    }
    var xo = XMLHttpRequest.prototype.open;
    XMLHttpRequest.prototype.open = function (m, u) { this.__sajoYt = /\/youtubei\/v1\/(player|next)/.test(String(u)); return xo.apply(this, arguments); };
    ['responseText', 'response'].forEach(function (k) {
      var d = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, k);
      if (!d || !d.get) return;
      Object.defineProperty(XMLHttpRequest.prototype, k, {
        configurable: true,
        get: function () {
          var v = d.get.call(this);
          if (!this.__sajoYt || this.readyState !== 4) return v;
          try {
            if (typeof v === 'string' && /adPlacements|playerAds|adSlots/.test(v)) return JSON.stringify(strip(jp(v), 0));
            if (v && typeof v === 'object') return strip(v, 0);
          } catch (e) { }
          return v;
        }
      });
    });
    // 4) the ad boxes in the pages
    var css = '#masthead-ad,#player-ads,ytd-ad-slot-renderer,ytd-in-feed-ad-layout-renderer,ytd-banner-promo-renderer,ytd-statement-banner-renderer,' +
      'ytd-promoted-sparkles-web-renderer,ytd-promoted-video-renderer,ytd-display-ad-renderer,ytd-companion-slot-renderer,ytd-action-companion-ad-renderer,' +
      'ytd-rich-item-renderer:has(ytd-ad-slot-renderer),ytd-search-pyv-renderer,.ytd-mealbar-promo-renderer,ytm-promoted-sparkles-web-renderer,' +
      'ytm-companion-ad-renderer,ytm-ad-slot-renderer,ad-slot-renderer,ytm-promoted-video-renderer,.ytp-ad-overlay-container,.ytp-ad-image-overlay,' +
      '.ytp-suggested-action-badge,ytm-rich-item-renderer:has(ad-slot-renderer){display:none!important}';
    var addCss = function () { if (!document.documentElement || document.getElementById('sajo-yt')) return; var st = document.createElement('style'); st.id = 'sajo-yt'; st.textContent = css; (document.head || document.documentElement).appendChild(st); };
    addCss(); document.addEventListener('DOMContentLoaded', addCss);
    // 5) an ad that still plays: muted, jumped to its end, "Skip" pressed
    var sped = null;
    setInterval(function () {
      var pl = document.querySelector('.ad-showing,.ad-interrupting');
      if (!pl) {
        // the same <video> carries the film after the ad: put speed and sound back
        if (sped) { try { sped.playbackRate = 1; sped.muted = false; } catch (e) { } sped = null; }
        return;
      }
      var v = pl.querySelector('video');
      if (v) { try { v.muted = true; if (isFinite(v.duration) && v.duration > 0) v.currentTime = v.duration; v.playbackRate = 16; sped = v; } catch (e) { } }
      var b = document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytm-ad-skip-button,button[class*="skip-button"]');
      if (b) { try { b.click(); } catch (e) { } }
    }, 300);
  })();

  if (window.__sajoSL || window.__sajoWait) return;
  if (!(window.Android && window.Android.sl)) {
    window.__sajoWait = true;
    var tries = 0, me = sajo;
    (function wait() {
      if (window.Android && window.Android.sl) { window.__sajoWait = false; me(); }
      else if (tries++ < 200) setTimeout(wait, 25);
      else window.__sajoWait = false;
    })();
    return;
  }
  window.__sajoSL = true;
  var A = window.Android;



  // players lock the screen to landscape when going full screen; a TV can't, the call fails and the player gives up
  // its full screen ("screen.orientation.lock() is not available on this device") — a TV is landscape already: say yes
  try {
    if (screen.orientation) {
      var okLock = function () { return Promise.resolve(); };
      try { Object.defineProperty(screen.orientation, 'lock', { configurable: true, value: okLock }); } catch (e) { screen.orientation.lock = okLock; }
      try { Object.defineProperty(screen.orientation, 'unlock', { configurable: true, value: function () { } }); } catch (e) { }
    }
    if (screen.lockOrientation) screen.lockOrientation = function () { return true; };
  } catch (e) { }

  // ------------------------------------------------------------------ continue watching: progress + resume
  (function () {
    var last = 0, resumed = [];
    document.addEventListener('timeupdate', function (ev) {
      var v = ev.target;
      if (!v || v.tagName !== 'VIDEO' || !(v.duration > 60) || !isFinite(v.duration)) return;
      var now = Date.now();
      if (now - last < 5000 && v.currentTime / v.duration < 0.97) return;
      last = now;
      try { if (A.progress) A.progress(v.currentTime, v.duration); } catch (e) { }
    }, true);
    document.addEventListener('playing', function (ev) {
      var v = ev.target;
      if (!v || v.tagName !== 'VIDEO' || resumed.indexOf(v) >= 0) return;
      resumed.push(v);
      if (!(v.duration > 60)) return;
      var t = 0;
      try { t = A.resumeAt ? +A.resumeAt() : 0; } catch (e) { }
      if (t > 30 && v.currentTime < 5 && t < v.duration * 0.97) { try { v.currentTime = t - 3; } catch (e) { } }
    }, true);
  })();

  // ------------------------------------------------------------------ Back closes an ad laid over the page/player
  // never touches a video or the player itself; prefers the ad's own close (×) button
  function closeAd(isTop, strict) {
    var vw = innerWidth, vh = innerHeight, va = vw * vh;
    if (!va || !document.body) return false;
    var fs = document.fullscreenElement || document.webkitFullscreenElement;
    var PLAYERISH = /(^|\s)(jw-[a-z-]+|jwplayer|vjs-[a-z-]+|video-js|plyr__[a-z-]+|plyr|art-[a-z-]+|dplayer-[a-z-]+|mejs__[a-z-]+|fp-[a-z-]+|ytp-[a-z-]+|html5-[a-z-]+|ytm-[a-z-]+)(\s|$)/i;
    var ADTEXT = /not a robot|confirm you|scan (the )?qr|scan to|download (the )?app|حمّ?ل التطبيق|تأكيد أنك لست/i;
    function hasPlayer(e) {
      if (e.tagName === 'VIDEO' || e.querySelector('video')) return true;
      var fr = e.tagName === 'IFRAME' ? [e] : e.querySelectorAll('iframe');
      for (var i = 0; i < fr.length; i++) {
        var x = fr[i];
        if (x.hasAttribute('allowfullscreen') || x.hasAttribute('webkitallowfullscreen') || /fullscreen/i.test(x.getAttribute('allow') || '')) return true;
      }
      return false;
    }
    function cls(n) { return (typeof n.className === 'string' ? n.className : '') + ' ' + (n.id || ''); }
    function areaOf(r) { return Math.max(0, Math.min(r.right, vw) - Math.max(r.left, 0)) * Math.max(0, Math.min(r.bottom, vh) - Math.max(r.top, 0)); }
    // from the element under a point, climb while the parents are still "the ad" (no video/player inside);
    // keep the outermost one that is laid over the page (positioned / stacked) or that says it is an ad
    function overlayOf(el) {
      var best = null, adText = false;
      for (var n = el; n && n !== document.body && n !== document.documentElement; n = n.parentElement) {
        if (hasPlayer(n) || (fs && (n.contains(fs) || fs.contains(n)))) break;
        var st = getComputedStyle(n), p = st.position, z = parseInt(st.zIndex, 10) || 0;
        var r = n.getBoundingClientRect(), a = areaOf(r);
        if (!adText && a < va * 0.9 && ADTEXT.test((n.innerText || '').slice(0, 400))) adText = true;
        if (PLAYERISH.test(cls(n)) && !adText) break;     // the player's own parts, unless it plainly is the ad
        if (r.top <= 4 && r.height < vh * 0.2 && r.width > vw * 0.8) continue;   // the site's header bar
        // on the page itself only clear overlays count (pages lay out sidebars etc. with positioning);
        // inside a player frame anything stacked over the player does
        var laid = isTop ? (p === 'fixed' || (p === 'absolute' && z >= 10)) : (p === 'fixed' || p === 'absolute' || z > 0);
        if ((laid && a >= va * (p === 'fixed' && isTop ? 0.04 : 0.1)) || (adText && a >= va * 0.05)) best = n;
      }
      return best;
    }
    var CLOSE_TXT = /^(×|✕|✖|✗|╳|x|X|close|Close|CLOSE|إغلاق|اغلاق|skip|Skip|SKIP|skip ad|Skip Ad)$/;
    function closeBtn(ov) {
      var r0 = ov.getBoundingClientRect(), best = null, bestScore = -1;
      var l = ov.querySelectorAll('*');
      for (var i = 0; i < l.length && i < 600; i++) {
        var e = l[i], r = e.getBoundingClientRect();
        if (r.width < 6 || r.height < 6 || r.width > 120 || r.height > 120) continue;
        var t = (e.textContent || '').trim();
        var hit = (t.length <= 12 && CLOSE_TXT.test(t)) || /close|dismiss/i.test(cls(e) + ' ' + (e.getAttribute('aria-label') || '') + ' ' + (e.getAttribute('title') || ''));
        if (!hit) continue;
        var score = (r.top - r0.top < r0.height * 0.3 ? 2 : 0) + (r.right > r0.left + r0.width * 0.6 || r.left < r0.left + r0.width * 0.4 ? 1 : 0);
        if (score > bestScore) { best = e; bestScore = score; }
      }
      return best;
    }
    function click(e) {
      var r = e.getBoundingClientRect(), o = { bubbles: true, cancelable: true, view: window, clientX: r.left + r.width / 2, clientY: r.top + r.height / 2 };
      try { e.dispatchEvent(new MouseEvent('mousedown', o)); e.dispatchEvent(new MouseEvent('mouseup', o)); } catch (x) { }
      try { e.click(); } catch (x) { }
    }
    var pts = [[.5, .5], [.3, .45], [.7, .45], [.5, .75], [.3, .7], [.7, .7], [.5, .92], [.5, .3], [.5, .15]];
    // 1) whatever is stacked ON TOP of the video / the player's frame is an ad over the player
    //    (players don't put frames or big boxes over their own picture; their controls are jw-/vjs-/plyr parts)
    if (document.elementsFromPoint) for (var s1 = 0; s1 < pts.length; s1++) {
      var stack = document.elementsFromPoint(vw * pts[s1][0], vh * pts[s1][1]), mi = -1;
      for (var k = 0; k < stack.length; k++) {
        var t = stack[k].tagName;
        if ((t === 'VIDEO' || t === 'IFRAME') && areaOf(stack[k].getBoundingClientRect()) >= va * 0.08) { mi = k; break; }
      }
      if (mi <= 0) continue;
      var med = stack[mi], mr = areaOf(med.getBoundingClientRect());
      for (var k2 = 0; k2 < mi; k2++) {
        var e = stack[k2];
        if (e.contains(med) || e === document.body || e === document.documentElement) continue;
        var c = e, bad = false;
        while (c.parentElement && !c.parentElement.contains(med) && c.parentElement !== document.body) c = c.parentElement;
        for (var q = e; q && q !== c.parentElement; q = q.parentElement) if (PLAYERISH.test(cls(q))) { bad = true; break; }
        if (bad || hasPlayer(c) && c.tagName !== 'IFRAME') continue;
        var ca = areaOf(c.getBoundingClientRect());
        if (ca < va * 0.1 && ca < mr * 0.25) continue;               // a small control/button, not an overlay
        var b0 = c.tagName === 'IFRAME' ? null : closeBtn(c);
        // automatic guard: only what is plainly an ad (says so, or has its own ✕) — never a guess
        if (strict && !b0 && !ADTEXT.test((c.innerText || '').slice(0, 600))) continue;
        if (b0) click(b0);
        setTimeout(function (o) { return function () { if (o.isConnected) o.remove(); }; }(c), b0 ? 250 : 0);
        return true;
      }
    }
    if (strict) return false;
    // 2) a box laid over the page
    for (var i = 0; i < pts.length; i++) {
      var x = vw * pts[i][0], y = vh * pts[i][1];
      var top = document.elementFromPoint(x, y);
      if (!top || top === document.body || top === document.documentElement || top.tagName === 'VIDEO') continue;
      var ov = overlayOf(top);
      if (!ov) continue;
      var b = closeBtn(ov);
      if (b) click(b);
      setTimeout(function (o) { return function () { if (o.isConnected) { o.remove(); } }; }(ov), b ? 250 : 0);
      document.documentElement.style.overflow = '';
      if (document.body) document.body.style.overflow = '';
      return true;
    }
    return false;
  }
  if (window === window.top) window.__sajoCloseAd = function () { return closeAd(true); };

  // ------------------------------------------------------------------ real full screen for players in frames
  // The player's own ⛶ button inside a frame can only fill its frame ("still inside the site").
  // The frame tells its parent, which stretches that frame over the whole screen, up to the top page.
  function stretch(p) {
    if (window.__shFs) return;
    window.__shFs = { el: p, st: p.getAttribute('style') || '', anc: [] };
    p.setAttribute('style', window.__shFs.st + ';position:fixed!important;left:0!important;top:0!important;width:100vw!important;height:100vh!important;' +
      'z-index:2147483647!important;background:#000!important;border:0!important;margin:0!important;max-width:none!important;max-height:none!important;transform:none!important');
    for (var q = p.parentElement; q && q !== document.documentElement; q = q.parentElement) {
      window.__shFs.anc.push([q, q.getAttribute('style')]);
      q.setAttribute('style', (q.getAttribute('style') || '') + ';transform:none!important;filter:none!important;backdrop-filter:none!important;contain:none!important;' +
        'perspective:none!important;will-change:auto!important;overflow:visible!important;z-index:2147483647!important');
    }
    document.documentElement.style.overflow = 'hidden';
    if (document.body) document.body.style.overflow = 'hidden';
  }
  function unstretch() {
    var f = window.__shFs;
    if (!f) return false;
    f.el.setAttribute('style', f.st);
    (f.anc || []).forEach(function (z) { if (z[1] == null) z[0].removeAttribute('style'); else z[0].setAttribute('style', z[1]); });
    document.documentElement.style.overflow = '';
    if (document.body) document.body.style.overflow = '';
    window.__shFs = null;
    return true;
  }
  window.addEventListener('message', function (ev) {
    var d = ev.data;
    if (!d || (d.sajoFs !== 1 && d.sajoFs !== 0)) return;
    if (d.sajoFs === 1) {
      var l = document.getElementsByTagName('iframe');
      for (var i = 0; i < l.length; i++) if (l[i].contentWindow === ev.source) { stretch(l[i]); break; }
    } else unstretch();
    if (window !== window.top) { try { parent.postMessage({ sajoFs: d.sajoFs }, '*'); } catch (e) { } }
    else if (d.sajoFs === 1 ? A.fsOn : A.fsOff) (d.sajoFs === 1 ? A.fsOn() : A.fsOff());
  });
  // Full screen works like a browser's: the real Android full screen (the mouse is routed into it by the app).
  // Player frames are allowed to go full screen themselves (their own ⛶ button).
  window.__sajoFsClear = function () { };
  if (window === window.top) {
    var allowFs = function (f) {
      if (f.tagName !== 'IFRAME') return;
      if (!f.hasAttribute('allowfullscreen')) f.setAttribute('allowfullscreen', '');
      if (!/fullscreen/.test(f.getAttribute('allow') || '')) f.setAttribute('allow', ((f.getAttribute('allow') || '') + ';fullscreen').replace(/^;/, ''));
    };
    new MutationObserver(function (ms) {
      for (var i = 0; i < ms.length; i++) for (var j = 0; j < ms[i].addedNodes.length; j++) {
        var n = ms[i].addedNodes[j];
        if (n.nodeType !== 1) continue;
        allowFs(n);
        if (n.querySelectorAll) { var l = n.querySelectorAll('iframe'); for (var k = 0; k < l.length; k++) allowFs(l[k]); }
      }
    }).observe(document, { childList: true, subtree: true });
  }

  // double OK on the player = press the player's OWN full-screen button (that full screen works best, as the user found)
  var FSBTN = '.jw-icon-fullscreen,.vjs-fullscreen-control,[data-plyr="fullscreen"],.plyr__control[data-plyr=fullscreen],.fp-fullscreen,' +
              '.art-control-fullscreen,.dplayer-full-icon,.mejs__fullscreen-button button,[aria-label*="ull screen" i],[aria-label*="ullscreen" i],' +
              '[title*="ull screen" i],[title*="ullscreen" i],[class*="fullscreen" i],[class*="full-screen" i]';
  function pressFsButton() {
    var l = document.querySelectorAll(FSBTN), best = null;
    for (var i = 0; i < l.length; i++) {
      var r = l[i].getBoundingClientRect();
      if (r.width < 6 || r.height < 6 || r.width > 200 || r.height > 200) continue;
      if (/exit|off/i.test((l[i].getAttribute('aria-label') || '') + ' ' + (typeof l[i].className === 'string' ? l[i].className : ''))) continue;
      best = l[i]; if (/jw-icon-fullscreen|vjs-fullscreen|plyr|fp-fullscreen/.test(l[i].className)) break;
    }
    if (!best) return false;
    try { best.dispatchEvent(new MouseEvent('mousedown', { bubbles: true })); best.dispatchEvent(new MouseEvent('mouseup', { bubbles: true })); } catch (e) { }
    try { best.click(); } catch (e) { }
    if (A.fsPressed) A.fsPressed();
    return true;
  }
  // the very first press on the player: full screen at once, from inside the player's frame (the press gave it the
  // right to), so the page there sees a real full screen before its ad gets a chance
  function fsNow() {
    var v = document.querySelector('video');
    if (!v) return false;
    var el = v.closest('.jwplayer,.video-js,.plyr,.art-video-player,.dplayer,.flowplayer,.mejs__container,[id*="player" i],[class*="player" i]') || document.documentElement;
    var f = el.requestFullscreen || el.webkitRequestFullscreen;
    if (!f) return false;
    try { var r = f.call(el); if (r && r.catch) r.catch(function () { }); } catch (e) { return false; }
    if (A.fsPressed) A.fsPressed();
    return true;
  }
  if (window === window.top) window.__sajoFsNow = function () {
    for (var i = 0; i < frames.length; i++) { try { frames[i].postMessage({ sajoFsNow: 1 }, '*'); } catch (e) { } }
    return fsNow();
  };
  window.addEventListener('message', function (ev) {
    var d = ev.data;
    if (!d || d.sajoFsNow !== 1 || window === window.top) return;
    for (var i = 0; i < frames.length; i++) { try { frames[i].postMessage(d, '*'); } catch (e) { } }
    fsNow();
  });
  if (window === window.top) window.__sajoFsBtn = function () {
    var ok = pressFsButton();
    for (var i = 0; i < frames.length; i++) { try { frames[i].postMessage({ sajoFsBtn: 1 }, '*'); } catch (e) { } }
    return ok;
  };
  window.addEventListener('message', function (ev) {
    var d = ev.data;
    if (!d || d.sajoFsBtn !== 1 || window === window.top) return;
    for (var i = 0; i < frames.length; i++) { try { frames[i].postMessage(d, '*'); } catch (e) { } }
    pressFsButton();
  });

  // ------------------------------------------------------------------ full screen: the remote jumps between the player's buttons
  // every frame reports its visible buttons in screen coordinates (its frame's offset is passed down)
  var CTL = 'button,[role=button],[role=slider],[role=menuitem],a[href],input,select,[tabindex]:not([tabindex="-1"]),[aria-label],[onclick],' +
            '.jw-icon,.jw-button-container>*,.vjs-control,.plyr__control,[class*="btn"],[class*="button"],[class*="control"]>*';
  function boxesHere(ox, oy) {
    var vw = innerWidth, vh = innerHeight, out = [], seen = [];
    var l = document.querySelectorAll(CTL);
    for (var i = 0; i < l.length && out.length < 120; i++) {
      var e = l[i], r = e.getBoundingClientRect();
      if (r.width < 10 || r.height < 10 || r.width > vw * 0.9 || r.height > vh * 0.5) continue;
      if (r.right < 0 || r.bottom < 0 || r.left > vw || r.top > vh) continue;
      var st = getComputedStyle(e);
      if (st.visibility === 'hidden' || st.display === 'none') continue;   // faded-out controls still count (they come back on hover)
      var cx = r.left + r.width / 2, cy = r.top + r.height / 2;
      var hit = document.elementFromPoint(Math.min(vw - 1, Math.max(0, cx)), Math.min(vh - 1, Math.max(0, cy)));
      var fse = document.fullscreenElement || document.webkitFullscreenElement;
      if (!(fse && fse.contains(e)) && (!hit || (hit !== e && !e.contains(hit) && !hit.contains(e)))) continue;     // covered by something else
      var dup = false;
      for (var k = 0; k < seen.length; k++) if (Math.abs(seen[k][0] - cx) < 8 && Math.abs(seen[k][1] - cy) < 8) { dup = true; break; }
      if (dup) continue;
      seen.push([cx, cy]);
      out.push([Math.round(ox + r.left), Math.round(oy + r.top), Math.round(r.width), Math.round(r.height)]);
    }
    var fr = document.getElementsByTagName('iframe');
    for (var j = 0; j < fr.length; j++) {
      var q = fr[j].getBoundingClientRect();
      if (q.width < 50 || q.height < 50 || q.right < 0 || q.bottom < 0 || q.left > vw || q.top > vh) continue;
      try { fr[j].contentWindow.postMessage({ sajoBoxes: 1, ox: ox + q.left, oy: oy + q.top }, '*'); } catch (z) { }
    }
    if (A.boxes) A.boxes(JSON.stringify(out));
  }
  if (window === window.top) window.__sajoBoxes = function () { boxesHere(0, 0); };
  window.addEventListener('message', function (ev) {
    var d = ev.data;
    if (d && d.sajoBoxes === 1 && window !== window.top) boxesHere(d.ox || 0, d.oy || 0);
  });

  // remote control of the video while it is full screen: seek / play-pause, in whatever frame the video is
  function media(cmd, d) {
    var vs = document.querySelectorAll('video'), v = null, best = 0;
    for (var i = 0; i < vs.length; i++) {
      var r = vs[i].getBoundingClientRect(), a = r.width * r.height + (vs[i].paused ? 0 : 1e9);
      if (vs[i].readyState > 0 && a > best) { best = a; v = vs[i]; }
    }
    if (!v) return false;
    if (cmd === 'seek') { try { v.currentTime = Math.max(0, Math.min((v.duration || 1e9) - 1, v.currentTime + d)); } catch (e) { } }
    else if (cmd === 'info') { }
    else if (cmd === 'toggle') { if (v.paused) { var p = v.play(); if (p && p.catch) p.catch(function () { }); } else v.pause(); }
    if (A.mediaInfo) A.mediaInfo(cmd, v.currentTime || 0, isFinite(v.duration) ? v.duration : 0, v.paused);
    return true;
  }
  window.__sajoMedia = function (cmd, d) {
    var done = media(cmd, d);
    for (var i = 0; i < frames.length; i++) { try { frames[i].postMessage({ sajoMedia: cmd, d: d }, '*'); } catch (e) { } }
    return done;
  };
  window.addEventListener('message', function (ev) {
    var m = ev.data;
    if (!m || !m.sajoMedia || window === window.top) return;
    for (var i = 0; i < frames.length; i++) { try { frames[i].postMessage(m, '*'); } catch (e) { } }
    media(m.sajoMedia, m.d);
  });

  // Menu «شيل اللي تحت الماوس»: removes what is under the pointer (going into the frame it is in),
  // never a video or anything holding one
  function zapAt(x, y) {
    var el = document.elementFromPoint(x, y);
    if (!el) return 'none';
    if (el.tagName === 'IFRAME') {
      var r = el.getBoundingClientRect();
      try { el.contentWindow.postMessage({ sajoZap: 1, x: x - r.left, y: y - r.top }, '*'); } catch (e) { }
      return 'frame';
    }
    if (el.tagName === 'VIDEO') return 'video';
    var vw = innerWidth, vh = innerHeight, best = null;
    for (var n = el; n && n !== document.body && n !== document.documentElement; n = n.parentElement) {
      if (n.tagName === 'VIDEO' || n.querySelector('video')) break;
      var fr = n.querySelectorAll('iframe'), pl = false;
      for (var i = 0; i < fr.length; i++) if (fr[i].hasAttribute('allowfullscreen') || /fullscreen/i.test(fr[i].getAttribute('allow') || '')) pl = true;
      if (pl) break;
      var st = getComputedStyle(n);
      // the outermost thing laid over the page (the ad's box); plain page blocks are left alone
      if (st.position === 'fixed' || st.position === 'absolute' || st.position === 'sticky' || (parseInt(st.zIndex, 10) || 0) > 0) best = n;
    }
    if (!best && el !== document.body && el.tagName !== 'HTML') best = el;
    if (!best) return 'video';
    best.remove();
    return 'ok';
  }
  if (window === window.top) window.__sajoZap = zapAt;

  // Menu «🔬 افحص اللي تحت الماوس»: what is stacked under the pointer in every frame (to fix a stubborn ad)
  function probe(x, y, depth) {
    var st = document.elementsFromPoint ? document.elementsFromPoint(x, y) : [document.elementFromPoint(x, y)];
    var out = [];
    for (var i = 0; i < st.length && out.length < 7; i++) {
      var e = st[i]; if (!e || e === document.documentElement) continue;
      var c = getComputedStyle(e), r = e.getBoundingClientRect();
      var cl = (typeof e.className === 'string' ? e.className : '').trim().split(/\s+/).slice(0, 2).join('.');
      out.push(e.tagName.toLowerCase() + (e.id ? '#' + e.id : '') + (cl ? '.' + cl : '') + ' [' + c.position + ' z' + c.zIndex + ' ' + Math.round(r.width) + 'x' + Math.round(r.height) +
        (e.tagName === 'IFRAME' ? ' ' + (e.src || '').replace(/^https?:\/\//, '').slice(0, 40) : '') + ']');
      if (e.tagName === 'IFRAME' && i === 0) {
        try { e.contentWindow.postMessage({ sajoProbe: 1, x: x - r.left, y: y - r.top, d: depth + 1 }, '*'); } catch (z) { }
      }
    }
    if (A.probe) A.probe(depth + ') ' + location.hostname + ' ' + innerWidth + 'x' + innerHeight + ': ' + out.join(' ← '));
  }
  if (window === window.top) window.__sajoProbe = function (x, y) { probe(x, y, 0); };
  window.addEventListener('message', function (ev) {
    var d = ev.data;
    if (d && d.sajoProbe === 1 && window !== window.top) probe(d.x, d.y, d.d || 1);
  });
  window.addEventListener('message', function (ev) {
    var d = ev.data;
    if (!d || d.sajoZap !== 1 || window === window.top) return;
    var r = zapAt(d.x, d.y);
    if (A.zapped && r !== 'frame') A.zapped(r);
  });
  window.addEventListener('message', function (ev) {
    var d = ev.data;
    if (!d || d.sajoCloseAd !== 1 || window === window.top) return;
    for (var i = 0; i < frames.length; i++) { try { frames[i].postMessage({ sajoCloseAd: 1 }, '*'); } catch (e) { } }
    if (closeAd(false) && A.adClosed) A.adClosed();
  });
  var cfg;
  try { cfg = JSON.parse(A.sl(location.hostname) || 'null'); } catch (e) { return; }
  if (!cfg) return;                       // blocking off for this site / launcher

  // ads that come back every minute over the video (closing with ✕ doesn't stick): closed again as soon as they show
  setInterval(function () {
    try {
      if (!document.querySelector('video')) return;     // only where a video plays (keeps phones light)
      if (document.hidden) return;
      closeAd(window === window.top, true);
    } catch (e) { }
  }, 1500);

  var MAGIC = 'sajo' + Math.random().toString(36).slice(2);


  var noop = function () {};
  var fnToStr = Function.prototype.toString;

  function rx(s) {
    if (s === undefined || s === null || s === '' || s === '*') return /^/;
    var not = false;
    if (s.charAt(0) === '!') { not = true; s = s.slice(1); }
    var r;
    var m = /^\/(.+)\/([gimsu]*)$/.exec(s);
    if (m) { try { r = new RegExp(m[1], m[2].replace('g', '')); } catch (e) { r = /(?!)/; } }
    else r = new RegExp(s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'));
    if (!not) return r;
    return { test: function (t) { return !r.test(t); } };
  }
  function str(f) { try { return typeof f === 'function' ? fnToStr.call(f) : String(f); } catch (e) { return ''; } }

  // swallow the errors our aborts throw
  window.addEventListener('error', function (ev) {
    if (ev && ev.message && String(ev.message).indexOf(MAGIC) >= 0) { ev.preventDefault(); ev.stopImmediatePropagation(); }
  }, true);

  // ------------------------------------------------------------------ values
  var SKIP = {};
  function cval(v) {
    switch (v) {
      case 'undefined': return undefined;
      case 'false': return false;
      case 'true': return true;
      case 'null': return null;
      case "''": case '""': case 'emptyStr': return '';
      case 'noopFunc': return function () {};
      case 'trueFunc': return function () { return true; };
      case 'falseFunc': return function () { return false; };
      case 'throwFunc': return function () { throw new Error(MAGIC); };
      case 'emptyArr': case '[]': return [];
      case 'emptyObj': case '{}': return {};
      case 'noopPromiseResolve': return function () { return Promise.resolve(); };
      case 'noopPromiseReject': return function () { return Promise.reject(); };
      case 'yes': return 'yes';
      case 'no': return 'no';
      case '-1': return -1;
    }
    if (/^-?\d+(\.\d+)?$/.test(v) && Math.abs(parseFloat(v)) <= 0x7FFF) return parseFloat(v);
    return SKIP;
  }

  // define the last link of a.b.c, waiting for the missing links to appear
  function onChain(chain, finalize) {
    var parts = chain.split('.');
    (function walk(owner, i) {
      var p = parts[i];
      if (i === parts.length - 1) { finalize(owner, p); return; }
      var cur;
      try { cur = owner[p]; } catch (e) { return; }
      if (cur && (typeof cur === 'object' || typeof cur === 'function')) { walk(cur, i + 1); return; }
      var val = cur;
      try {
        Object.defineProperty(owner, p, {
          configurable: true,
          get: function () { return val; },
          set: function (n) { val = n; if (n && (typeof n === 'object' || typeof n === 'function')) walk(n, i + 1); }
        });
      } catch (e) { }
    })(window, 0);
  }

  // ------------------------------------------------------------------ scriptlets
  var S = {};
  S['set-constant'] = function (chain, v) {
    var val = cval(v);
    if (val === SKIP || !chain) return;
    onChain(chain, function (o, p) {
      try { Object.defineProperty(o, p, { configurable: false, get: function () { return val; }, set: noop }); } catch (e) { }
    });
  };
  S['abort-on-property-read'] = function (chain) {
    if (!chain) return;
    onChain(chain, function (o, p) {
      try { Object.defineProperty(o, p, { configurable: false, get: function () { throw new ReferenceError(MAGIC); }, set: noop }); } catch (e) { }
    });
  };
  S['abort-on-property-write'] = function (prop) {
    if (!prop) return;
    onChain(prop, function (o, p) {
      var v = o[p];
      try { Object.defineProperty(o, p, { configurable: false, get: function () { return v; }, set: function () { throw new ReferenceError(MAGIC); } }); } catch (e) { }
    });
  };
  S['abort-current-script'] = function (chain, needle, ctx) {
    if (!chain) return;
    var re = rx(needle), cre = ctx ? rx(ctx) : null;
    var parts = chain.split('.'), owner = window;
    while (parts.length > 1) {
      var k = parts.shift();
      try { owner = owner[k]; } catch (e) { return; }
      if (!owner || (typeof owner !== 'object' && typeof owner !== 'function')) return;
    }
    var prop = parts[0];
    var d = Object.getOwnPropertyDescriptor(owner, prop);
    if (d && d.configurable === false) return;
    var val = owner[prop];
    function check() {
      var s = document.currentScript;
      if (!s) return;
      var t = s.src ? s.src : s.textContent;
      if (!re.test(s.textContent || '') && !(s.src && re.test(s.src))) return;
      if (cre && !cre.test(t)) return;
      throw new ReferenceError(MAGIC);
    }
    try {
      Object.defineProperty(owner, prop, {
        configurable: true,
        get: function () { check(); return d && d.get ? d.get.call(this) : val; },
        set: function (n) { check(); if (d && d.set) d.set.call(this, n); else val = n; }
      });
    } catch (e) { }
  };
  function timerFilter(name, needle, delay) {
    var re = rx(needle), dNot = false, dVal = null;
    if (delay) { if (delay.charAt(0) === '!') { dNot = true; delay = delay.slice(1); } dVal = parseInt(delay, 10); if (isNaN(dVal)) dVal = null; }
    var orig = window[name];
    window[name] = new Proxy(orig, {
      apply: function (t, self, a) {
        var hit = re.test(str(a[0]));
        if (hit && dVal !== null) { var eq = (a[1] | 0) === dVal; hit = dNot ? !eq : eq; }
        if (hit) { a = Array.prototype.slice.call(a); a[0] = noop; }
        return Reflect.apply(t, self, a);
      }
    });
  }
  S['no-setTimeout-if'] = function (n, d) { timerFilter('setTimeout', n, d); };
  S['no-setInterval-if'] = function (n, d) { timerFilter('setInterval', n, d); };
  function booster(name, needle, delay, boost) {
    var re = rx(needle), dv = delay === '*' ? null : parseInt(delay || '1000', 10);
    var b = parseFloat(boost || '0.05'); if (!(b >= 0.001 && b <= 50)) b = 0.05;
    var orig = window[name];
    window[name] = new Proxy(orig, {
      apply: function (t, self, a) {
        if (re.test(str(a[0])) && (dv === null || (a[1] | 0) === dv)) { a = Array.prototype.slice.call(a); a[1] = (a[1] | 0) * b; }
        return Reflect.apply(t, self, a);
      }
    });
  }
  S['nano-setInterval-booster'] = function (n, d, b) { booster('setInterval', n, d, b); };
  S['nano-setTimeout-booster'] = function (n, d, b) { booster('setTimeout', n, d, b); };
  S['addEventListener-defuser'] = function (type, needle) {
    var tre = rx(type), re = rx(needle);
    var orig = EventTarget.prototype.addEventListener;
    EventTarget.prototype.addEventListener = new Proxy(orig, {
      apply: function (t, self, a) {
        if (tre.test(String(a[0])) && re.test(str(a[1]))) return;
        return Reflect.apply(t, self, a);
      }
    });
  };
  function fakeWindow() {
    var w = { closed: false, opener: window, close: function () { w.closed = true; }, focus: noop, blur: noop, postMessage: noop,
              location: { href: '', assign: noop, replace: noop }, document: document.implementation.createHTMLDocument('') };
    return w;
  }
  S['window.open-defuser'] = function (needle) {
    var not = false;
    if (needle === '0') { return; }               // uBO: "0" = disabled form
    if (needle && needle.charAt(0) === '!') { not = true; needle = needle.slice(1); }
    if (needle === '1') needle = '';
    var re = rx(needle);
    var orig = window.open;
    window.open = new Proxy(orig, {
      apply: function (t, self, a) {
        var hit = re.test(String(a[0] || ''));
        if (not) hit = !hit;
        return hit ? fakeWindow() : Reflect.apply(t, self, a);
      }
    });
  };
  function onNodes(fn) {
    var run = function (root) { try { fn(root); } catch (e) { } };
    var mo = new MutationObserver(function (ms) {
      for (var i = 0; i < ms.length; i++) for (var j = 0; j < ms[i].addedNodes.length; j++) run(ms[i].addedNodes[j]);
    });
    mo.observe(document, { childList: true, subtree: true });
    if (document.documentElement) run(document.documentElement);
    document.addEventListener('DOMContentLoaded', function () { run(document.documentElement); });
  }
  S['remove-node-text'] = function (name, needle) {
    if (!name) return;
    var nn = name.toUpperCase(), re = rx(needle);
    function chk(n) {
      if (n.nodeName === nn && re.test(n.textContent || '')) { if (n.parentNode) n.parentNode.removeChild(n); return; }
    }
    onNodes(function (root) {
      chk(root);
      if (root.querySelectorAll && nn !== '#TEXT') { var l = root.querySelectorAll(name); for (var i = 0; i < l.length; i++) chk(l[i]); }
    });
  };
  S['remove-attr'] = function (attrs, sel) {
    if (!attrs) return;
    var list = attrs.split(/\s*\|\s*/);
    var q = sel || list.map(function (a) { return '[' + a + ']'; }).join(',');
    onNodes(function () { var l = document.querySelectorAll(q); for (var i = 0; i < l.length; i++) for (var j = 0; j < list.length; j++) l[i].removeAttribute(list[j]); });
  };
  S['remove-class'] = function (cls, sel) {
    if (!cls) return;
    var list = cls.split(/\s*\|\s*/);
    var q = sel || list.map(function (c) { return '.' + c; }).join(',');
    onNodes(function () { var l = document.querySelectorAll(q); for (var i = 0; i < l.length; i++) for (var j = 0; j < list.length; j++) l[i].classList.remove(list[j]); });
  };
  function reqMatch(needle, url, init) {
    if (!needle || needle === '*') return true;
    var pairs = needle.split(/\s+/), ok = true;
    for (var i = 0; i < pairs.length; i++) {
      var p = pairs[i], c = p.indexOf(':'), key = 'url', val = p;
      if (c > 0 && /^[a-z]+$/.test(p.slice(0, c))) { key = p.slice(0, c); val = p.slice(c + 1); }
      var v = key === 'url' ? url : key === 'method' ? ((init && init.method) || 'GET') : (init && init[key]) || '';
      if (!rx(val).test(String(v))) ok = false;
    }
    return ok;
  }
  S['no-fetch-if'] = function (needle, body) {
    if (!window.fetch) return;
    var orig = window.fetch;
    window.fetch = new Proxy(orig, {
      apply: function (t, self, a) {
        var url = a[0] && a[0].url ? a[0].url : String(a[0]);
        if (!reqMatch(needle, url, a[1])) return Reflect.apply(t, self, a);
        var r = new Response(body === 'emptyObj' ? '{}' : body === 'emptyArr' ? '[]' : '', { status: 200, statusText: 'OK' });
        try { Object.defineProperty(r, 'url', { value: url }); } catch (e) { }
        return Promise.resolve(r);
      }
    });
  };
  S['no-xhr-if'] = function (needle) {
    var X = XMLHttpRequest.prototype, open = X.open, send = X.send;
    X.open = function (m, u) { this.__sajoNo = reqMatch(needle, String(u), { method: m }); return open.apply(this, arguments); };
    X.send = function () {
      if (!this.__sajoNo) return send.apply(this, arguments);
      var x = this;
      try {
        Object.defineProperty(x, 'readyState', { value: 4 });
        Object.defineProperty(x, 'status', { value: 200 });
        Object.defineProperty(x, 'statusText', { value: 'OK' });
        Object.defineProperty(x, 'responseText', { value: '' });
        Object.defineProperty(x, 'response', { value: x.responseType === 'json' ? {} : '' });
      } catch (e) { }
      setTimeout(function () {
        ['readystatechange', 'load', 'loadend'].forEach(function (t) { try { x.dispatchEvent(new Event(t)); } catch (e) { } });
      }, 1);
    };
  };
  function prune(o, path) {
    var parts = path.split('.');
    (function go(obj, i) {
      if (!obj || typeof obj !== 'object') return;
      var k = parts[i];
      var keys = (k === '[]' || k === '*') ? Object.keys(obj) : [k];
      for (var j = 0; j < keys.length; j++) {
        if (i === parts.length - 1) { if (Object.prototype.hasOwnProperty.call(obj, keys[j])) delete obj[keys[j]]; }
        else go(obj[keys[j]], i + 1);
      }
    })(o, 0);
  }
  function has(o, path) {
    var parts = path.split('.'), cur = o;
    for (var i = 0; i < parts.length; i++) { if (!cur || typeof cur !== 'object' || !(parts[i] in cur)) return false; cur = cur[parts[i]]; }
    return true;
  }
  S['json-prune'] = function (paths, need) {
    if (!paths) return;
    var P = paths.split(/\s+/), N = need ? need.split(/\s+/) : [];
    JSON.parse = new Proxy(JSON.parse, {
      apply: function (t, self, a) {
        var r = Reflect.apply(t, self, a);
        try { if (N.every(function (n) { return has(r, n); })) P.forEach(function (p) { prune(r, p); }); } catch (e) { }
        return r;
      }
    });
  };
  S['nowebrtc'] = function () {
    var F = function () { return { close: noop, createDataChannel: function () { return { close: noop }; }, createOffer: function () { return Promise.resolve(); },
      setLocalDescription: function () { return Promise.resolve(); }, addEventListener: noop, removeEventListener: noop }; };
    ['RTCPeerConnection', 'webkitRTCPeerConnection'].forEach(function (k) { if (window[k]) window[k] = F; });
  };
  S['set-cookie'] = function (name, value, path) {
    if (!name) return;
    var v = { 'true': 'true', 'false': 'false', 'yes': 'yes', 'no': 'no', 'ok': 'ok', 'accept': 'accept', 'reject': 'reject',
              'emptyStr': '', '0': '0', '1': '1', 'on': 'on', 'off': 'off', 'y': 'y', 'n': 'n' };
    var val = v.hasOwnProperty(value) ? v[value] : (/^\d+$/.test(value || '') ? value : null);
    if (val === null) return;
    try { document.cookie = encodeURIComponent(name) + '=' + val + '; path=' + (path === 'none' ? location.pathname : '/'); } catch (e) { }
  };
  function storage(kind, key, value) {
    if (!key) return;
    try {
      var st = window[kind];
      if (value === '$remove$') { st.removeItem(key); return; }
      var map = { 'undefined': 'undefined', 'false': 'false', 'true': 'true', 'null': 'null', 'emptyArr': '[]', 'emptyObj': '{}',
                  'emptyStr': '', '': '', 'yes': 'yes', 'no': 'no', 'on': 'on', 'off': 'off' };
      var v = map.hasOwnProperty(value) ? map[value] : (/^\d+$/.test(value) ? value : null);
      if (v !== null) st.setItem(key, v);
    } catch (e) { }
  }
  S['set-local-storage-item'] = function (k, v) { storage('localStorage', k, v); };
  S['set-session-storage-item'] = function (k, v) { storage('sessionStorage', k, v); };
  S['noeval-if'] = function (needle) {
    var re = rx(needle);
    window.eval = new Proxy(window.eval, { apply: function (t, self, a) { if (re.test(String(a[0]))) return; return Reflect.apply(t, self, a); } });
  };
  S['noeval'] = function () { S['noeval-if'](''); };
  S['popads-dummy'] = function () {
    try { Object.defineProperty(window, 'PopAds', { value: {} }); Object.defineProperty(window, 'popns', { value: {} }); } catch (e) { }
  };
  function fakeDetector(Ctor) {
    var F = function () { };
    F.prototype = {
      setOption: function () { return this; }, check: function () { return false; }, emitEvent: function () { return this; },
      clearEvent: noop, on: function (det, fn) { if (!det && typeof fn === 'function') setTimeout(fn, 1); return this; },
      onDetected: function () { return this; }, onNotDetected: function (fn) { if (typeof fn === 'function') setTimeout(fn, 1); return this; }
    };
    return F;
  }
  S['nofab'] = function () {
    var F = fakeDetector();
    var put = function (k, v) {
      try { if (!(k in window)) Object.defineProperty(window, k, { configurable: true, get: function () { return v; }, set: noop }); } catch (e) { }
    };
    put('FuckAdBlock', F); put('BlockAdBlock', F); put('SniffAdBlock', F);
    put('fuckAdBlock', new F()); put('blockAdBlock', new F()); put('sniffAdBlock', new F());
  };
  S['nobab'] = S['nofab'];

  var ALIAS = {
    'set': 'set-constant', 'aopr': 'abort-on-property-read', 'aopw': 'abort-on-property-write', 'acs': 'abort-current-script',
    'acis': 'abort-current-script', 'abort-current-inline-script': 'abort-current-script', 'nostif': 'no-setTimeout-if',
    'setTimeout-defuser': 'no-setTimeout-if', 'nosiif': 'no-setInterval-if', 'setInterval-defuser': 'no-setInterval-if',
    'nano-sib': 'nano-setInterval-booster', 'nano-stb': 'nano-setTimeout-booster', 'aeld': 'addEventListener-defuser',
    'prevent-addEventListener': 'addEventListener-defuser', 'nowoif': 'window.open-defuser', 'no-window-open-if': 'window.open-defuser',
    'prevent-window-open': 'window.open-defuser', 'rmnt': 'remove-node-text', 'ra': 'remove-attr', 'rc': 'remove-class',
    'prevent-fetch': 'no-fetch-if', 'prevent-xhr': 'no-xhr-if', 'nowebrtc': 'nowebrtc', 'noeval-if': 'noeval-if',
    'prevent-eval-if': 'noeval-if', 'silent-noeval': 'noeval', 'nobab2': 'nobab', 'fuckadblock.js-3.2.0': 'nofab',
    'popads.net': 'popads-dummy', 'set-local-storage-item': 'set-local-storage-item'
  };

  // generic stealth: the usual detector libraries always report "no adblock"
  if (cfg.stealth) { try { S['nofab'](); } catch (e) { } }

  var list = cfg.js || [];
  for (var i = 0; i < list.length; i++) {
    var a = list[i];
    var name = String(a[0] || '').replace(/\.js$/, '');
    var fn = S[ALIAS[name] || name];
    if (fn) { try { fn.apply(null, a.slice(1)); } catch (e) { } }
  }
})();
