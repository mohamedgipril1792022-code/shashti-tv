(function(){
  if (window.__shashtiClean) return; window.__shashtiClean = 1;
  try { // allowed sites: do nothing there
    var top = (location.ancestorOrigins && location.ancestorOrigins.length) ? location.ancestorOrigins[location.ancestorOrigins.length-1] : location.origin;
    var th = top.replace(/^https?:\/\//,'').split('/')[0].split('.').slice(-2).join('.');
    if ((window.__SHASHTI_ALLOW || []).indexOf(th) >= 0) return;
  } catch(e) {}
  // pop-ups and pop-unders
  try { // pop-ups without a click are dropped; a real click still opens its window
    var _open = window.open;
    window.open = function(){ var ua = navigator.userActivation; if (ua && !ua.isActive) return null; return _open.apply(window, arguments); };
  } catch(e) {}
  function big(r){ return r.width*r.height > innerWidth*innerHeight*0.35; }
  function sweep(){
    var all = document.body ? document.body.querySelectorAll('div,a,span,ins,section') : [];
    for (var i = all.length - 1; i >= 0; i--) {
      var el = all[i];
      if (!el.isConnected) continue;
      var st = getComputedStyle(el);
      if (st.position !== 'fixed' && st.position !== 'absolute') continue;
      if ((parseInt(st.zIndex) || 0) < 100) continue;
      var r = el.getBoundingClientRect();
      if (!big(r) || el.querySelector('video,iframe,img,canvas,svg,button,input,select,textarea')) continue;
      // only invisible layers that catch the first click
      if (parseFloat(st.opacity) < 0.15 || (!el.innerText.trim() && (st.backgroundColor === 'rgba(0, 0, 0, 0)' || st.backgroundColor === 'transparent'))) el.remove();
    }
  }
  function start(){ sweep(); try { new MutationObserver(function(){ clearTimeout(window.__shT); window.__shT=setTimeout(sweep,300); })
      .observe(document.documentElement,{childList:true,subtree:true}); } catch(e) {} }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
})();
