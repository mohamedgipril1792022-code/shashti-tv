// Sajo TV — YouTube only: ads out of the player data, ad boxes hidden, a playing ad skipped, keeps playing in background.
(function () {
  'use strict';
  // ------------------------------------------------------------------ YouTube (only on youtube.com — nothing else changes)
  // Like Brave/uBlock: the ads are taken out of the player's data before the player reads it, the ad boxes are hidden,
  // and if an ad still starts it is skipped at once.
  if (!window.__sajoYT && /(^|\.)youtube(-nocookie)?\.com$/.test(location.hostname)) (function () {
    window.__sajoYT = true;   // runs at the very start, without waiting for the app's bridge (YouTube sets its data at once)
    // with the phone locked YouTube pauses the video ("the page is hidden"): it is always told the page is visible
    try {
      Object.defineProperty(Document.prototype, 'hidden', { configurable: true, get: function () { return false; } });
      Object.defineProperty(Document.prototype, 'visibilityState', { configurable: true, get: function () { return 'visible'; } });
      Object.defineProperty(Document.prototype, 'webkitHidden', { configurable: true, get: function () { return false; } });
      Object.defineProperty(Document.prototype, 'webkitVisibilityState', { configurable: true, get: function () { return 'visible'; } });
      var ae = EventTarget.prototype.addEventListener;
      EventTarget.prototype.addEventListener = function (t) { if (t === 'visibilitychange' || t === 'webkitvisibilitychange') return; return ae.apply(this, arguments); };
      ['visibilitychange', 'webkitvisibilitychange'].forEach(function (t) { ae.call(window, t, function (e) { e.stopImmediatePropagation(); }, true); ae.call(document, t, function (e) { e.stopImmediatePropagation(); }, true); });
    } catch (e) { }
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
    JSON.parse = function (txt) { var r = jp.apply(this, arguments); try { if (r && typeof r === 'object' && typeof txt === 'string' && txt.length > 2000 && (r.adPlacements || r.playerAds || r.adSlots || r.playerResponse)) strip(r, 0); } catch (e) { } return r; };
    // 3) the player API answers (fetch / XHR to /youtubei/v1/player)
    if (window.fetch) {
      var of = window.fetch;
      window.fetch = function (input, init) {
        var url = typeof input === 'string' ? input : (input && input.url) || '';
        var p = of.apply(this, arguments);
        if (!/\/youtubei\/v1\/player/.test(url)) return p;
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
    XMLHttpRequest.prototype.open = function (m, u) { this.__sajoYt = /\/youtubei\/v1\/player/.test(String(u)); return xo.apply(this, arguments); };
    ['responseText', 'response'].forEach(function (k) {
      var d = Object.getOwnPropertyDescriptor(XMLHttpRequest.prototype, k);
      if (!d || !d.get) return;
      Object.defineProperty(XMLHttpRequest.prototype, k, {
        configurable: true,
        get: function () {
          var v = d.get.call(this);
          if (!this.__sajoYt || this.readyState !== 4) return v;
          if (this.__sajoOut !== undefined && this.__sajoIn === v) return this.__sajoOut;   // cleaned once, not at every read
          var out = v;
          try {
            if (typeof v === 'string' && /adPlacements|playerAds|adSlots/.test(v)) out = JSON.stringify(strip(jp(v), 0));
            else if (v && typeof v === 'object') out = strip(v, 0);
          } catch (e) { }
          this.__sajoIn = v; this.__sajoOut = out;
          return out;
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
})();
