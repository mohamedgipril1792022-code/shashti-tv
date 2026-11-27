package com.shashti.tv;

/** The phone remote's page (served by RemoteServer). */
final class RemotePage {
    static final String HTML =
        "<!doctype html><html lang=\"ar\" dir=\"rtl\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no\"><title>ريموت Sajo TV</title>\n" +
        "<style>\n" +
        "*{box-sizing:border-box;-webkit-tap-highlight-color:transparent;-webkit-user-select:none;user-select:none}\n" +
        "body{margin:0 auto;background:#120d0e;color:#fff;font-family:system-ui,sans-serif;padding:14px;max-width:460px}\n" +
        "h1{font-size:18px;text-align:center;margin:6px 0 14px}\n" +
        ".g{display:grid;gap:10px;margin-top:10px}\n" +
        ".r3{grid-template-columns:repeat(3,1fr)}.r4{grid-template-columns:repeat(4,1fr)}\n" +
        "button{background:#2a1a1d;color:#fff;border:1px solid #3a2124;border-radius:16px;font-size:22px;padding:16px 0;touch-action:manipulation}\n" +
        "button:active{background:#e50914}\n" +
        ".ok{background:#e50914;font-weight:700}\n" +
        "input{flex:1;min-width:0;background:#0f0a0b;color:#fff;border:1px solid #3a2124;border-radius:14px;padding:12px;font-size:16px;-webkit-user-select:text;user-select:text}\n" +
        ".row{display:flex;gap:8px;margin-top:12px}\n" +
        ".row button{padding:12px 16px;font-size:16px;white-space:nowrap}\n" +
        "#s{text-align:center;color:#ffb300;font-size:13px;min-height:18px;margin-top:10px}\n" +
        "</style></head><body>\n" +
        "<h1>📱 ريموت Sajo TV</h1>\n" +
        "<div class=\"g r3\"><span></span><button data-c=\"up\">▲</button><span></span>\n" +
        "<button data-c=\"right\">▶</button><button class=\"ok\" data-c=\"ok\">OK</button><button data-c=\"left\">◀</button>\n" +
        "<span></span><button data-c=\"down\">▼</button><span></span></div>\n" +
        "<div class=\"g r3\"><button data-c=\"back\">↩ رجوع</button><button data-c=\"home\">🏠</button><button data-c=\"menu\">☰</button></div>\n" +
        "<div class=\"g r4\"><button data-c=\"back10\">⏪ ١٠</button><button data-c=\"play\">⏯</button><button data-c=\"fwd10\">١٠ ⏩</button><button data-c=\"skip\" style=\"font-size:14px\">⏭ مقدمة</button></div>\n" +
        "<div class=\"g r3\"><button data-c=\"voldown\">🔉</button><button data-c=\"mute\">🔇</button><button data-c=\"volup\">🔊</button></div>\n" +
        "<div class=\"row\"><input id=\"t\" placeholder=\"اكتب نص للخانة المفتوحة…\"><button id=\"st\">أرسل</button></div>\n" +
        "<div class=\"row\"><input id=\"u\" dir=\"ltr\" placeholder=\"https://…\"><button id=\"su\">افتح</button></div>\n" +
        "<div id=\"s\"></div>\n" +
        "<script>\n" +
        "var K='@@K@@',S=document.getElementById('s');\n" +
        "function call(p,q){return fetch(p+'?k='+K+q).then(function(r){return r.text()}).then(function(x){S.textContent=x==='ok'?'':x;return x}).catch(function(){S.textContent='مفيش اتصال بالتلفزيون';return 'err'})}\n" +
        "function vib(){if(navigator.vibrate)navigator.vibrate(12)}\n" +
        "document.querySelectorAll('[data-c]').forEach(function(b){var c=b.dataset.c,t=0,rep=/^(up|down|left|right|volup|voldown|back10|fwd10)$/.test(c);\n" +
        " function go(){vib();call('/c','&c='+c)}\n" +
        " b.addEventListener('pointerdown',function(e){e.preventDefault();go();if(rep){t=setTimeout(function(){t=setInterval(go,140)},450)}});\n" +
        " ['pointerup','pointerleave','pointercancel'].forEach(function(n){b.addEventListener(n,function(){clearTimeout(t);clearInterval(t)})})});\n" +
        "document.getElementById('st').onclick=function(){var i=document.getElementById('t'),v=i.value;if(v)call('/t','&s='+encodeURIComponent(v)).then(function(x){if(x==='ok')i.value='';else if(x==='none')S.textContent='مفيش خانة مفتوحة — اختار خانة بالريموت الأول'})};\n" +
        "document.getElementById('su').onclick=function(){var v=document.getElementById('u').value.trim();if(v)call('/o','&u='+encodeURIComponent(v))};\n" +
        "</script></body></html>";

    private RemotePage() { }
}
