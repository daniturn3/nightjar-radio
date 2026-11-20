const C='njr-v1';
self.addEventListener('install',e=>{self.skipWaiting();e.waitUntil(caches.open(C).then(c=>c.addAll(['./','icon-192.png','icon-512.png','manifest.json']).catch(()=>{})))});
self.addEventListener('activate',e=>e.waitUntil(self.clients.claim()));
self.addEventListener('fetch',e=>{const r=e.request,u=new URL(r.url);
  if(r.mode==='navigate'){e.respondWith(fetch(r,{cache:'no-store'}).then(x=>{caches.open(C).then(c=>c.put('./',x.clone()));return x}).catch(()=>caches.match('./')));return}
  if(u.origin===location.origin||/fonts\.(googleapis|gstatic)\.com/.test(u.host)){e.respondWith(caches.match(r).then(h=>h||fetch(r).then(x=>{if(x.ok){const y=x.clone();caches.open(C).then(c=>c.put(r,y))}return x})))}});
