package com.lancast.tv;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** UPnP MediaRenderer v1: SSDP, AVTransport, RenderingControl, ConnectionManager, GENA. */
public final class Dlna implements Closeable {
    public interface Identity { String name(); }
    private static final String AV="urn:schemas-upnp-org:service:AVTransport:1";
    private static final String RC="urn:schemas-upnp-org:service:RenderingControl:1";
    private static final String CM="urn:schemas-upnp-org:service:ConnectionManager:1";
    private static final String DEVICE="urn:schemas-upnp-org:device:MediaRenderer:1";
    private static final String PROTOCOL="http-get:*:video/mp4:*,http-get:*:video/mpeg:*,http-get:*:video/3gpp:*,http-get:*:audio/mpeg:*,http-get:*:audio/mp4:*,http-get:*:audio/aac:*,http-get:*:audio/x-wav:*,http-get:*:audio/ogg:*,http-get:*:image/jpeg:*,http-get:*:image/png:*,http-get:*:image/webp:*";
    private final String uuid;
    private final int port;
    private final Renderer player;
    private final Identity identity;
    private final ScheduledThreadPoolExecutor timer = new ScheduledThreadPoolExecutor(2, r -> { Thread t = new Thread(r,"lancast-dlna"); t.setDaemon(true); return t; });
    private final ThreadPoolExecutor notifications = new ThreadPoolExecutor(2,2,30,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(24),r -> { Thread t=new Thread(r,"lancast-events"); t.setDaemon(true); return t; });
    private final Map<String, Subscription> subscriptions = new ConcurrentHashMap<>();
    private volatile MulticastSocket socket;
    private volatile boolean closed;
    private volatile boolean enabled = true;
    private volatile String error = "";
    private String networkSignature="";
    private static final class Subscription {
        String sid, service; URL callback; InetAddress peer; long seq;
        volatile long expires;
        volatile boolean initialized;
        volatile String deliveredKey="";
        final AtomicBoolean inFlight=new AtomicBoolean();
    }
    private static final class Fault extends Exception {
        final int code;
        Fault(int code, String text) { super(text); this.code=code; }
    }
    public Dlna(String uuid, int port, Renderer player, Identity identity) { this.uuid=uuid; this.port=port; this.player=player; this.identity=identity; }
    public void start() {
        start(true);
    }
    void start(boolean discovery) {
        if(discovery) timer.scheduleWithFixedDelay(this::maintain,0,20,TimeUnit.SECONDS);
        timer.scheduleWithFixedDelay(this::events,1,1,TimeUnit.SECONDS);
    }
    public String error() { return error; }
    public boolean enabled() { return enabled; }
    public void enabled(boolean value) {
        if (enabled == value) return;
        if (!value) announce("ssdp:byebye");
        enabled=value; subscriptions.clear();
        if (value) timer.execute(this::maintain);
    }
    private void maintain() {
        if (closed || !enabled) return;
        String signature=Net.interfaces().toString();
        if(!signature.equals(networkSignature)) { MulticastSocket old=socket; socket=null; if(old!=null) old.close(); networkSignature=signature; }
        if (socket == null) try {
            MulticastSocket next = new MulticastSocket(null); next.setReuseAddress(true);
            next.bind(new InetSocketAddress(1900)); next.setTimeToLive(2);
            int joined=0;
            for (NetworkInterface nic : Net.interfaces().keySet()) try { next.joinGroup(new InetSocketAddress("239.255.255.250",1900),nic); joined++; } catch (IOException ignored) {}
            if (joined == 0) { next.close(); error="尚未连接局域网，DLNA 将自动重试"; return; }
            socket=next; error="";
            Thread listener = new Thread(this::listen,"lancast-ssdp"); listener.setDaemon(true); listener.start();
        } catch (IOException e) { error="DLNA 发现端口不可用，请退出电视上其他投屏软件后重试"; return; }
        announce("ssdp:alive");
    }
    private List<String> targets() { return Arrays.asList("upnp:rootdevice", "uuid:"+uuid, DEVICE, AV, RC, CM); }
    private String usn(String target) { return "uuid:"+uuid+(target.equals("uuid:"+uuid)?"":"::"+target); }
    private String url(String ip) { return "http://"+ip+":"+port; }
    private void listen() {
        MulticastSocket listening=socket;
        while (!closed && listening == socket) try {
            byte[] buffer=new byte[8192]; DatagramPacket packet=new DatagramPacket(buffer,buffer.length); listening.receive(packet);
            if (!enabled || !Net.local(packet.getAddress()) || packet.getLength() == buffer.length) continue;
            String text=new String(packet.getData(),0,packet.getLength(),StandardCharsets.US_ASCII);
            if (!text.startsWith("M-SEARCH * HTTP/1.1\r\n")) continue;
            Map<String,String> headers=new HashMap<>();
            for (String line:text.split("\r\n")) { int colon=line.indexOf(':'); if(colon>0) headers.put(line.substring(0,colon).toLowerCase(Locale.US),line.substring(colon+1).trim()); }
            if (!"\"ssdp:discover\"".equalsIgnoreCase(headers.get("man"))) continue;
            String st=headers.get("st"); if(st==null) continue;
            int mx; try { mx=Math.max(1,Math.min(3,Integer.parseInt(headers.get("mx")))); } catch(Exception e) { mx=1; }
            for (String target:targets()) if(st.equals("ssdp:all") || st.equals(target)) {
                InetAddress peer=packet.getAddress(); int remotePort=packet.getPort();
                if (timer.getQueue().size()<64) timer.schedule(() -> respond(target,peer,remotePort),ThreadLocalRandom.current().nextInt(mx*1000),TimeUnit.MILLISECONDS);
            }
        } catch(IOException e) { if (!closed && socket == listening) { socket=null; listening.close(); } break; }
    }
    private void respond(String target, InetAddress peer, int remotePort) {
        if (!enabled || closed) return;
        try (DatagramSocket reply=new DatagramSocket()) {
            reply.connect(peer,remotePort); String ip=reply.getLocalAddress().getHostAddress();
            String message="HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=60\r\nEXT:\r\nLOCATION: "+url(ip)+"/dlna/device.xml\r\nSERVER: Android/5.1 UPnP/1.0 LanCast/0.1\r\nST: "+target+"\r\nUSN: "+usn(target)+"\r\n\r\n";
            byte[] bytes=message.getBytes(StandardCharsets.UTF_8); reply.send(new DatagramPacket(bytes,bytes.length));
        } catch(IOException ignored) {}
    }
    public void announce(String nts) {
        MulticastSocket current=socket; if(current==null || closed) return;
        synchronized(this) {
            for(Map.Entry<NetworkInterface,Inet4Address> entry:Net.interfaces().entrySet()) try {
                current.setNetworkInterface(entry.getKey());
                for(String target:targets()) {
                    String text="NOTIFY * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nCACHE-CONTROL: max-age=60\r\nLOCATION: "+url(entry.getValue().getHostAddress())+"/dlna/device.xml\r\nNT: "+target+"\r\nNTS: "+nts+"\r\nSERVER: Android/5.1 UPnP/1.0 LanCast/0.1\r\nUSN: "+usn(target)+"\r\n\r\n";
                    byte[] bytes=text.getBytes(StandardCharsets.UTF_8); current.send(new DatagramPacket(bytes,bytes.length,InetAddress.getByName("239.255.255.250"),1900));
                }
            } catch(IOException ignored) {}
        }
    }
    public boolean route(HttpWire.Request r,HttpWire.Response out) throws Exception {
        if(!r.path.startsWith("/dlna/")) return false;
        if(!enabled) { out.text(503,"text/plain","DLNA disabled"); return true; }
        if ("/dlna/device.xml".equals(r.path) && (r.method.equals("GET") || r.method.equals("HEAD"))) {
            out.text(200,"text/xml; charset=utf-8",description(url(r.local.getHostAddress()))); return true;
        }
        String[] pieces=r.path.split("/");
        if(pieces.length!=4) { out.text(404,"text/plain","Not found"); return true; }
        String service=pieces[2]; if(!Arrays.asList("av","rc","cm").contains(service)) { out.text(404,"text/plain","Not found"); return true; }
        if("scpd.xml".equals(pieces[3]) && (r.method.equals("GET") || r.method.equals("HEAD"))) out.text(200,"text/xml; charset=utf-8",scpd(service));
        else if("control".equals(pieces[3]) && r.method.equals("POST")) soap(r,out,service);
        else if("event".equals(pieces[3])) subscribe(r,out,service);
        else out.text(405,"text/plain","Method not allowed");
        return true;
    }
    public String description(String base) {
        return "<?xml version=\"1.0\" encoding=\"utf-8\"?><root xmlns=\"urn:schemas-upnp-org:device-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><URLBase>"+Net.xml(base)+"/</URLBase><device><deviceType>"+DEVICE+"</deviceType><friendlyName>"+Net.xml(identity.name())+"</friendlyName><manufacturer>LanCast</manufacturer><modelDescription>Local network media receiver</modelDescription><modelName>LanCast TV</modelName><modelNumber>0.1.0</modelNumber><UDN>uuid:"+uuid+"</UDN><serviceList>"+service("av",AV,"AVTransport")+service("rc",RC,"RenderingControl")+service("cm",CM,"ConnectionManager")+"</serviceList><presentationURL>"+Net.xml(base)+"/</presentationURL></device></root>";
    }
    private String service(String id,String type,String name) {
        return "<service><serviceType>"+type+"</serviceType><serviceId>urn:upnp-org:serviceId:"+name+"</serviceId><SCPDURL>/dlna/"+id+"/scpd.xml</SCPDURL><controlURL>/dlna/"+id+"/control</controlURL><eventSubURL>/dlna/"+id+"/event</eventSubURL></service>";
    }
    public static Map<String,String> parse(String xml) throws Exception {
        String upper=xml.toUpperCase(Locale.US);
        if(upper.contains("<!DOCTYPE") || upper.contains("<!ENTITY")) throw new Fault(402,"Invalid Args");
        DocumentBuilderFactory factory=DocumentBuilderFactory.newInstance(); factory.setNamespaceAware(true); factory.setExpandEntityReferences(false);
        Document doc=factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
        NodeList bodies=doc.getElementsByTagNameNS("http://schemas.xmlsoap.org/soap/envelope/","Body");
        if(bodies.getLength()!=1) throw new Fault(402,"Invalid Args");
        Map<String,String> result=new LinkedHashMap<>();
        for(Node action=bodies.item(0).getFirstChild();action!=null;action=action.getNextSibling()) if(action instanceof Element) {
            result.put("_action",action.getLocalName()); result.put("_namespace",action.getNamespaceURI());
            for(Node n=action.getFirstChild();n!=null;n=n.getNextSibling()) if(n instanceof Element) result.put(n.getLocalName(),n.getTextContent());
            break;
        }
        if(!result.containsKey("_action")) throw new Fault(402,"Invalid Args");
        return result;
    }
    private void soap(HttpWire.Request request,HttpWire.Response out,String service) throws Exception {
        String type=service.equals("av")?AV:service.equals("rc")?RC:CM;
        try {
            Map<String,String> args=parse(request.text(65536)); String action=args.get("_action");
            if(!type.equals(args.get("_namespace"))) throw new Fault(401,"Invalid Action");
            if(args.containsKey("InstanceID") && !"0".equals(args.get("InstanceID"))) throw new Fault(718,"Invalid InstanceID");
            if(args.containsKey("Channel") && !"Master".equals(args.get("Channel"))) throw new Fault(402,"Invalid Args");
            Renderer.State s=player.state(); String body;
            switch(service+":"+action) {
                case "av:SetAVTransportURI": {
                    String uri=required(args,"CurrentURI"), metadata=args.get("CurrentURIMetaData");
                    String title=uri, mime="";
                    if(metadata!=null && !metadata.isEmpty()) {
                        String[] meta=metadata(metadata); if(!meta[0].isEmpty()) title=meta[0]; mime=meta[1];
                    }
                    try { player.load(uri,title,mime,metadata==null?"":metadata); } catch(IllegalArgumentException e) { throw new Fault(714,"Illegal MIME-type or URI"); }
                    body=""; break;
                }
                case "av:Play": if(!"1".equals(required(args,"Speed"))) throw new Fault(717,"Play speed not supported"); player.play(); body=""; break;
                case "av:Pause": player.pause(); body=""; break;
                case "av:Stop": player.stop(); body=""; break;
                case "av:Seek": if(!"REL_TIME".equals(required(args,"Unit")) && !"ABS_TIME".equals(args.get("Unit"))) throw new Fault(710,"Seek mode not supported"); player.seek(parseTime(required(args,"Target"))); body=""; break;
                case "av:GetTransportInfo": body=tag("CurrentTransportState",s.state)+tag("CurrentTransportStatus",s.error.isEmpty()?"OK":"ERROR_OCCURRED")+tag("CurrentSpeed","1"); break;
                case "av:GetPositionInfo": body=tag("Track",s.uri.isEmpty()?"0":"1")+tag("TrackDuration",time(s.duration))+tag("TrackMetaData",s.metadata)+tag("TrackURI",s.uri)+tag("RelTime",time(s.position))+tag("AbsTime",time(s.position))+tag("RelCount","2147483647")+tag("AbsCount","2147483647"); break;
                case "av:GetMediaInfo": body=tag("NrTracks",s.uri.isEmpty()?"0":"1")+tag("MediaDuration",time(s.duration))+tag("CurrentURI",s.uri)+tag("CurrentURIMetaData",s.metadata)+tag("NextURI","")+tag("NextURIMetaData","")+tag("PlayMedium",s.uri.isEmpty()?"NONE":"NETWORK")+tag("RecordMedium","NOT_IMPLEMENTED")+tag("WriteStatus","NOT_IMPLEMENTED"); break;
                case "av:GetDeviceCapabilities": body=tag("PlayMedia","NETWORK")+tag("RecMedia","NOT_IMPLEMENTED")+tag("RecQualityModes","NOT_IMPLEMENTED"); break;
                case "av:GetTransportSettings": body=tag("PlayMode","NORMAL")+tag("RecQualityMode","NOT_IMPLEMENTED"); break;
                case "av:GetCurrentTransportActions": body=tag("Actions",s.uri.isEmpty()?"":"Play,Pause,Stop,Seek"); break;
                case "rc:GetVolume": body=tag("CurrentVolume",Integer.toString(s.volume)); break;
                case "rc:SetVolume": player.volume(number(required(args,"DesiredVolume"),0,100)); body=""; break;
                case "rc:GetMute": body=tag("CurrentMute",s.muted?"1":"0"); break;
                case "rc:SetMute": { String value=required(args,"DesiredMute"); if(!Arrays.asList("0","1","true","false").contains(value)) throw new Fault(402,"Invalid Args"); player.mute(value.equals("1")||value.equals("true")); body=""; break; }
                case "rc:ListPresets": body=tag("CurrentPresetNameList","FactoryDefaults"); break;
                case "rc:SelectPreset": if(!"FactoryDefaults".equals(required(args,"PresetName"))) throw new Fault(701,"Invalid Name"); player.volume(70); player.mute(false); body=""; break;
                case "cm:GetProtocolInfo": body=tag("Source","")+tag("Sink",PROTOCOL); break;
                case "cm:GetCurrentConnectionIDs": body=tag("ConnectionIDs","0"); break;
                case "cm:GetCurrentConnectionInfo": if(!"0".equals(required(args,"ConnectionID"))) throw new Fault(706,"Invalid connection reference"); body=tag("RcsID","0")+tag("AVTransportID","0")+tag("ProtocolInfo","http-get:*:"+(s.mime.isEmpty()?"video/mp4":s.mime)+":*")+tag("PeerConnectionManager","")+tag("PeerConnectionID","-1")+tag("Direction","Input")+tag("Status","OK"); break;
                default: throw new Fault(401,"Invalid Action");
            }
            String xml="<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body><u:"+action+"Response xmlns:u=\""+type+"\">"+body+"</u:"+action+"Response></s:Body></s:Envelope>";
            out.text(200,"text/xml; charset=utf-8",xml);
        } catch(Exception e) {
            int code=e instanceof Fault?((Fault)e).code:501; String message=e instanceof Fault?e.getMessage():"Action Failed";
            out.text(500,"text/xml; charset=utf-8","<?xml version=\"1.0\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><faultcode>s:Client</faultcode><faultstring>UPnPError</faultstring><detail><UPnPError xmlns=\"urn:schemas-upnp-org:control-1-0\"><errorCode>"+code+"</errorCode><errorDescription>"+Net.xml(message)+"</errorDescription></UPnPError></detail></s:Fault></s:Body></s:Envelope>");
        }
    }
    private static String[] metadata(String xml) {
        if(xml.length()>60000 || xml.toUpperCase(Locale.US).contains("<!DOCTYPE") || xml.toUpperCase(Locale.US).contains("<!ENTITY")) return new String[]{"",""};
        try {
            DocumentBuilderFactory f=DocumentBuilderFactory.newInstance(); f.setNamespaceAware(true); f.setExpandEntityReferences(false);
            Document d=f.newDocumentBuilder().parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));
            NodeList titles=d.getElementsByTagNameNS("*","title"), resources=d.getElementsByTagNameNS("*","res");
            String mime=""; if(resources.getLength()>0) { String[] protocol=((Element)resources.item(0)).getAttribute("protocolInfo").split(":",4); if(protocol.length==4) mime=protocol[2]; }
            return new String[]{titles.getLength()>0?titles.item(0).getTextContent():"",mime};
        } catch(Exception e) { return new String[]{"",""}; }
    }
    private static String required(Map<String,String> args,String name) throws Fault { if(!args.containsKey(name)) throw new Fault(402,"Invalid Args"); return args.get(name); }
    private static int number(String value,int min,int max) throws Fault { try { int n=Integer.parseInt(value); if(n<min||n>max) throw new NumberFormatException(); return n; } catch(NumberFormatException e) { throw new Fault(402,"Invalid Args"); } }
    public static int parseTime(String text) throws Exception {
        String[] parts=text.split(":"); if(parts.length!=3) throw new Fault(711,"Illegal seek target");
        try { int h=Integer.parseInt(parts[0]), m=Integer.parseInt(parts[1]); double s=Double.parseDouble(parts[2]); double ms=(h*3600d+m*60d+s)*1000d; if(h<0||m<0||m>59||s<0||s>=60||Double.isNaN(s)||ms>Integer.MAX_VALUE) throw new NumberFormatException(); return (int)ms; }
        catch(NumberFormatException e) { throw new Fault(711,"Illegal seek target"); }
    }
    public static String time(int ms) { long seconds=Math.max(0,ms)/1000; return String.format(Locale.US,"%02d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60); }
    private static String tag(String name,String value) { return "<"+name+">"+Net.xml(value)+"</"+name+">"; }
    private void subscribe(HttpWire.Request r,HttpWire.Response out,String service) throws Exception {
        String sid=r.header("sid"); Subscription sub=sid==null?null:subscriptions.get(sid);
        if(r.method.equals("UNSUBSCRIBE")) {
            if(sub==null || !sub.service.equals(service)) { out.text(412,"text/plain","Invalid SID"); return; }
            subscriptions.remove(sid); out.bytes(200,"text/plain",new byte[0],Collections.emptyMap()); return;
        }
        if(!r.method.equals("SUBSCRIBE")) { out.text(405,"text/plain","Method not allowed"); return; }
        int seconds=300; String timeout=r.header("timeout");
        if(timeout!=null && timeout.startsWith("Second-")) try { seconds=Math.max(30,Math.min(1800,Integer.parseInt(timeout.substring(7)))); } catch(NumberFormatException ignored) {}
        if(sid==null) {
            String callback=r.header("callback");
            if(!"upnp:event".equalsIgnoreCase(r.header("nt")) || callback==null || !callback.startsWith("<") || !callback.endsWith(">") || subscriptions.size()>=24) { out.text(412,"text/plain","Invalid subscription"); return; }
            URL destination;
            try {
                URI parsed=new URI(callback.substring(1,callback.length()-1));
                destination=parsed.toURL();
                if(!destination.getProtocol().equals("http") || destination.getUserInfo()!=null || parsed.getFragment()!=null || destination.getPort()==0 || destination.getPort()>65535 || !InetAddress.getByName(destination.getHost()).equals(r.peer)) throw new IOException();
            } catch(Exception e) { out.text(412,"text/plain","Callback must belong to sender"); return; }
            sub=new Subscription(); sub.sid="uuid:"+UUID.randomUUID(); sub.service=service; sub.callback=destination; sub.peer=r.peer; sub.expires=System.currentTimeMillis()+seconds*1000L;
            subscriptions.put(sub.sid,sub);
        } else if(sub==null || !sub.service.equals(service) || r.header("callback")!=null || r.header("nt")!=null) { out.text(412,"text/plain","Invalid SID"); return; }
        sub.expires=System.currentTimeMillis()+seconds*1000L;
        Map<String,String> headers=new LinkedHashMap<>(); headers.put("SID",sub.sid); headers.put("TIMEOUT","Second-"+seconds);
        out.bytes(200,"text/plain",new byte[0],headers);
        Subscription target=sub; if(!sub.initialized) timer.schedule(() -> dispatchEvent(target,player.state()),100,TimeUnit.MILLISECONDS);
    }
    private void events() {
        if(closed || !enabled || subscriptions.isEmpty()) return;
        try {
            Renderer.State state=player.state();
            String key=eventKey(state);
            for(Subscription s:subscriptions.values()) {
                if(s.expires<System.currentTimeMillis()) { subscriptions.remove(s.sid); continue; }
                if(!s.initialized || !key.equals(s.deliveredKey)) dispatchEvent(s,state);
            }
        } catch(Exception ignored) {}
    }
    private static String eventKey(Renderer.State s) {
        return s.state+'\n'+s.uri+'\n'+s.metadata+'\n'+s.duration+'\n'+s.volume+'\n'+s.muted+'\n'+s.error;
    }
    private void dispatchEvent(Subscription sub,Renderer.State state) {
        if(!sub.inFlight.compareAndSet(false,true)) return;
        try { notifications.execute(() -> { try { event(sub,state); } finally { sub.inFlight.set(false); } }); }
        catch(RejectedExecutionException e) { sub.inFlight.set(false); }
    }
    private void event(Subscription sub,Renderer.State s) {
        if(closed || !enabled || !subscriptions.containsKey(sub.sid)) return;
        String property;
        if(sub.service.equals("cm")) property=tag("SourceProtocolInfo","")+tag("SinkProtocolInfo",PROTOCOL)+tag("CurrentConnectionIDs","0");
        else {
            String ns=sub.service.equals("av")?"AVT":"RCS";
            String data=sub.service.equals("av") ? "<TransportState val=\""+s.state+"\"/><TransportStatus val=\""+(s.error.isEmpty()?"OK":"ERROR_OCCURRED")+"\"/><AVTransportURI val=\""+Net.xml(s.uri)+"\"/><AVTransportURIMetaData val=\""+Net.xml(s.metadata)+"\"/><CurrentTrackURI val=\""+Net.xml(s.uri)+"\"/><CurrentTrackDuration val=\""+time(s.duration)+"\"/><CurrentTransportActions val=\""+(s.uri.isEmpty()?"":"Play,Pause,Stop,Seek")+"\"/>" : "<Volume channel=\"Master\" val=\""+s.volume+"\"/><Mute channel=\"Master\" val=\""+(s.muted?1:0)+"\"/>";
            property=tag("LastChange","<Event xmlns=\"urn:schemas-upnp-org:metadata-1-0/"+ns+"/\"><InstanceID val=\"0\">"+data+"</InstanceID></Event>");
        }
        String payload="<?xml version=\"1.0\"?><e:propertyset xmlns:e=\"urn:schemas-upnp-org:event-1-0\">";
        if(sub.service.equals("cm")) payload+="<e:property>"+tag("SourceProtocolInfo","")+"</e:property><e:property>"+tag("SinkProtocolInfo",PROTOCOL)+"</e:property><e:property>"+tag("CurrentConnectionIDs","0")+"</e:property>";
        else payload+="<e:property>"+property+"</e:property>";
        payload+="</e:propertyset>";
        try {
            // A raw socket supports NOTIFY on Android versions whose HttpURLConnection rejects that method.
            URL u=sub.callback; int remotePort=u.getPort()<0?80:u.getPort();
            try(Socket connection=new Socket()) {
                // Pin the validated sender IP; never resolve a callback hostname again.
                connection.connect(new InetSocketAddress(sub.peer,remotePort),1500); connection.setSoTimeout(1500);
                byte[] body=payload.getBytes(StandardCharsets.UTF_8);
                String path=u.getFile().isEmpty()?"/":u.getFile();
                String header="NOTIFY "+path+" HTTP/1.1\r\nHOST: "+u.getHost()+":"+remotePort+"\r\nCONTENT-TYPE: text/xml; charset=utf-8\r\nCONTENT-LENGTH: "+body.length+"\r\nNT: upnp:event\r\nNTS: upnp:propchange\r\nSID: "+sub.sid+"\r\nSEQ: "+(sub.seq++ & 0xffffffffL)+"\r\nConnection: close\r\n\r\n";
                connection.getOutputStream().write(header.getBytes(StandardCharsets.UTF_8)); connection.getOutputStream().write(body); connection.getOutputStream().flush();
                String status=HttpWire.line(connection.getInputStream(),512);
                if(!status.matches("HTTP/1\\.[01] 2[0-9][0-9]( .*)?")) throw new IOException("Event rejected");
                sub.deliveredKey=eventKey(s); sub.initialized=true;
            }
        } catch(IOException ignored) {}
    }
    public String scpd(String service) {
        Schema schema=new Schema();
        if(service.equals("av")) {
            schema.var("A_ARG_TYPE_InstanceID","ui4",false,"0"); schema.var("AVTransportURI","uri",false,""); schema.var("AVTransportURIMetaData","string",false,"");
            schema.var("TransportState","string",false,"NO_MEDIA_PRESENT","STOPPED","PLAYING","TRANSITIONING","PAUSED_PLAYBACK","NO_MEDIA_PRESENT");
            schema.var("TransportStatus","string",false,"OK","OK","ERROR_OCCURRED"); schema.var("TransportPlaySpeed","string",false,"1","1");
            schema.var("A_ARG_TYPE_SeekMode","string",false,"REL_TIME","REL_TIME","ABS_TIME"); schema.var("A_ARG_TYPE_SeekTarget","string",false,"");
            schema.var("CurrentTrack","ui4",false,"0"); schema.var("NumberOfTracks","ui4",false,"0"); schema.var("CurrentTrackDuration","string",false,""); schema.var("CurrentMediaDuration","string",false,""); schema.var("CurrentTrackMetaData","string",false,""); schema.var("CurrentTrackURI","uri",false,""); schema.var("RelativeTimePosition","string",false,""); schema.var("AbsoluteTimePosition","string",false,""); schema.var("RelativeCounterPosition","i4",false,"2147483647"); schema.var("AbsoluteCounterPosition","i4",false,"2147483647");
            schema.var("NextAVTransportURI","uri",false,""); schema.var("NextAVTransportURIMetaData","string",false,""); schema.var("PlaybackStorageMedium","string",false,"NETWORK","NONE","NETWORK"); schema.var("RecordStorageMedium","string",false,"NOT_IMPLEMENTED"); schema.var("RecordMediumWriteStatus","string",false,"NOT_IMPLEMENTED"); schema.var("PossiblePlaybackStorageMedia","string",false,"NETWORK"); schema.var("PossibleRecordStorageMedia","string",false,"NOT_IMPLEMENTED"); schema.var("PossibleRecordQualityModes","string",false,"NOT_IMPLEMENTED"); schema.var("CurrentPlayMode","string",false,"NORMAL","NORMAL"); schema.var("CurrentRecordQualityMode","string",false,"NOT_IMPLEMENTED"); schema.var("CurrentTransportActions","string",false,""); schema.var("LastChange","string",true,"");
            schema.action("SetAVTransportURI","InstanceID:i:A_ARG_TYPE_InstanceID","CurrentURI:i:AVTransportURI","CurrentURIMetaData:i:AVTransportURIMetaData");
            schema.action("Play","InstanceID:i:A_ARG_TYPE_InstanceID","Speed:i:TransportPlaySpeed"); schema.action("Pause","InstanceID:i:A_ARG_TYPE_InstanceID"); schema.action("Stop","InstanceID:i:A_ARG_TYPE_InstanceID"); schema.action("Seek","InstanceID:i:A_ARG_TYPE_InstanceID","Unit:i:A_ARG_TYPE_SeekMode","Target:i:A_ARG_TYPE_SeekTarget");
            schema.action("GetTransportInfo","InstanceID:i:A_ARG_TYPE_InstanceID","CurrentTransportState:o:TransportState","CurrentTransportStatus:o:TransportStatus","CurrentSpeed:o:TransportPlaySpeed");
            schema.action("GetPositionInfo","InstanceID:i:A_ARG_TYPE_InstanceID","Track:o:CurrentTrack","TrackDuration:o:CurrentTrackDuration","TrackMetaData:o:CurrentTrackMetaData","TrackURI:o:CurrentTrackURI","RelTime:o:RelativeTimePosition","AbsTime:o:AbsoluteTimePosition","RelCount:o:RelativeCounterPosition","AbsCount:o:AbsoluteCounterPosition");
            schema.action("GetMediaInfo","InstanceID:i:A_ARG_TYPE_InstanceID","NrTracks:o:NumberOfTracks","MediaDuration:o:CurrentMediaDuration","CurrentURI:o:AVTransportURI","CurrentURIMetaData:o:AVTransportURIMetaData","NextURI:o:NextAVTransportURI","NextURIMetaData:o:NextAVTransportURIMetaData","PlayMedium:o:PlaybackStorageMedium","RecordMedium:o:RecordStorageMedium","WriteStatus:o:RecordMediumWriteStatus");
            schema.action("GetDeviceCapabilities","InstanceID:i:A_ARG_TYPE_InstanceID","PlayMedia:o:PossiblePlaybackStorageMedia","RecMedia:o:PossibleRecordStorageMedia","RecQualityModes:o:PossibleRecordQualityModes");
            schema.action("GetTransportSettings","InstanceID:i:A_ARG_TYPE_InstanceID","PlayMode:o:CurrentPlayMode","RecQualityMode:o:CurrentRecordQualityMode");
            schema.action("GetCurrentTransportActions","InstanceID:i:A_ARG_TYPE_InstanceID","Actions:o:CurrentTransportActions");
        } else if(service.equals("rc")) {
            schema.var("A_ARG_TYPE_InstanceID","ui4",false,"0"); schema.var("A_ARG_TYPE_Channel","string",false,"Master","Master"); schema.var("Volume","ui2",false,"70"); schema.var("Mute","boolean",false,"0"); schema.var("PresetNameList","string",false,"FactoryDefaults"); schema.var("A_ARG_TYPE_PresetName","string",false,"FactoryDefaults","FactoryDefaults"); schema.var("LastChange","string",true,"");
            schema.action("GetVolume","InstanceID:i:A_ARG_TYPE_InstanceID","Channel:i:A_ARG_TYPE_Channel","CurrentVolume:o:Volume"); schema.action("SetVolume","InstanceID:i:A_ARG_TYPE_InstanceID","Channel:i:A_ARG_TYPE_Channel","DesiredVolume:i:Volume"); schema.action("GetMute","InstanceID:i:A_ARG_TYPE_InstanceID","Channel:i:A_ARG_TYPE_Channel","CurrentMute:o:Mute"); schema.action("SetMute","InstanceID:i:A_ARG_TYPE_InstanceID","Channel:i:A_ARG_TYPE_Channel","DesiredMute:i:Mute"); schema.action("ListPresets","InstanceID:i:A_ARG_TYPE_InstanceID","CurrentPresetNameList:o:PresetNameList"); schema.action("SelectPreset","InstanceID:i:A_ARG_TYPE_InstanceID","PresetName:i:A_ARG_TYPE_PresetName");
        } else {
            schema.var("SourceProtocolInfo","string",true,""); schema.var("SinkProtocolInfo","string",true,PROTOCOL); schema.var("CurrentConnectionIDs","string",true,"0"); schema.var("A_ARG_TYPE_ConnectionID","i4",false,"0"); schema.var("A_ARG_TYPE_RcsID","i4",false,"0"); schema.var("A_ARG_TYPE_AVTransportID","i4",false,"0"); schema.var("A_ARG_TYPE_ProtocolInfo","string",false,""); schema.var("A_ARG_TYPE_ConnectionManager","string",false,""); schema.var("A_ARG_TYPE_Direction","string",false,"Input","Input","Output"); schema.var("A_ARG_TYPE_ConnectionStatus","string",false,"OK","OK","ContentFormatMismatch","InsufficientBandwidth","UnreliableChannel","Unknown");
            schema.action("GetProtocolInfo","Source:o:SourceProtocolInfo","Sink:o:SinkProtocolInfo"); schema.action("GetCurrentConnectionIDs","ConnectionIDs:o:CurrentConnectionIDs"); schema.action("GetCurrentConnectionInfo","ConnectionID:i:A_ARG_TYPE_ConnectionID","RcsID:o:A_ARG_TYPE_RcsID","AVTransportID:o:A_ARG_TYPE_AVTransportID","ProtocolInfo:o:A_ARG_TYPE_ProtocolInfo","PeerConnectionManager:o:A_ARG_TYPE_ConnectionManager","PeerConnectionID:o:A_ARG_TYPE_ConnectionID","Direction:o:A_ARG_TYPE_Direction","Status:o:A_ARG_TYPE_ConnectionStatus");
        }
        return "<?xml version=\"1.0\"?><scpd xmlns=\"urn:schemas-upnp-org:service-1-0\"><specVersion><major>1</major><minor>0</minor></specVersion><actionList>"+schema.actions+"</actionList><serviceStateTable>"+schema.variables+"</serviceStateTable></scpd>";
    }
    private static final class Schema {
        final StringBuilder actions=new StringBuilder(),variables=new StringBuilder();
        void var(String name,String type,boolean event,String def,String... allowed) {
            variables.append("<stateVariable sendEvents=\"").append(event?"yes":"no").append("\">").append(tag("name",name)).append(tag("dataType",type));
            if(!def.isEmpty()) variables.append(tag("defaultValue",def));
            if(allowed.length>0) { variables.append("<allowedValueList>"); for(String v:allowed) variables.append(tag("allowedValue",v)); variables.append("</allowedValueList>"); }
            if(name.equals("Volume")) variables.append("<allowedValueRange><minimum>0</minimum><maximum>100</maximum><step>1</step></allowedValueRange>");
            variables.append("</stateVariable>");
        }
        void action(String name,String... args) {
            actions.append("<action>").append(tag("name",name)).append("<argumentList>");
            for(String arg:args) { String[] p=arg.split(":"); actions.append("<argument>").append(tag("name",p[0])).append(tag("direction",p[1].equals("i")?"in":"out")).append(tag("relatedStateVariable",p[2])).append("</argument>"); }
            actions.append("</argumentList></action>");
        }
    }
    @Override public void close() { announce("ssdp:byebye"); closed=true; subscriptions.clear(); timer.shutdownNow(); notifications.shutdownNow(); MulticastSocket current=socket; socket=null; if(current!=null) current.close(); }
}
