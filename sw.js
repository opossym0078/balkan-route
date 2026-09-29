// Балканы · офлайн-режим
const V='balk-shell-v2',RT='tiles-rt',PRE='tiles-pre',MAX=7500;
const SHELL=['./','./index.html','./manifest.webmanifest','./icon-192.png','./icon-512.png','./icon-180.png'];
self.addEventListener('install',e=>{e.waitUntil(caches.open(V).then(c=>c.addAll(SHELL)).then(()=>self.skipWaiting()))});
self.addEventListener('activate',e=>{e.waitUntil(caches.keys().then(ks=>Promise.all(ks.filter(k=>k.startsWith('balk-shell')&&k!==V).map(k=>caches.delete(k)))).then(()=>self.clients.claim()))});
const tileKey=u=>{let st=null;if(/(^|\.)tile\.openstreetmap\.org$/.test(u.hostname))st='osm';else if(/basemaps\.cartocdn\.com$/.test(u.hostname))st=/dark_all/.test(u.pathname)?'dark':'voy';if(!st)return null;
  const m=u.pathname.match(/\/(\d+)\/(\d+)\/(\d+)(?:@2x)?\.png$/);return m?{st,z:m[1],x:m[2],y:m[3],id:`https://t.local/${st}/${m[1]}/${m[2]}/${m[3]}`}:null};
let puts=0;
async function trim(){const c=await caches.open(RT),ks=await c.keys();if(ks.length>MAX)for(const k of ks.slice(0,ks.length-MAX))await c.delete(k)}
async function tile(e,k){const [pre,rt]=await Promise.all([caches.open(PRE),caches.open(RT)]);
  const inPre=await pre.match(k.id),hit=inPre||await rt.match(k.id);
  const net=fetch(e.request).then(async r=>{if(r.ok){const b=await r.clone().blob();await (inPre?pre:rt).put(k.id,new Response(b,{headers:{'content-type':b.type||'image/png','x-size':String(b.size)}}));if(!inPre&&++puts%50===0)trim()}return r}).catch(()=>null);
  if(hit){e.waitUntil(net);return hit}
  const r=await net;if(r)return r;
  const alt=k.st==='dark'?['dark','voy','osm']:['osm','voy','dark'];
  for(const s of alt){const id=`https://t.local/${s}/${k.z}/${k.x}/${k.y}`;const h=await pre.match(id)||await rt.match(id);if(h)return h}
  return Response.error()}
async function shell(e){const c=await caches.open(V);
  if(e.request.mode==='navigate'){try{const r=await Promise.race([fetch(e.request),new Promise((_,j)=>setTimeout(()=>j(0),4000))]);if(r&&r.ok){c.put('./index.html',r.clone());return r}}catch(x){}
    return await c.match('./index.html')||await c.match('./')||Response.error()}
  const hit=await c.match(e.request,{ignoreSearch:true});const net=fetch(e.request).then(r=>{if(r.ok)c.put(e.request,r.clone());return r}).catch(()=>null);
  if(hit){e.waitUntil(net);return hit}return await net||Response.error()}
self.addEventListener('fetch',e=>{const q=e.request;if(q.method!=='GET'||q.cache==='no-store')return;const u=new URL(q.url);
  const k=tileKey(u);if(k){e.respondWith(tile(e,k));return}
  if(u.origin===self.location.origin)e.respondWith(shell(e))});
