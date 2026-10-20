package com.lancast.tv;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.media.ExifInterface;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity implements SurfaceHolder.Callback {
    private static final int BG=Color.rgb(11,16,32), INK=Color.rgb(240,245,252), GREEN=Color.rgb(101,229,191);
    private final Handler handler=new Handler();
    private final ExecutorService images=Executors.newSingleThreadExecutor();
    private ReceiverService service;
    private FrameLayout root,home,stage;
    private SurfaceView video;
    private ImageView image;
    private TextView title,details,music,error;
    private LinearLayout controls;
    private Button pause,dlnaButton;
    private SeekBar seek;
    private HomeView homeView;
    private boolean bound,dragging,showing,controlsVisible=true;
    private String photoPath="", mediaKey="";
    private Bitmap displayed;
    private int imageGeneration;
    private long controlsUntil;
    private final ServiceConnection connection=new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name,IBinder binder) {
            service=((ReceiverService.LocalBinder)binder).get(); bound=true;
            service.player.changed=MainActivity.this::update;
            if(video.getHolder().getSurface().isValid()) service.player.surface(video.getHolder().getSurface()); update();
        }
        @Override public void onServiceDisconnected(ComponentName name) { service=null; bound=false; }
    };
    private final Runnable tick=new Runnable() { @Override public void run() { update(); handler.postDelayed(this,750); } };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN|View.SYSTEM_UI_FLAG_HIDE_NAVIGATION|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY|View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        build();
        Intent intent=new Intent(this,ReceiverService.class); if(Build.VERSION.SDK_INT>=26) startForegroundService(intent); else startService(intent);
        bindService(intent,connection,BIND_AUTO_CREATE);
    }
    private int dp(float n) { return (int)(n*getResources().getDisplayMetrics().density+0.5f); }
    private TextView label(String text,float size,int color) { TextView v=new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(color); return v; }
    private Button button(String text,Runnable click) {
        Button button=new Button(this); button.setText(text); button.setTextSize(15); button.setTextColor(INK); button.setAllCaps(false); button.setMinHeight(dp(44));
        GradientDrawable background=new GradientDrawable(); background.setColor(Color.rgb(30,43,64)); background.setCornerRadius(dp(12)); background.setStroke(dp(1),Color.rgb(65,88,111)); button.setBackground(background);
        button.setPadding(dp(16),dp(4),dp(16),dp(4));
        button.setOnFocusChangeListener((view,focus) -> { view.setScaleX(focus?1.04f:1f); view.setScaleY(focus?1.04f:1f); background.setStroke(dp(focus?2:1),focus?GREEN:Color.rgb(65,88,111)); });
        button.setOnClickListener(v -> { reveal(); click.run(); }); return button;
    }
    private void addButton(LinearLayout row,Button button) { LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(0,dp(44),1); p.setMargins(dp(5),0,dp(5),0); row.addView(button,p); }
    private void build() {
        root=new FrameLayout(this); root.setBackgroundColor(BG); setContentView(root);
        home=new FrameLayout(this); root.addView(home,new FrameLayout.LayoutParams(-1,-1));
        homeView=new HomeView(); home.addView(homeView,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout homeButtons=new LinearLayout(this); homeButtons.setPadding(dp(30),0,dp(30),0);
        FrameLayout.LayoutParams footer=new FrameLayout.LayoutParams(-1,dp(44),Gravity.BOTTOM); footer.bottomMargin=dp(18); home.addView(homeButtons,footer);
        addButton(homeButtons,button("设备名称",this::rename));
        dlnaButton=button("DLNA：开启",() -> { if(service!=null&&service.dlna!=null) { service.dlna(!service.dlna.enabled()); update(); } }); addButton(homeButtons,dlnaButton);
        addButton(homeButtons,button("刷新连接码",() -> { if(service!=null) { service.renewPin(); homeView.invalidate(); toast("已更新连接码，浏览器需重新配对"); } }));
        addButton(homeButtons,button("清理缓存",this::clear));
        addButton(homeButtons,button("使用说明",this::help));
        addButton(homeButtons,button("退出接收",this::exit));
        stage=new FrameLayout(this); stage.setBackgroundColor(Color.BLACK); stage.setVisibility(View.GONE); root.addView(stage,new FrameLayout.LayoutParams(-1,-1));
        video=new SurfaceView(this); video.getHolder().addCallback(this); stage.addView(video,new FrameLayout.LayoutParams(-1,-1,Gravity.CENTER));
        image=new ImageView(this); image.setScaleType(ImageView.ScaleType.FIT_CENTER); stage.addView(image,new FrameLayout.LayoutParams(-1,-1));
        music=label("♫\n音乐正在大屏播放",36,GREEN); music.setGravity(Gravity.CENTER); stage.addView(music,new FrameLayout.LayoutParams(-1,-1));
        error=label("",18,Color.rgb(255,190,144)); error.setGravity(Gravity.CENTER); error.setPadding(dp(40),0,dp(40),0); stage.addView(error,new FrameLayout.LayoutParams(-1,-1));
        controls=new LinearLayout(this); controls.setOrientation(LinearLayout.VERTICAL); controls.setPadding(dp(28),dp(16),dp(28),dp(16)); controls.setBackgroundColor(0xE6101728);
        FrameLayout.LayoutParams overlay=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM); stage.addView(controls,overlay);
        title=label("",20,INK); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END); controls.addView(title);
        details=label("",12,Color.LTGRAY); controls.addView(details);
        seek=new SeekBar(this); seek.setMax(1000); controls.addView(seek,new LinearLayout.LayoutParams(-1,dp(32)));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar bar,int value,boolean user) { if(user) reveal(); }
            public void onStartTrackingTouch(SeekBar bar) { dragging=true; }
            public void onStopTrackingTouch(SeekBar bar) { dragging=false; act(() -> { Renderer.State s=service.player.state(); service.player.seek((int)(s.duration*(bar.getProgress()/1000d))); }); }
        });
        LinearLayout row=new LinearLayout(this); controls.addView(row);
        addButton(row,button("− 10 秒",() -> skip(-10000)));
        pause=button("暂停",this::toggle); addButton(row,pause);
        addButton(row,button("+ 10 秒",() -> skip(10000)));
        addButton(row,button("音量 −",() -> volume(-10)));
        addButton(row,button("音量 +",() -> volume(10)));
        addButton(row,button("结束投屏",() -> act(() -> service.player.clear())));
        stage.setOnTouchListener((v,event) -> { if(event.getAction()==android.view.MotionEvent.ACTION_DOWN) { reveal(); return true; } return false; });
    }
    @Override protected void onStart() { super.onStart(); handler.post(tick); }
    @Override protected void onStop() { handler.removeCallbacks(tick); super.onStop(); }
    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null); images.shutdownNow(); imageGeneration++;
        if(service!=null) { service.player.changed=null; service.player.surface(null); }
        if(bound) unbindService(connection); displayed=null; super.onDestroy();
    }
    private void update() {
        if(service==null||isFinishing()) return;
        homeView.invalidate(); if(service.dlna!=null) dlnaButton.setText("DLNA："+(service.dlna.enabled()?"开启":"关闭"));
        Renderer.State s=service.player.state(); boolean active=!s.uri.isEmpty();
        if(active!=showing) { showing=active; stage.setVisibility(active?View.VISIBLE:View.GONE); home.setVisibility(active?View.GONE:View.VISIBLE); if(active) { reveal(); pause.requestFocus(); } }
        if(!active) { if(!photoPath.isEmpty()) { photoPath=""; imageGeneration++; image.setImageDrawable(null); displayed=null; } return; }
        if(!mediaKey.equals(s.uri)) { mediaKey=s.uri; reveal(); }
        title.setText(s.title);
        details.setText((s.state.equals("TRANSITIONING")?"正在加载…":Dlna.time(s.position)+" / "+Dlna.time(s.duration))+"   ·   音量 "+(s.muted?"静音":s.volume+"%")+"   ·   确认键暂停 / 方向键控制 / 返回键结束");
        pause.setText(s.state.equals("PLAYING")?"暂停":"播放");
        if(!dragging) seek.setProgress(s.duration>0?(int)(s.position*1000L/s.duration):0);
        seek.setEnabled(s.duration>0);
        boolean photo=s.mime.startsWith("image/"), audio=s.mime.startsWith("audio/");
        video.setVisibility(!photo&&!audio?View.VISIBLE:View.INVISIBLE); image.setVisibility(photo?View.VISIBLE:View.GONE); music.setVisibility(audio?View.VISIBLE:View.GONE);
        error.setText(s.error); error.setVisibility(s.error.isEmpty()?View.GONE:View.VISIBLE);
        if(photo && service.player.photo!=null && !photoPath.equals(service.player.photo.getAbsolutePath())) loadPhoto(service.player.photo);
        if(!photo && !photoPath.isEmpty()) { photoPath=""; imageGeneration++; image.setImageDrawable(null); displayed=null; }
        fitVideo();
        if(!dragging && controlsVisible && System.currentTimeMillis()>controlsUntil && s.state.equals("PLAYING") && s.error.isEmpty()) { controlsVisible=false; controls.setVisibility(View.GONE); stage.setFocusableInTouchMode(true); stage.requestFocus(); }
    }
    private void fitVideo() {
        if(service==null||stage.getWidth()==0) return;
        int vw=service.player.videoWidth,vh=service.player.videoHeight;
        if(vw<=0||vh<=0) return;
        float scale=Math.min(stage.getWidth()/(float)vw,stage.getHeight()/(float)vh);
        FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)video.getLayoutParams(); int w=Math.round(vw*scale),h=Math.round(vh*scale);
        if(params.width!=w||params.height!=h) { params.width=w; params.height=h; params.gravity=Gravity.CENTER; video.setLayoutParams(params); }
    }
    private void loadPhoto(File file) {
        photoPath=file.getAbsolutePath(); int version=++imageGeneration; image.setImageDrawable(null);
        int width=Math.max(1280,stage.getWidth()),height=Math.max(720,stage.getHeight());
        images.execute(() -> {
            Bitmap bitmap=null;
            try {
                BitmapFactory.Options options=new BitmapFactory.Options(); options.inJustDecodeBounds=true; BitmapFactory.decodeFile(file.getAbsolutePath(),options);
                options.inSampleSize=1;
                while(options.outWidth/options.inSampleSize>width*1.5 || options.outHeight/options.inSampleSize>height*1.5) options.inSampleSize*=2;
                options.inJustDecodeBounds=false; options.inPreferredConfig=Bitmap.Config.RGB_565; bitmap=BitmapFactory.decodeFile(file.getAbsolutePath(),options);
                if(bitmap==null) throw new IOException();
                try {
                    ExifInterface exif=new ExifInterface(file.getAbsolutePath()); int orientation=exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,1); Matrix matrix=new Matrix();
                    switch(orientation) { case 2: matrix.setScale(-1,1); break; case 3: matrix.setRotate(180); break; case 4: matrix.setScale(1,-1); break; case 5: matrix.setRotate(90); matrix.postScale(-1,1); break; case 6: matrix.setRotate(90); break; case 7: matrix.setRotate(-90); matrix.postScale(-1,1); break; case 8: matrix.setRotate(-90); break; }
                    if(!matrix.isIdentity()) { Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true); if(rotated!=bitmap) bitmap.recycle(); bitmap=rotated; }
                } catch(IOException ignored) {}
                Bitmap ready=bitmap; handler.post(() -> { if(version==imageGeneration&&!isFinishing()) { displayed=ready; image.setImageBitmap(ready); } else ready.recycle(); });
            } catch(Exception|OutOfMemoryError e) { if(bitmap!=null) bitmap.recycle(); handler.post(() -> { if(version==imageGeneration&&service!=null) service.player.displayError("照片无法解码，请使用 JPEG、PNG 或 WebP，并降低图片尺寸后重试。"); }); }
        });
    }
    private void reveal() { controlsUntil=System.currentTimeMillis()+6000; controlsVisible=true; if(controls!=null) controls.setVisibility(View.VISIBLE); }
    private interface Action { void run() throws Exception; }
    private void act(Action action) { if(service==null) return; try { action.run(); update(); } catch(Exception e) { toast(e.getMessage()==null?"操作失败，请重新选择媒体":e.getMessage()); } }
    private void toggle() { act(() -> { if(service.player.state().state.equals("PLAYING")) service.player.pause(); else service.player.play(); }); }
    private void skip(int delta) { act(() -> service.player.seek(Math.max(0,service.player.state().position+delta))); }
    private void volume(int delta) { act(() -> service.player.volume(Math.max(0,Math.min(100,service.player.state().volume+delta)))); }
    private void toast(String text) { Toast.makeText(this,text,Toast.LENGTH_LONG).show(); }
    private void rename() {
        if(service==null) return; EditText input=new EditText(this); input.setSingleLine(true); input.setText(service.name()); input.setSelectAllOnFocus(true);
        new AlertDialog.Builder(this).setTitle("电视在手机投屏列表中的名称").setView(input).setPositiveButton("保存",(d,w) -> { service.name(input.getText().toString()); update(); }).setNegativeButton("取消",null).show();
    }
    private void clear() { new AlertDialog.Builder(this).setTitle("清理接收缓存").setMessage("将结束当前投屏，并删除通过浏览器发送的文件。").setPositiveButton("清理",(d,w) -> act(() -> service.clearLibrary())).setNegativeButton("取消",null).show(); }
    private void help() {
        new AlertDialog.Builder(this).setTitle("只装电视端，手机直接投")
            .setMessage("视频 App：手机和电视连接同一路由器，打开支持 DLNA 的视频 App，点 TV / 投屏，选择本机名称。\n\n本地照片、视频、音乐：手机浏览器扫描二维码，或输入电视显示的网址和连接码，选择文件发送，无需安装手机 App。\n\n遥控器：确认键播放/暂停；播放中隐藏控制条后，左右键快退/快进，上下键调整音量；返回键结束投屏。\n\n找不到电视：打开 DLNA；关闭路由器的访客网络和 AP 隔离；退出其他占用发现端口的投屏软件。\n\n兼容说明：仅支持标准 DLNA 与浏览器本地媒体；不包含 AirPlay 镜像、乐播私有协议或会员内容授权。不保证每个视频 App 都能发现。视频格式取决于电视硬件，建议 H.264 + AAC / MP4。\n\n局域网中的 DLNA 设备可直接控制播放；不需要时请关闭 DLNA 或退出接收。")
            .setPositiveButton("知道了",null).show();
    }
    private void exit() { new AlertDialog.Builder(this).setTitle("退出投屏接收？").setMessage("退出后手机将无法发现或控制此电视。").setPositiveButton("退出",(d,w) -> { stopService(new Intent(this,ReceiverService.class)); finish(); }).setNegativeButton("取消",null).show(); }
    @Override public void onBackPressed() { if(showing) act(() -> service.player.clear()); else exit(); }
    @Override public boolean onKeyDown(int key,android.view.KeyEvent event) {
        if(showing) {
            if(key==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { toggle(); reveal(); return true; }
            if(key==KeyEvent.KEYCODE_MEDIA_PLAY) { act(() -> service.player.play()); reveal(); return true; }
            if(key==KeyEvent.KEYCODE_MEDIA_PAUSE) { act(() -> service.player.pause()); reveal(); return true; }
            if(key==KeyEvent.KEYCODE_MEDIA_STOP) { act(() -> service.player.clear()); return true; }
            if(!controlsVisible) {
                if(key==KeyEvent.KEYCODE_DPAD_CENTER||key==KeyEvent.KEYCODE_ENTER) { toggle(); reveal(); pause.requestFocus(); return true; }
                if(key==KeyEvent.KEYCODE_DPAD_LEFT) { skip(-10000); reveal(); return true; }
                if(key==KeyEvent.KEYCODE_DPAD_RIGHT) { skip(10000); reveal(); return true; }
                if(key==KeyEvent.KEYCODE_DPAD_UP) { volume(10); reveal(); return true; }
                if(key==KeyEvent.KEYCODE_DPAD_DOWN) { volume(-10); reveal(); return true; }
            }
            reveal();
        }
        return super.onKeyDown(key,event);
    }
    @Override public void surfaceCreated(SurfaceHolder holder) { if(service!=null) service.player.surface(holder.getSurface()); }
    @Override public void surfaceChanged(SurfaceHolder holder,int format,int width,int height) { if(service!=null) service.player.surface(holder.getSurface()); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { if(service!=null) service.player.surface(null); }
    private final class HomeView extends View {
        private final Paint p=new Paint(Paint.ANTI_ALIAS_FLAG);
        private String qrLink="";
        private boolean[][] qr;
        HomeView() { super(MainActivity.this); setLayerType(View.LAYER_TYPE_SOFTWARE,null); }
        private void text(Canvas c,String value,float x,float y,float size,int color,boolean bold) { p.setShader(null); p.setColor(color); p.setTextSize(size); p.setTypeface(bold?Typeface.create("sans-serif",Typeface.BOLD):Typeface.create("sans-serif",Typeface.NORMAL)); c.drawText(value,x,y,p); }
        private void box(Canvas c,float x,float y,float w,float h,int color,float r) { p.setShader(null); p.setColor(color); c.drawRoundRect(x,y,x+w,y+h,r,r,p); }
        @Override protected void onDraw(Canvas c) {
            super.onDraw(c); float scale=Math.min(getWidth()/1280f,getHeight()/720f); c.save(); c.translate((getWidth()-1280*scale)/2,(getHeight()-720*scale)/2); c.scale(scale,scale);
            p.setShader(new LinearGradient(0,0,1280,720,new int[]{0xFF101C32,0xFF0B1020},null,Shader.TileMode.CLAMP)); c.drawRect(0,0,1280,720,p); p.setShader(null);
            box(c,62,45,40,30,GREEN,7); box(c,67,50,30,20,BG,3); box(c,79,75,7,7,GREEN,2);
            text(c,"轻投屏",118,73,30,INK,true); text(c,"让分享，回到大屏",253,72,17,0xFF8495AB,false);
            box(c,1054,43,164,38,0xFF172B36,19); text(c,"●  ANDROID 5.1+",1070,68,15,GREEN,true);
            text(c,"手机里的精彩",68,205,60,INK,true); text(c,"一起放到大屏",68,281,60,GREEN,true);
            text(c,"视频 App 点一下 TV，选择这台电视即可投屏。",72,330,22,0xFFADBBD0,false);
            String[] heads={"01  连接同一网络","02  打开视频 App","03  选择电视名称"};
            String[] subs={"手机和电视在同一路由器下","点视频播放页的 TV / 投屏","支持标准 DLNA 协议的 App"};
            for(int i=0;i<3;i++) { float x=72+i*235; box(c,x,382,219,96,0xFF172237,13); text(c,heads[i],x+16,416,18,INK,true); text(c,subs[i],x+16,450,14,0xFF8FA1B9,false); }
            String name=service==null?"服务启动中…":service.name(); if(name.length()>22) name=name.substring(0,22)+"…";
            text(c,"设备名称",74,529,15,0xFF8194AD,false); text(c,name,74,568,30,INK,true);
            boolean ready=service!=null&&service.dlna!=null&&service.dlna.enabled()&&service.dlna.error().isEmpty();
            String status=service==null?"正在启动":!service.startupError.isEmpty()?service.startupError:service.dlna==null?"接收服务不可用":!service.dlna.enabled()?"DLNA 已关闭":service.dlna.error().isEmpty()?"DLNA 接收已开启":service.dlna.error();
            text(c,(ready?"●  ":"○  ")+status,72,602,15,ready?GREEN:0xFFFFC68D,false);
            box(c,828,133,386,460,0xFF18243A,24); text(c,"本地照片 / 视频 / 音乐",859,176,23,INK,true); text(c,"安卓和 iPhone 均无需安装 App",861,204,15,0xFF9DAEC5,false);
            String base=service==null?"":service.base(); String link=base.isEmpty()?"":base+"/#pin="+service.pin();
            if(!link.equals(qrLink)) { qrLink=link; qr=null; if(!link.isEmpty()) try { qr=QrCode.encode(link); } catch(IllegalArgumentException ignored) {} }
            box(c,902,226,238,238,Color.WHITE,12);
            if(qr!=null) { p.setColor(BG); float unit=5.5f; for(int y=0;y<QrCode.SIZE;y++) for(int x=0;x<QrCode.SIZE;x++) if(qr[y][x]) c.drawRect(930+x*unit,254+y*unit,930+(x+1)*unit,254+(y+1)*unit,p); }
            else text(c,"请连接 Wi-Fi 或网线",927,347,18,BG,true);
            text(c,"浏览器连接码",863,503,15,0xFF9DAEC5,false); text(c,service==null?"------":service.pin(),1020,508,29,GREEN,true);
            text(c,base.isEmpty()?"网络就绪后自动显示地址":base,858,547,19,INK,true);
            text(c,"扫码或输入网址 · 连接后选择本地媒体",858,576,15,0xFF8FA1B9,false);
            c.restore();
        }
    }
}
