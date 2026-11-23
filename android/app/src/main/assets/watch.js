// Sajo TV — continue watching: how far each video got (saved by the app per page) and resuming there.
// Runs on every page, on the box and the phone; it only listens to the video, it changes nothing on the page.
(function watch() {
  'use strict';
  if (window.__sajoWatch) return;
  if (!(window.Android && window.Android.progress)) {
    watch.n = (watch.n || 0) + 1;
    if (watch.n < 200) setTimeout(watch, 50);
    return;
  }
  window.__sajoWatch = true;
  var A = window.Android;
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
      // one <video> can carry many videos one after another (YouTube): resume once per video, not per element
      if (!v || v.tagName !== 'VIDEO' || v.__sajoSrc === (v.currentSrc || v.src)) return;
      v.__sajoSrc = v.currentSrc || v.src;
      if (!(v.duration > 60)) return;
      var t = 0;
      try { t = A.resumeAt ? +A.resumeAt() : 0; } catch (e) { }
      if (t > 30 && v.currentTime < 5 && t < v.duration * 0.97) { try { v.currentTime = t - 3; } catch (e) { } }
    }, true);
  })();
})();
