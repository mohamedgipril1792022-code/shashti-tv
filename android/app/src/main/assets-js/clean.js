(function(){
  if (window.__shashtiClean) return; window.__shashtiClean = 1;
  // Back on the remote: close a box inside this frame (e.g. a box drawn inside the player)
  function closeHere(){
    if (!document.body) return false;
    var vw=innerWidth, vh=innerHeight, c=[];
    var all=document.body.querySelectorAll('*');
    for (var i=0;i<all.length;i++){
      var e=all[i], st=getComputedStyle(e);
      if (st.position!=='fixed' && st.position!=='absolute') continue;
      if (st.display==='none' || st.visibility==='hidden' || parseFloat(st.opacity)<0.3) continue;
      if (e.tagName==='VIDEO' || e.querySelector('video')) continue;
      var r=e.getBoundingClientRect(); if (r.width*r.height < vw*vh*0.3) continue;
      c.push(e);
    }
    if (!c.length) {
      // the box is the frame's own page (not floating): press its ✕ if the page looks like a lock screen
      var txt=(document.body.innerText||'').slice(0,3000);
      if (!/robot|روبوت|vpn|verify|تحقق|ad.?block|السماح/i.test(txt) || document.querySelector('video')) return false;
      var x=[].slice.call(document.querySelectorAll('button,a,span,div,i,svg,[role=button]')).filter(function(b){
        var t=(b.innerText||b.getAttribute('aria-label')||b.getAttribute('title')||'').trim().toLowerCase();
        var cl=((b.className&&b.className.baseVal!==undefined)?b.className.baseVal:(b.className||''))+' '+(b.id||'');
        var r=b.getBoundingClientRect();
        return r.width>0 && r.width<90 && r.height<90 && (/^(×|✕|✖|x|close|إغلاق|اغلاق|skip|تخطي)$/i.test(t) || /close|dismiss|btn-x/i.test(cl));
      })[0];
      if (x) { x.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window})); return true; }
      // no ✕ at all: ask the page around this frame to remove the frame itself
      try { parent.postMessage({shashti:'removeMe', host: location.hostname}, '*'); return true; } catch(e) {}
      return false;
    }
    c.sort(function(a,b){return (parseInt(getComputedStyle(b).zIndex)||0)-(parseInt(getComputedStyle(a).zIndex)||0)});
    var o=c[0];
    var btn=[].slice.call(o.querySelectorAll('button,a,span,div,i,svg,[role=button]')).filter(function(b){
      var t=(b.innerText||b.getAttribute('aria-label')||b.getAttribute('title')||'').trim().toLowerCase();
      var cl=((b.className&&b.className.baseVal!==undefined)?b.className.baseVal:(b.className||''))+' '+(b.id||'');
      var r=b.getBoundingClientRect();
      return r.width>0 && r.width<90 && r.height<90 && (/^(×|✕|✖|x|close|إغلاق|اغلاق|skip|تخطي)$/i.test(t) || /close|dismiss|btn-x/i.test(cl));
    })[0];
    if (btn) { btn.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window})); setTimeout(function(){ if (o.isConnected && getComputedStyle(o).display!=='none') o.remove(); }, 400); }
    else o.remove();
    return true;
  }
  window.addEventListener('message', function(ev){
    var d=ev.data;
    if (d && d.shashti==='removeMe') {   // a frame showing a lock/scam page asked to be removed
      var fs=document.querySelectorAll('iframe');
      for (var k=0;k<fs.length;k++){ if (fs[k].contentWindow===ev.source) {
        var t=fs[k]; for (var e=t; e && e!==document.body; e=e.parentElement){ if (getComputedStyle(e).position==='fixed') { t=e; break; } }
        if (t.tagName==='IFRAME' && t.hasAttribute('allowfullscreen')) return; // never the real player
        t.remove(); try { window.Android && Android.closedInFrame(); if (d.host && d.host!==location.hostname) Android.learnHost(d.host); } catch(x) {}
      } }
      return;
    }
    if (!d || d.shashti!=='close') return;
    var fr=document.querySelectorAll('iframe');
    for (var i=0;i<fr.length;i++){ try { fr[i].contentWindow.postMessage({shashti:'close'}, '*'); } catch(x) {} }
    if (window!==top && closeHere()) { try { window.Android && Android.closedInFrame(); } catch(x) {} }
  });
  try { // allowed sites: do nothing there
    var top = (location.ancestorOrigins && location.ancestorOrigins.length) ? location.ancestorOrigins[location.ancestorOrigins.length-1] : location.origin;
    var th = top.replace(/^https?:\/\//,'').split('/')[0].split('.').slice(-2).join('.');
    if ((window.__SHASHTI_ALLOW || []).indexOf(th) >= 0) return;
  } catch(e) {}
  // boxes the user removed before on this site
  try {
    var hide = (window.__SHASHTI_HIDE || {})[location.hostname.split('.').slice(-2).join('.')];
    if (hide && hide.length) {
      var css = document.createElement('style');
      css.textContent = hide.join(',') + '{display:none!important}';
      (document.head || document.documentElement).appendChild(css);
      document.addEventListener('DOMContentLoaded', function(){ (document.head||document.documentElement).appendChild(css); document.documentElement.style.overflow=''; document.body && (document.body.style.overflow=''); });
    }
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
  // a box that replaces the player (often inside the player's own frame): tell the app so it can retry without blocking
  var WALL=/robot|روبوت|vpn|ad.?block|disable|verify|تحقق|عطّل|عطل|مانع/i, reported=false;
  function wallCheck(){
    if (reported || !document.body) return;
    var vw=innerWidth, vh=innerHeight;
    var els=document.body.querySelectorAll('div,section,aside,dialog');
    for (var i=0;i<els.length;i++){
      var e=els[i], st=getComputedStyle(e);
      if (st.position!=='fixed' && st.position!=='absolute') continue;
      if (st.display==='none' || st.visibility==='hidden' || parseFloat(st.opacity)<0.3) continue;
      var r=e.getBoundingClientRect(); if (r.width*r.height < vw*vh*0.35) continue;
      if (e.querySelector('video')) continue;
      var txt=(e.innerText||'').slice(0,600);
      if (WALL.test(txt) || (window!==top && e.querySelector('canvas,img,svg') && txt.length<200)) {
        reported=true; try { window.Android && Android.reportWall(location.href); } catch(x) {}
        return;
      }
    }
  }
  setInterval(wallCheck, 2000);
  function start(){ sweep(); try { new MutationObserver(function(){ clearTimeout(window.__shT); window.__shT=setTimeout(sweep,300); })
      .observe(document.documentElement,{childList:true,subtree:true}); } catch(e) {} }
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
})();
