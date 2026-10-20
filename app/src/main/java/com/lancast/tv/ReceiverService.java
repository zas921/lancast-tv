package com.lancast.tv;

import android.app.*;
import android.content.*;
import android.net.wifi.WifiManager;
import android.os.*;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ReceiverService extends Service {
    public final class LocalBinder extends Binder { public ReceiverService get() { return ReceiverService.this; } }
    public static final long MAX_FILE=1024L*1024*1024;
    public PlayerEngine player;
    public Dlna dlna;
    public int port;
    public String startupError="";
    private HttpWire http;
    private WifiManager.MulticastLock multicast;
    private WifiManager.WifiLock wifi;
    private final SecureRandom random=new SecureRandom();
    private final Map<String,Long> tokens=new LinkedHashMap<>();
    private final Map<String,long[]> attempts=new LinkedHashMap<>();
    private final LinkedHashMap<String,Media> library=new LinkedHashMap<>();
    private final AtomicBoolean uploading=new AtomicBoolean();
    private File mediaDir;
    private String pin,name,uuid;
    private final Handler main=new Handler(Looper.getMainLooper());
    private byte[] page;
    public static final class Media {
        public String id,name,type; public File file;
        JSONObject json() throws JSONException { return new JSONObject().put("id",id).put("name",name).put("type",type).put("size",file.length()); }
    }
    @Override public void onCreate() {
        super.onCreate(); foreground();
        SharedPreferences settings=getSharedPreferences("receiver",MODE_PRIVATE);
        name=settings.getString("name","轻投屏 · 客厅"); uuid=settings.getString("uuid",UUID.randomUUID().toString()); settings.edit().putString("uuid",uuid).apply();
        renewPin(); player=new PlayerEngine(this); player.incoming=this::openPlayer;
        mediaDir=new File(getCacheDir(),"media"); mediaDir.mkdirs();
        File[] leftovers=mediaDir.listFiles(); if(leftovers!=null) for(File f:leftovers) if(f.isFile()) f.delete();
        File[] photos=getCacheDir().listFiles(); if(photos!=null) for(File f:photos) if(f.getName().startsWith("remote-photo-")) f.delete();
        try(InputStream in=getAssets().open("remote.html")) { ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] b=new byte[8192]; int n; while((n=in.read(b))>=0) bytes.write(b,0,n); page=bytes.toByteArray(); }
        catch(IOException e) { page="Remote page unavailable".getBytes(java.nio.charset.StandardCharsets.UTF_8); }
        try {
            try { http=new HttpWire(8192,this::route); } catch(IOException busy) { http=new HttpWire(0,this::route); }
            port=http.port(); http.start();
            WifiManager manager=(WifiManager)getApplicationContext().getSystemService(WIFI_SERVICE);
            if(manager!=null) {
                multicast=manager.createMulticastLock("lancast-discovery"); multicast.setReferenceCounted(false); multicast.acquire();
                wifi=manager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF,"lancast-stream"); wifi.setReferenceCounted(false); wifi.acquire();
            }
            dlna=new Dlna(uuid,port,player,this::name); dlna.enabled(settings.getBoolean("dlna",true)); dlna.start();
        } catch(Exception e) { startupError="接收服务启动失败："+e.getClass().getSimpleName(); }
    }
    private void foreground() {
        NotificationManager notifications=(NotificationManager)getSystemService(NOTIFICATION_SERVICE);
        if(Build.VERSION.SDK_INT>=26) notifications.createNotificationChannel(new NotificationChannel("receiver","投屏接收服务",NotificationManager.IMPORTANCE_LOW));
        Intent open=new Intent(this,MainActivity.class);
        PendingIntent pending=PendingIntent.getActivity(this,0,open,PendingIntent.FLAG_UPDATE_CURRENT|(Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0));
        Notification.Builder builder=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,"receiver"):new Notification.Builder(this);
        startForeground(1,builder.setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("轻投屏正在等待连接").setContentText("同一局域网内接收视频、照片和音乐").setContentIntent(pending).setOngoing(true).build());
    }
    public synchronized String pin() { return pin; }
    public synchronized void renewPin() { pin=String.format(Locale.US,"%06d",random.nextInt(1000000)); tokens.clear(); attempts.clear(); }
    public String name() { return name; }
    public void name(String value) { value=value.trim(); if(value.isEmpty()) return; name=value.substring(0,Math.min(32,value.length())); getSharedPreferences("receiver",MODE_PRIVATE).edit().putString("name",name).apply(); if(dlna!=null) dlna.announce("ssdp:alive"); }
    public void dlna(boolean enabled) { if(dlna!=null) dlna.enabled(enabled); getSharedPreferences("receiver",MODE_PRIVATE).edit().putBoolean("dlna",enabled).apply(); }
    public String base() { String ip=Net.ip(); return ip.isEmpty()?"":"http://"+ip+":"+port; }
    private synchronized String pair(HttpWire.Request r,String code) throws Exception {
        String address=r.peer.getHostAddress(); long now=System.currentTimeMillis();
        long[] count=attempts.get(address); if(count==null||now-count[0]>60000) { count=new long[]{now,0}; if(attempts.size()>100) attempts.clear(); attempts.put(address,count); }
        if(count[1]>=6) throw new HttpWire.HttpError(429,"尝试过多，请 1 分钟后再试");
        if(code==null||!MessageDigest.isEqual(pin.getBytes("UTF-8"),code.getBytes("UTF-8"))) { count[1]++; throw new HttpWire.HttpError(401,"连接码不正确，请查看电视上的 6 位连接码"); }
        attempts.remove(address);
        Iterator<Map.Entry<String,Long>> expired=tokens.entrySet().iterator();
        while(expired.hasNext()) if(expired.next().getValue()<now) expired.remove();
        if(tokens.size()>=24) tokens.remove(tokens.keySet().iterator().next());
        byte[] bytes=new byte[24]; random.nextBytes(bytes); StringBuilder token=new StringBuilder(); for(byte b:bytes) token.append(String.format(Locale.US,"%02x",b&255));
        tokens.put(token.toString(),now+12*60*60*1000L); return token.toString();
    }
    private synchronized void authorized(HttpWire.Request r) throws Exception {
        String token=r.header("x-lancast-token"); Long expires=token==null?null:tokens.get(token);
        if(expires==null||expires<System.currentTimeMillis()) throw new HttpWire.HttpError(401,"连接已失效，请重新输入电视上的连接码");
    }
    private void sameHost(HttpWire.Request r) throws Exception {
        String expected=r.local.getHostAddress()+":"+port;
        if(!expected.equals(r.header("host"))) throw new HttpWire.HttpError(403,"请使用电视显示的 IP 地址访问");
        String origin=r.header("origin"); if(origin!=null&&!origin.equals("http://"+expected)) throw new HttpWire.HttpError(403,"请求来源无效");
    }
    private void route(HttpWire.Request r,HttpWire.Response out) throws Exception {
        if(dlna!=null&&dlna.route(r,out)) return;
        if((r.path.equals("/")||r.path.equals("/remote.html"))&&(r.method.equals("GET")||r.method.equals("HEAD"))) {
            Map<String,String> headers=new LinkedHashMap<>();
            headers.put("Content-Security-Policy","default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'");
            out.bytes(200,"text/html; charset=utf-8",page,headers); return;
        }
        if(r.path.startsWith("/media/")&&(r.method.equals("GET")||r.method.equals("HEAD"))) {
            // Files require the paired browser token; UPnP uses the sender's own media URL.
            authorized(r); Media item; synchronized(library) { item=library.get(r.path.substring(7)); }
            if(item==null||!item.file.exists()) throw new HttpWire.HttpError(404,"媒体已清理"); out.file(item.file,item.type,r.header("range")); return;
        }
        if(!r.path.startsWith("/api/")) throw new HttpWire.HttpError(404,"Not found");
        sameHost(r);
        boolean read=r.path.equals("/api/status")||r.path.equals("/api/library");
        if(!r.method.equals(read?"GET":"POST")) throw new HttpWire.HttpError(405,"Method not allowed");
        if(r.path.equals("/api/pair")) { JSONObject body=json(r); send(out,new JSONObject().put("token",pair(r,body.optString("pin"))).put("name",name)); return; }
        authorized(r);
        switch(r.path) {
            case "/api/status": {
                Renderer.State s=player.state();
                send(out,new JSONObject().put("name",name).put("state",s.state).put("title",s.title).put("mime",s.mime).put("duration",s.duration).put("position",s.position).put("volume",s.volume).put("muted",s.muted).put("error",s.error).put("dlna",dlna!=null&&dlna.enabled())); break;
            }
            case "/api/library": { JSONArray items=new JSONArray(); synchronized(library) { for(Media item:library.values()) items.put(item.json()); } send(out,new JSONObject().put("items",items)); break; }
            case "/api/upload": upload(r,out); break;
            case "/api/control": {
                JSONObject body=json(r); String action=body.optString("action");
                switch(action) {
                    case "play": player.play(); break;
                    case "pause": player.pause(); break;
                    case "stop": player.stop(); break;
                    case "seek": { long ms=body.optLong("position",-1); if(ms<0||ms>Integer.MAX_VALUE) throw new HttpWire.HttpError(400,"无效进度"); player.seek((int)ms); break; }
                    case "volume": player.volume(body.getInt("value")); break;
                    case "mute": player.mute(body.getBoolean("value")); break;
                    case "load": { Media item; synchronized(library) { item=library.get(body.optString("id")); } if(item==null) throw new HttpWire.HttpError(404,"媒体已清理，请重新发送"); player.local(item.file,item.name,item.type); openPlayer(); break; }
                    case "url": { String url=body.optString("url"); PlayerEngine.validate(url); player.load(url,body.optString("title","网络媒体"),body.optString("type"),""); player.play(); openPlayer(); break; }
                    default: throw new HttpWire.HttpError(400,"未知播放操作");
                }
                send(out,new JSONObject().put("ok",true)); break;
            }
            case "/api/clear": clearLibrary(); send(out,new JSONObject().put("ok",true)); break;
            default: throw new HttpWire.HttpError(404,"Not found");
        }
    }
    private void openPlayer() { main.post(() -> { Intent intent=new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_SINGLE_TOP); startActivity(intent); }); }
    private JSONObject json(HttpWire.Request r) throws Exception { try { return new JSONObject(r.text(32768)); } catch(JSONException e) { throw new HttpWire.HttpError(400,"无效请求"); } }
    private void send(HttpWire.Response out,JSONObject body) throws IOException { out.text(200,"application/json; charset=utf-8",body.toString()); }
    private void upload(HttpWire.Request r,HttpWire.Response out) throws Exception {
        if(!uploading.compareAndSet(false,true)) throw new HttpWire.HttpError(409,"电视正在接收另一个文件，请稍后再试");
        File partial=null;
        try {
            if(r.header("content-length")==null) throw new HttpWire.HttpError(411,"上传需要文件长度");
            if(r.length<=0 || r.length>MAX_FILE) throw new HttpWire.HttpError(413,"单个文件需大于 0 且不超过 1 GB");
            String filename=r.query.get("name"); if(filename==null||filename.isEmpty()) filename="手机媒体";
            filename=filename.replace('\\','/'); filename=filename.substring(filename.lastIndexOf('/')+1); if(filename.length()>160) filename=filename.substring(filename.length()-160);
            String type=PlayerEngine.type(filename);
            if(type.isEmpty() || filename.toLowerCase(Locale.US).endsWith(".m3u8")) throw new HttpWire.HttpError(400,"请选择 MP4、MOV、MKV、MP3、M4A、AAC、WAV、OGG、FLAC、JPEG、PNG 或 WebP 文件；HEIC 请先转 JPEG");
            synchronized(library) {
                long size=0; for(Media item:library.values()) size+=item.file.length();
                if(library.size()>=40 || size+r.length>MAX_FILE) throw new HttpWire.HttpError(507,"投屏缓存已满，请先清理缓存（最多 40 项 / 1 GB）");
            }
            if(mediaDir.getUsableSpace()<r.length+64L*1024*1024) throw new HttpWire.HttpError(507,"电视存储空间不足，请先清理缓存");
            String id=UUID.randomUUID().toString(); partial=new File(mediaDir,id+".part");
            try(OutputStream file=new BufferedOutputStream(new FileOutputStream(partial),65536)) { HttpWire.copy(r.body,file,r.length); }
            File complete=new File(mediaDir,id+".media"); if(!partial.renameTo(complete)) throw new IOException("Unable to commit upload");
            Media item=new Media(); item.id=id; item.name=filename; item.type=type; item.file=complete; synchronized(library) { library.put(id,item); }
            send(out,item.json());
        } finally { if(partial!=null) partial.delete(); uploading.set(false); }
    }
    public void clearLibrary() throws Exception {
        if(!uploading.compareAndSet(false,true)) throw new HttpWire.HttpError(409,"正在接收文件，请稍后清理");
        try { player.clear(); synchronized(library) { for(Media item:library.values()) item.file.delete(); library.clear(); } }
        finally { uploading.set(false); }
    }
    @Override public IBinder onBind(Intent intent) { return new LocalBinder(); }
    @Override public int onStartCommand(Intent intent,int flags,int startId) { return START_NOT_STICKY; }
    @Override public void onDestroy() {
        if(http!=null) http.close(); if(dlna!=null) dlna.close(); if(player!=null) player.close();
        if(multicast!=null && multicast.isHeld()) multicast.release(); if(wifi!=null && wifi.isHeld()) wifi.release(); super.onDestroy();
    }
}
