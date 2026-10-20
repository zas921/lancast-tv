package com.lancast.tv;

import android.content.Context;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Surface;
import java.io.*;
import java.net.*;
import java.util.Locale;
import java.util.concurrent.*;

/** All player mutations run on Android's main thread. HTTP/DLNA callers use call(). */
public final class PlayerEngine implements Renderer {
    private final Context context;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService downloads=Executors.newSingleThreadExecutor();
    private MediaPlayer media;
    private Surface surface;
    private boolean prepared,wantPlay;
    private int generation, pendingSeek;
    private String state="NO_MEDIA_PRESENT",uri="",title="等待投屏",mime="",metadata="",error="";
    private int volume=70;
    private boolean muted;
    public File photo;
    public int videoWidth,videoHeight;
    public Runnable changed;
    public Runnable incoming;
    public PlayerEngine(Context context) { this.context=context; }
    private <T> T call(Callable<T> action) throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper()) return action.call();
        FutureTask<T> task=new FutureTask<>(action); main.post(task);
        try { return task.get(5,TimeUnit.SECONDS); }
        catch(TimeoutException e) { task.cancel(false); main.removeCallbacks(task); throw e; }
        catch(InterruptedException e) { task.cancel(false); main.removeCallbacks(task); Thread.currentThread().interrupt(); throw e; }
        catch(ExecutionException e) { Throwable cause=e.getCause(); if(cause instanceof Exception) throw (Exception)cause; throw new RuntimeException(cause); }
    }
    @Override public State state() {
        try { return call(() -> {
            State s=new State(); s.state=state; s.uri=uri; s.title=title; s.mime=mime; s.metadata=metadata; s.error=error; s.volume=volume; s.muted=muted;
            if(media!=null && prepared) try { s.duration=Math.max(0,media.getDuration()); s.position=Math.max(0,media.getCurrentPosition()); } catch(IllegalStateException ignored) {}
            return s;
        }); } catch(Exception e) { State s=new State(); s.error="播放器忙，请重试"; return s; }
    }
    public static String type(String name) {
        String value=name.toLowerCase(Locale.US).split("\\?",2)[0];
        if(value.endsWith(".jpg") || value.endsWith(".jpeg")) return "image/jpeg";
        if(value.endsWith(".png")) return "image/png";
        if(value.endsWith(".webp")) return "image/webp";
        if(value.endsWith(".mp3")) return "audio/mpeg";
        if(value.endsWith(".m4a")) return "audio/mp4";
        if(value.endsWith(".aac")) return "audio/aac";
        if(value.endsWith(".wav")) return "audio/x-wav";
        if(value.endsWith(".ogg")) return "audio/ogg";
        if(value.endsWith(".flac")) return "audio/flac";
        if(value.endsWith(".3gp")) return "video/3gpp";
        if(value.endsWith(".mkv")) return "video/x-matroska";
        if(value.endsWith(".ts")) return "video/mp2t";
        if(value.endsWith(".mpeg") || value.endsWith(".mpg")) return "video/mpeg";
        if(value.endsWith(".mp4") || value.endsWith(".m4v") || value.endsWith(".mov") || value.endsWith(".m3u8")) return "video/mp4";
        return "";
    }
    public static void validate(String value) {
        if(value==null || value.isEmpty() || value.length()>8192) throw new IllegalArgumentException("无效的媒体地址");
        try { URI parsed=new URI(value); if(!("http".equalsIgnoreCase(parsed.getScheme()) || "https".equalsIgnoreCase(parsed.getScheme())) || parsed.getHost()==null || parsed.getUserInfo()!=null || parsed.getFragment()!=null) throw new Exception(); }
        catch(Exception e) { throw new IllegalArgumentException("只接受 HTTP / HTTPS 媒体直链"); }
    }
    @Override public void load(String value,String name,String contentType,String meta) throws Exception {
        validate(value); call(() -> { loadMain(value,name,contentType,meta,null); return null; });
    }
    public void local(File file,String name,String contentType) throws Exception {
        call(() -> { loadMain(Uri.fromFile(file).toString(),name,contentType,"",file); wantPlay=true; if(prepared && media!=null) { media.start(); state="PLAYING"; } else if(photo!=null) state="PLAYING"; notifyChanged(); return null; });
    }
    private void loadMain(String value,String name,String contentType,String meta,File file) {
        generation++; final int version=generation; release(); discardPhoto(); videoWidth=videoHeight=0; pendingSeek=0;
        uri=value; title=name==null||name.isEmpty()?"手机投屏":name; mime=contentType==null||contentType.isEmpty()?type(value):contentType;
        metadata=meta==null?"":meta; error=""; wantPlay=false; state="TRANSITIONING";
        if(mime.startsWith("image/")) {
            if(file!=null) { photo=file; state="STOPPED"; }
            else downloads.execute(() -> downloadPhoto(value,version));
        } else try {
            media=new MediaPlayer(); MediaPlayer current=media;
            current.setAudioStreamType(AudioManager.STREAM_MUSIC); current.setScreenOnWhilePlaying(true);
            current.setWakeMode(context,android.os.PowerManager.PARTIAL_WAKE_LOCK);
            current.setSurface(surface); current.setVolume(muted?0:volume/100f,muted?0:volume/100f);
            current.setDataSource(context,Uri.parse(value));
            current.setOnPreparedListener(mp -> {
                if(media!=mp) return; prepared=true; videoWidth=mp.getVideoWidth(); videoHeight=mp.getVideoHeight();
                if(pendingSeek>0) mp.seekTo(pendingSeek);
                if(wantPlay) { mp.start(); state="PLAYING"; } else state="STOPPED";
                notifyChanged();
            });
            current.setOnVideoSizeChangedListener((mp,w,h) -> { videoWidth=w; videoHeight=h; notifyChanged(); });
            current.setOnCompletionListener(mp -> { if(media==mp) { state="STOPPED"; wantPlay=false; pendingSeek=0; notifyChanged(); } });
            current.setOnErrorListener((mp,what,extra) -> { if(media==mp) fail("播放失败：格式、编码或地址不受支持（"+what+" / "+extra+"）。请尝试 H.264 + AAC 的 MP4。",version); return true; });
            current.prepareAsync();
        } catch(Exception e) { fail("无法打开媒体，请检查格式、网络及直链是否有效。",version); }
        notifyChanged();
        if(incoming!=null) incoming.run();
    }
    private void downloadPhoto(String value,int version) {
        File downloaded=null;
        try {
            downloaded=File.createTempFile("remote-photo-",".img",context.getCacheDir());
            URL next=new URL(value); int redirects=0;
            while(true) {
                HttpURLConnection connection=(HttpURLConnection)next.openConnection();
                connection.setConnectTimeout(10000); connection.setReadTimeout(15000); connection.setInstanceFollowRedirects(false);
                try {
                    int code=connection.getResponseCode();
                    if(code>=300 && code<400) {
                        if(++redirects>4 || connection.getHeaderField("Location")==null) throw new IOException();
                        next=new URL(next,connection.getHeaderField("Location")); validate(next.toString()); continue;
                    }
                    if(code!=200 || connection.getContentLength()>20*1024*1024) throw new IOException();
                    try(InputStream in=connection.getInputStream();OutputStream out=new FileOutputStream(downloaded)) {
                        byte[] bytes=new byte[32768]; int count,total=0;
                        while((count=in.read(bytes))>=0) { total+=count; if(total>20*1024*1024) throw new IOException(); out.write(bytes,0,count); }
                    }
                    break;
                } finally { connection.disconnect(); }
            }
            File ready=downloaded; main.post(() -> { if(generation==version) { photo=ready; state=wantPlay?"PLAYING":"STOPPED"; notifyChanged(); } else ready.delete(); });
        } catch(Exception e) { if(downloaded!=null) downloaded.delete(); main.post(() -> fail("照片下载失败；支持 JPEG、PNG、WebP，单张网络照片不超过 20 MB。",version)); }
    }
    private void fail(String text,int version) { if(version!=generation) return; error=text; state="STOPPED"; wantPlay=false; release(); notifyChanged(); }
    public void displayError(String text) { error=text; notifyChanged(); }
    private void notifyChanged() { if(changed!=null) changed.run(); }
    @Override public void play() throws Exception { call(() -> {
        if(uri.isEmpty()) throw new IllegalStateException("尚未选择媒体");
        wantPlay=true;
        if(photo!=null) state="PLAYING";
        else if(media!=null && prepared) { if(media.getDuration()>0 && media.getCurrentPosition()>=media.getDuration()-250) media.seekTo(0); media.start(); state="PLAYING"; }
        else if(media==null && !mime.startsWith("image/")) throw new IllegalStateException("请重新选择媒体");
        notifyChanged(); return null;
    }); }
    @Override public void pause() throws Exception { call(() -> { wantPlay=false; if(media!=null && prepared) media.pause(); if(!uri.isEmpty()) state="PAUSED_PLAYBACK"; notifyChanged(); return null; }); }
    @Override public void stop() throws Exception { call(() -> { wantPlay=false; pendingSeek=0; if(media!=null && prepared) { media.pause(); media.seekTo(0); } if(!uri.isEmpty()) state="STOPPED"; notifyChanged(); return null; }); }
    public void clear() throws Exception { call(() -> { generation++; release(); discardPhoto(); uri=""; title="等待投屏"; error=""; mime=""; metadata=""; state="NO_MEDIA_PRESENT"; notifyChanged(); return null; }); }
    @Override public void seek(int ms) throws Exception { call(() -> { if(ms<0) throw new IllegalArgumentException("无效进度"); pendingSeek=ms; if(media!=null && prepared) media.seekTo(Math.min(ms,Math.max(0,media.getDuration()))); notifyChanged(); return null; }); }
    @Override public void volume(int value) throws Exception { if(value<0||value>100) throw new IllegalArgumentException("音量范围为 0–100"); call(() -> { volume=value; applyVolume(); return null; }); }
    @Override public void mute(boolean value) throws Exception { call(() -> { muted=value; applyVolume(); return null; }); }
    private void applyVolume() { if(media!=null) media.setVolume(muted?0:volume/100f,muted?0:volume/100f); notifyChanged(); }
    public void surface(Surface value) { surface=value; if(media!=null) try { media.setSurface(value); } catch(IllegalStateException ignored) {} }
    private void release() { if(media!=null) { media.setOnErrorListener(null); media.release(); media=null; } prepared=false; }
    private void discardPhoto() { if(photo!=null && photo.getName().startsWith("remote-photo-")) photo.delete(); photo=null; }
    public void close() { generation++; downloads.shutdownNow(); release(); discardPhoto(); }
}
