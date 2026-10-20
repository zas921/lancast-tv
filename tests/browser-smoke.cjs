// UI-only smoke test with a simulated television API; not a substitute for casting to a TV.
const fs=require('node:fs'),path=require('node:path'),http=require('node:http'),assert=require('node:assert/strict');
const {chromium}=require('playwright');
const root=path.resolve(__dirname,'..'),output=path.join(root,'build');fs.mkdirSync(output,{recursive:true});
let browser,server,page,client,session,checks=0;const errors=[];
const state={name:'轻投屏 · 客厅',state:'NO_MEDIA_PRESENT',title:'等待投屏',mime:'',duration:180000,position:0,volume:70,muted:false,error:''};
const library=[];
function check(v,m){assert.ok(v,m);checks++;}
function rpc(method,params={}){return client.send(method,params);}
async function evaluate(expression){return page.evaluate(expression);}
async function waitFor(expression){for(let i=0;i<50;i++){if(await evaluate(expression))return;await new Promise(r=>setTimeout(r,100));}throw new Error('UI condition timed out: '+expression);}
async function screenshot(name){const r=await rpc('Page.captureScreenshot',{format:'png',captureBeyondViewport:false},session);fs.writeFileSync(path.join(output,name),Buffer.from(r.data,'base64'));}
async function main(){
 server=http.createServer(async(req,res)=>{try{
  const url=new URL(req.url,'http://localhost');let bytes=[];for await(const b of req)bytes.push(b);const data=Buffer.concat(bytes);let value={};if(req.headers['content-type']?.includes('json'))value=JSON.parse(data.toString()||'{}');
  function json(v){res.setHeader('Content-Type','application/json');res.end(JSON.stringify(v));}
  if(url.pathname==='/'){res.setHeader('Content-Type','text/html; charset=utf-8');res.end(fs.readFileSync(path.join(root,'app/src/main/assets/remote.html')));return;}
  if(url.pathname==='/api/pair'){if(value.pin!=='123456'){res.statusCode=401;return res.end('连接码不正确');}return json({token:'test-token',name:state.name});}
  if(req.headers['x-lancast-token']!=='test-token'){res.statusCode=401;return res.end('请重新配对');}
  switch(url.pathname){
   case '/api/status':return json(state);
   case '/api/library':return json({items:library});
   case '/api/upload':{const item={id:'item-'+library.length,name:url.searchParams.get('name'),type:'image/jpeg',size:data.length};library.push(item);return json(item);}
   case '/api/control':switch(value.action){case 'load':{const item=library.find(x=>x.id===value.id);Object.assign(state,{title:item.name,mime:item.type,state:'PLAYING'});break;}case 'pause':state.state='PAUSED_PLAYBACK';break;case 'play':state.state='PLAYING';break;case 'seek':state.position=value.position;break;case 'volume':state.volume=value.value;break;case 'mute':state.muted=value.value;break;case 'stop':state.state='STOPPED';break;}return json({ok:true});
   case '/api/clear':library.length=0;Object.assign(state,{state:'NO_MEDIA_PRESENT',title:'等待投屏',mime:''});return json({ok:true});
   default:res.statusCode=404;res.end();
  }
 }catch(e){res.statusCode=500;res.end(e.message);}});
 await new Promise(r=>server.listen(0,'127.0.0.1',r));
 const chrome=process.env.CHROME_PATH||process.argv[2];
 browser=await chromium.launch({...(chrome?{executablePath:chrome}:{}),headless:true,args:['--disable-gpu'],timeout:20000});
 const context=await browser.newContext({viewport:{width:390,height:844},isMobile:true,deviceScaleFactor:1});page=await context.newPage();client=await context.newCDPSession(page);page.on('pageerror',e=>errors.push(e.message));
 await rpc('Page.enable',{},session);await rpc('Runtime.enable',{},session);
 await rpc('Emulation.setDeviceMetricsOverride',{width:390,height:844,deviceScaleFactor:1,mobile:true},session);
 await rpc('Page.navigate',{url:'http://127.0.0.1:'+server.address().port},session);await waitFor("document.readyState==='complete'");
 check(await evaluate("!document.getElementById('pairing').classList.contains('hidden')"),'pairing visible');check(await evaluate('document.documentElement.scrollWidth<=innerWidth'),'no horizontal overflow, pairing');await screenshot('phone-pairing.png');
 await evaluate("document.getElementById('pin').value='000000';document.getElementById('pair-form').dispatchEvent(new Event('submit',{cancelable:true}));");await waitFor("document.getElementById('toast').textContent.includes('不正确')");checks++;
 await evaluate("document.getElementById('pin').value='123456';document.getElementById('pair-form').dispatchEvent(new Event('submit',{cancelable:true}));");await waitFor("document.getElementById('badge').textContent==='已连接电视'");checks++;
 check(await evaluate('document.documentElement.scrollWidth<=innerWidth'),'no horizontal overflow, controls');
 await evaluate("(()=>{const d=new DataTransfer();d.items.add(new File(['photo-one'],'合照.jpg',{type:'image/jpeg'}));d.items.add(new File(['photo-two'],'风景.jpg',{type:'image/jpeg'}));document.getElementById('files').files=d.files;document.getElementById('files').dispatchEvent(new Event('change'));})()");
 await waitFor("document.getElementById('library').children.length===2");check(library.length===2,'multiple file upload');check(state.title==='合照.jpg','first item automatically plays');
 await evaluate("document.getElementById('play').click()");await waitFor("document.getElementById('play').textContent==='播放'");check(state.state==='PAUSED_PLAYBACK','pause sends control');
 await evaluate("document.getElementById('next').click()");await waitFor("document.getElementById('title').textContent==='风景.jpg'");checks++;
 await evaluate("document.getElementById('volume').value='42';document.getElementById('volume').dispatchEvent(new Event('change'));");await waitFor("document.getElementById('volume-value').textContent==='42%'");check(state.volume===42,'volume sends control');
 await evaluate("document.getElementById('forward10').click()");await waitFor("document.getElementById('position').textContent==='00:10'");check(state.position===10000,'seek sends control');
 await screenshot('phone-controls.png');
 await rpc('Emulation.setDeviceMetricsOverride',{width:1280,height:900,deviceScaleFactor:1,mobile:false},session);await screenshot('browser-controls.png');
 check(await evaluate('document.documentElement.scrollWidth<=innerWidth'),'no horizontal overflow, desktop');
 await evaluate("document.getElementById('disconnect').click()");await waitFor("!document.getElementById('pairing').classList.contains('hidden')");checks++;
 check(errors.length===0,'no uncaught JavaScript exceptions');
 // Independently decode the production QR using Chrome when BarcodeDetector is available.
 const qr=fs.readFileSync(path.join(output,'qr-test.svg')).toString('base64');
 const decoded=await evaluate("(async()=>{if(!('BarcodeDetector' in window))return null;const img=new Image();img.src='data:image/svg+xml;base64,"+qr+"';await img.decode();const codes=await new BarcodeDetector({formats:['qr_code']}).detect(img);return codes.map(x=>x.rawValue)})()");
 if(decoded!==null){check(decoded.includes('http://192.168.1.100:8192/#pin=123456'),'QR roundtrip');}else{console.log('BarcodeDetector unavailable; QR decode requires separate verification.');}
 console.log('PASS: '+checks+' browser UI checks (simulated TV API)');
}
main().catch(e=>{console.error(e);process.exitCode=1;}).finally(async()=>{if(browser)await browser.close();if(server)server.close();});
