import { test, before, after } from 'node:test';
import assert from 'node:assert/strict';
import http from 'node:http';
import { createDesktopServer, privateAddress, rewritePlaylist } from './server.mjs';
const token = 'test-only-pairing-code-1234567890';
let origin, base, fixture, relay, closeSessions;
const seen = [];
before(async () => {
  fixture = http.createServer((req, res) => {
    seen.push({ path: req.url, auth: req.headers.authorization, range: req.headers.range });
    if (req.url === '/redirect.m3u8') { res.writeHead(302, { Location: '/folder/master.m3u8' }); res.end(); }
    else if (req.url === '/slow.ts') { res.writeHead(200, {'Content-Length':100000}); res.write('first'); req.on('close',()=>seen.push({path:'slow-closed'})); }
    else if (req.url.endsWith('master.m3u8')) { res.setHeader('Content-Type','application/vnd.apple.mpegurl'); res.end('#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="audio",URI="audio.m3u8"\n#EXT-X-STREAM-INF:BANDWIDTH=1000\nvideo.m3u8\n'); }
    else if (req.url.endsWith('.m3u8')) { res.setHeader('Content-Type','application/vnd.apple.mpegurl'); res.end('#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI="key.bin",IV=0x01\n#EXT-X-MAP:URI="init.mp4",BYTERANGE="4@0"\n#EXTINF:3,\n#EXT-X-BYTERANGE:4@2\nsegment.ts\n#EXT-X-ENDLIST\n'); }
    else { const data = Buffer.from('0123456789'); const range = req.headers.range?.match(/^bytes=(\d+)-(\d+)$/); res.setHeader('Accept-Ranges','bytes'); if (range) { const a=+range[1], b=+range[2]; res.writeHead(206, { 'Content-Range': `bytes ${a}-${b}/10`, 'Content-Length':b-a+1 }); res.end(data.subarray(a,b+1)); } else { res.setHeader('Content-Length',data.length); res.end(data); } }
  });
  await new Promise((resolve) => fixture.listen(0,'127.0.0.1',resolve)); origin=`http://127.0.0.1:${fixture.address().port}`;
  ({ server:relay, closeSessions } = createDesktopServer({ token, allowTestOrigin:origin }));
  await new Promise((resolve) => relay.listen(0,'127.0.0.1',resolve)); base=`http://127.0.0.1:${relay.address().port}`;
});
after(async () => { closeSessions(); await Promise.all([fixture,relay].map((s) => new Promise((resolve) => { s.close(resolve); s.closeAllConnections(); }))); });
const request = (path, options={}) => fetch(base+path,{...options, headers:{Authorization:`Bearer ${token}`,...options.headers}});
const create = async (url) => { const res=await request('/v1/sessions',{method:'POST',body:JSON.stringify({url})}); assert.equal(res.status,201); return res.json(); };
const assets = (text) => [...text.matchAll(/(?:^|URI=")((?:\/v1\/sessions\/)[^\s"]+)/gm)].map((m)=>m[1]);
test('配对验证和真实处理能力',async () => {
  assert.equal((await fetch(base+'/v1/status')).status,401);
  assert.equal((await request('/v1/status',{headers:{Authorization:'Bearer wrong'}})).status,401);
  const status=await (await request('/v1/status')).json(); assert.deepEqual(status.capabilities,{adRemoval:false,superResolution:false});
});
test('主列表、音轨、子列表、密钥和初始化片段全部经过电脑，凭证不泄漏给上游',async () => {
  const session=await create(origin+'/redirect.m3u8');
  const root=await (await request(session.path)).text(); assert.ok(!root.includes(origin)); assert.equal(assets(root).length,2);
  const media=await (await request(assets(root)[1])).text();
  assert.equal(assets(media).length,3); assert.ok(media.includes('BYTERANGE="4@0"')); assert.ok(media.includes('IV=0x01'));
  for (const path of assets(media)) assert.equal((await request(path)).status,200);
  assert.equal(seen.some((r)=>r.auth),false);
  assert.ok(seen.some((r)=>r.path==='/folder/video.m3u8'));
  assert.equal((await fetch(base+session.path)).status,401);
  assert.equal((await request(`/v1/sessions/${session.id}`,{method:'DELETE'})).status,200);
  assert.equal((await request(session.path)).status,404);
});
test('MP4 与分片保留 Range、HEAD 和 206，支持拖动快进',async () => {
  const session=await create(origin+'/film.mp4');
  const res=await request(session.path,{headers:{Range:'bytes=2-5'}}); assert.equal(res.status,206); assert.equal(res.headers.get('content-range'),'bytes 2-5/10'); assert.equal(await res.text(),'2345');
  const head=await request(session.path,{method:'HEAD'}); assert.equal(head.headers.get('content-length'),'10'); assert.equal(await head.text(),'');
  await request(`/v1/sessions/${session.id}`,{method:'DELETE'});
});
test('阻止私网、URL 凭证及非 HTTP 片源，测试豁免仅限单个 origin',async () => {
  for (const url of ['http://127.0.0.1:1/a','http://192.168.0.1/a','http://[::1]/a','http://[::ffff:7f00:1]/a','file:///etc/passwd','http://user:pass@example.com/a']) {
    const res=await request('/v1/sessions',{method:'POST',body:JSON.stringify({url})}); assert.ok([400,403].includes(res.status),`${url}: ${res.status}`);
  }
  for (const ip of ['127.0.0.1','192.168.0.1','10.0.0.1','::1','::ffff:7f00:1','fc00::1','fe80::1','2002:7f00:1::']) assert.equal(privateAddress(ip),true,ip);
  assert.equal(privateAddress('8.8.8.8'),false); assert.equal(privateAddress('2606:4700:4700::1111'),false);
});
test('拒绝无法安全重写的 HLS，而不是绕开电脑访问',() => {
  assert.throws(()=>rewritePlaylist('#EXTM3U\n#EXT-X-DEFINE:NAME="a",VALUE="b"','https://example.com',()=>''));
  assert.throws(()=>rewritePlaylist('<html>bad</html>','https://example.com',()=>''));
});
test('域名解析固定地址，兼容 Node 双栈 all lookup，不重新解析域名',async () => {
  const name=`http://fixture.test:${fixture.address().port}`;
  let calls=0;
  const app=createDesktopServer({token,allowTestOrigin:name,lookupHost:async(host)=>{assert.equal(host,'fixture.test');calls++;return [{address:'127.0.0.1',family:4}];}});
  await new Promise((resolve)=>app.server.listen(0,'127.0.0.1',resolve));
  const target=`http://127.0.0.1:${app.server.address().port}`;
  try {
    const res=await fetch(target+'/v1/sessions',{method:'POST',headers:{Authorization:`Bearer ${token}`},body:JSON.stringify({url:name+'/film.mp4'})});
    assert.equal(res.status,201); const session=await res.json();
    const media=await fetch(target+session.path,{headers:{Authorization:`Bearer ${token}`}});assert.equal(media.status,200);assert.equal(await media.text(),'0123456789');assert.equal(calls,2);
  } finally { app.closeSessions(); await new Promise((resolve)=>{app.server.close(resolve);app.server.closeAllConnections();}); }
});
test('过期任务自动清理，不会永久占满播放槽',async () => {
  const app=createDesktopServer({token,allowTestOrigin:origin,idleMs:35});
  await new Promise((resolve)=>app.server.listen(0,'127.0.0.1',resolve));
  const target=`http://127.0.0.1:${app.server.address().port}`;
  try {
    const result=await fetch(target+'/v1/sessions',{method:'POST',headers:{Authorization:`Bearer ${token}`},body:JSON.stringify({url:origin+'/film.mp4'})});assert.equal(result.status,201);const session=await result.json();
    await new Promise((resolve)=>setTimeout(resolve,100));
    assert.equal((await fetch(target+session.path,{headers:{Authorization:`Bearer ${token}`}})).status,404);
  } finally {app.closeSessions();await new Promise((resolve)=>{app.server.close(resolve);app.server.closeAllConnections();});}
});
test('Fake-IP 兼容必须显式开启且只适用于域名，不放行私网和字面量',async () => {
  for(const enabled of [false,true]) {
    const app=createDesktopServer({token,allowFakeIp:enabled,lookupHost:async()=>[{address:'198.18.0.10',family:4}]});
    await new Promise((resolve)=>app.server.listen(0,'127.0.0.1',resolve));const target=`http://127.0.0.1:${app.server.address().port}`;
    try {
      for(const [url,expected] of [['https://public.test/film.mp4',enabled?201:403],['http://198.18.0.10/film.mp4',403],['http://192.168.1.1/film.mp4',403]]) {
        const result=await fetch(target+'/v1/sessions',{method:'POST',headers:{Authorization:`Bearer ${token}`},body:JSON.stringify({url})});assert.equal(result.status,expected,url);
      }
    } finally {app.closeSessions();await new Promise((resolve)=>{app.server.close(resolve);app.server.closeAllConnections();});}
  }
});

test('删除会话会终止尚在传输的媒体请求',async () => {
  const session=await create(origin+'/slow.ts');
  const response=await request(session.path), reader=response.body.getReader();
  assert.equal(Buffer.from((await reader.read()).value).toString(),'first');
  await request(`/v1/sessions/${session.id}`,{method:'DELETE'});
  await assert.rejects(reader.read());
  for(let i=0;i<30&&!seen.some(r=>r.path==='slow-closed');i++) await new Promise(r=>setTimeout(r,10));
  assert.ok(seen.some(r=>r.path==='slow-closed'));
});
