(function(){
  if (window.__shashtiClean) return; window.__shashtiClean = 1;
  // popups and pop-unders
  try { window.open = function(){ return null; }; } catch(e) {}
  var AD = /(^|[\s_-])(ads?|advert\w*|banner|sponsor\w*|popup|popunder|overlay-?ad)([\s_-]|$)/i;
  function big(r){ var vw=innerWidth, vh=innerHeight; return r.width*r.height > vw*vh*0.35; }
  function hasMedia(el){ return el.tagName==='VIDEO' || !!el.querySelector('video'); }
  function sweep(){
    var all = document.body ? document.body.getElementsByTagName('*') : [];
    for (var i = all.length - 1; i >= 0; i--) {
      var el = all[i];
      if (!el.isConnected || hasMedia(el)) continue;
      var st = getComputedStyle(el), r = el.getBoundingClientRect();
      var pos = st.position, z = parseInt(st.zIndex) || 0;
      var cls = (typeof el.className === 'string' ? el.className : '') + ' ' + (el.id || '');
      // invisible full-screen click catchers on top of the page/player
      if ((pos==='fixed' || pos==='absolute') && z >= 100 && big(r) &&
          (parseFloat(st.opacity) < 0.15 || (el.tagName==='A' && !el.innerText.trim()) ||
           (!el.innerText.trim() && !el.querySelector('img,iframe,canvas,svg,button,input')))) { el.remove(); continue; }
      // boxes named like ads
      if (AD.test(cls) && !el.querySelector('iframe[allowfullscreen],video')) {
        if (r.height < innerHeight * 0.9) { el.style.setProperty('display','none','important'); }
      }
    }
  }
  function start(){ sweep(); try { new MutationObserver(function(){ clearTimeout(window.__shT); window.__shT=setTimeout(sweep,250); })
      .observe(document.documentElement,{childList:true,subtree:true}); } catch(e) {} }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
  // links that jump to another site on the first click
  document.addEventListener('click', function(e){
    var a = e.target.closest && e.target.closest('a[href]');
    if (a && a.target === '_blank' && a.hostname && a.hostname !== location.hostname) { e.preventDefault(); e.stopPropagation(); }
  }, true);
})();
