package com.lancast.tv;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.*;

/** Exercises real wire protocols against the same server shipped in the APK. No Android mocks. */
public final class ProtocolTest {
    private static int checks;
    private static void check(boolean value,String message) { if(!value) throw new AssertionError(message); checks++; }
    private static final class TestRenderer implements Renderer {
        final State s=new State();
        public synchronized State state() {
            State copy=new State(); copy.state=s.state; copy.uri=s.uri; copy.title=s.title; copy.mime=s.mime;
            copy.metadata=s.metadata; copy.error=s.error; copy.duration=s.duration; copy.position=s.position;
            copy.volume=s.volume; copy.muted=s.muted; return copy;
        }
        public synchronized void load(String uri,String title,String mime,String metadata) { s.uri=uri;s.title=title;s.mime=mime;s.metadata=metadata;s.state="STOPPED";s.duration=240000; }
        public synchronized void play() { s.state="PLAYING"; }
        public synchronized void pause() { s.state="PAUSED_PLAYBACK"; }
        public synchronized void stop() { s.state="STOPPED";s.position=0; }
        public synchronized void seek(int ms) { s.position=ms; }
        public synchronized void volume(int n) { s.volume=n; }
        public synchronized void mute(boolean value) { s.muted=value; }
    }
    private static String request(int port,String method,String path,String headers,String body) throws Exception {
        byte[] bytes=body.getBytes(StandardCharsets.UTF_8);
        try(Socket s=new Socket("127.0.0.1",port)) {
            s.setSoTimeout(5000);
            String request=method+" "+path+" HTTP/1.1\r\nHost: 127.0.0.1:"+port+"\r\nContent-Length: "+bytes.length+"\r\n"+headers+"\r\n";
            s.getOutputStream().write(request.getBytes(StandardCharsets.UTF_8));s.getOutputStream().write(bytes);s.getOutputStream().flush();
            ByteArrayOutputStream response=new ByteArrayOutputStream(); byte[] buffer=new byte[8192];int n;
            while((n=s.getInputStream().read(buffer))>=0)response.write(buffer,0,n);
            return new String(response.toByteArray(),StandardCharsets.UTF_8);
        }
    }
    private static String soap(int port,String service,String action,String args) throws Exception {
        String name=service.equals("av")?"AVTransport":service.equals("rc")?"RenderingControl":"ConnectionManager";
        return request(port,"POST","/dlna/"+service+"/control","Content-Type: text/xml; charset=utf-8\r\nSOAPACTION: \"urn:schemas-upnp-org:service:"+name+":1#"+action+"\"\r\n","<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><u:"+action+" xmlns:u=\"urn:schemas-upnp-org:service:"+name+":1\">"+args+"</u:"+action+"></s:Body></s:Envelope>");
    }
    private static Document xml(String source) throws Exception { DocumentBuilderFactory f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);return f.newDocumentBuilder().parse(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8))); }
    private static String body(String response) { return response.substring(response.indexOf("\r\n\r\n")+4); }
    private static void status(String response,int code,String message) { check(response.startsWith("HTTP/1.1 "+code+" "),message+": "+response); }
    private static void invalid(String raw) throws Exception {
        try { HttpWire.read(new ByteArrayInputStream(raw.getBytes(StandardCharsets.US_ASCII)));throw new AssertionError("Malformed request accepted"); }
        catch(HttpWire.HttpError expected) { checks++; }
    }
    public static void main(String[] args) throws Exception {
        check(Arrays.equals(HttpWire.range("bytes=2-5",10),new long[]{2,5}),"bounded range");
        check(Arrays.equals(HttpWire.range("bytes=-3",10),new long[]{7,9}),"suffix range");
        check(Arrays.equals(HttpWire.range("bytes=8-99",10),new long[]{8,9}),"clamped end");
        check(Arrays.equals(HttpWire.range("bytes=5-",10),new long[]{5,9}),"open-ended range");
        for(String range:new String[]{"bytes=20-","bytes=3-2","bytes=-0","bytes=0-1,3-4","bytes=-999999999999999999999999"}) {
            try { HttpWire.range(range,10);throw new AssertionError(range); } catch(HttpWire.HttpError expected) { checks++; }
        }
        invalid("POST / HTTP/1.1\r\nContent-Length: -1\r\n\r\n");
        invalid("POST / HTTP/1.1\r\nContent-Length: 1\r\nContent-Length: 2\r\n\r\n");
        invalid("POST / HTTP/1.1\r\nTransfer-Encoding: chunked\r\n\r\n");
        invalid("GET http://example.com/ HTTP/1.1\r\n\r\n");
        invalid("GET / HTTP/1.9\r\n\r\n");
        invalid("GET //example.com/path HTTP/1.1\r\n\r\n");
        invalid("GET /path#fragment HTTP/1.1\r\n\r\n");
        invalid("GE(T / HTTP/1.1\r\n\r\n");
        invalid("POST / HTTP/1.1\r\nContent-Length: +1\r\n\r\n");
        invalid("POST / HTTP/1.1\r\nContent-Length: 1.0\r\n\r\n");
        invalid("GET / HTTP/1.1\r\nBad Header: value\r\n\r\n");
        invalid("GET / HTTP/1.1\r\nX-Test: a\u0000b\r\n\r\n");
        check(HttpWire.read(new ByteArrayInputStream("GET / HTTP/1.0\r\n\r\n".getBytes(StandardCharsets.US_ASCII))).method.equals("GET"),"HTTP/1.0 remains supported");
        HttpWire.Request decoded=HttpWire.read(new ByteArrayInputStream("GET /api/upload?name=%E7%85%A7%E7%89%87.jpg HTTP/1.1\r\nHost: x\r\n\r\n".getBytes(StandardCharsets.UTF_8)));
        check("照片.jpg".equals(decoded.query.get("name")),"UTF-8 filename");
        check(Dlna.parseTime("01:02:03.500")==3723500,"fractional seek");
        check(Dlna.time(3723500).equals("01:02:03"),"position formatting");
        for(String time:new String[]{"00:60:00","00:00:NaN","00:00:Infinity","-1:00:00","bad"}) {
            try { Dlna.parseTime(time);throw new AssertionError(time); } catch(Exception expected) { checks++; }
        }
        File media=File.createTempFile("lancast-test-",".txt");Files.write(media.toPath(),"0123456789".getBytes(StandardCharsets.US_ASCII));
        TestRenderer player=new TestRenderer(); final Dlna[] dlna=new Dlna[1];
        try(HttpWire http=new HttpWire(0,(r,out)-> {
            if(dlna[0].route(r,out))return;
            if(r.path.equals("/range"))out.file(media,"text/plain",r.header("range"));
            else if(r.path.equals("/echo"))out.text(200,"text/plain",r.text(32));
            else out.text(404,"text/plain","Not found");
        })) {
            dlna[0]=new Dlna("12345678-1234-1234-1234-123456789abc",http.port(),player,()->"客厅 & TV"); http.start();
            int port=http.port(); String result=request(port,"GET","/dlna/device.xml","",""); status(result,200,"description");
            Document device=xml(body(result));check(device.getElementsByTagNameNS("*","friendlyName").item(0).getTextContent().equals("客厅 & TV"),"escaped friendly name");
            for(String service:new String[]{"av","rc","cm"}) {
                result=request(port,"GET","/dlna/"+service+"/scpd.xml","","");status(result,200,"SCPD "+service);Document schema=xml(body(result));
                Set<String> vars=new HashSet<>();NodeList states=schema.getElementsByTagNameNS("*","stateVariable");
                for(int i=0;i<states.getLength();i++)vars.add(((Element)states.item(i)).getElementsByTagNameNS("*","name").item(0).getTextContent());
                NodeList refs=schema.getElementsByTagNameNS("*","relatedStateVariable");for(int i=0;i<refs.getLength();i++)check(vars.contains(refs.item(i).getTextContent()),"SCPD argument points to declared state variable");
            }
            String instance="<InstanceID>0</InstanceID>";
            result=soap(port,"av","SetAVTransportURI",instance+"<CurrentURI>http://192.168.1.2/video.mp4?a=1&amp;b=2</CurrentURI><CurrentURIMetaData>&lt;DIDL-Lite xmlns:dc=\"http://purl.org/dc/elements/1.1/\"&gt;&lt;item&gt;&lt;dc:title&gt;测试影片&lt;/dc:title&gt;&lt;res protocolInfo=\"http-get:*:video/mp4:*\"&gt;http://192.168.1.2/video.mp4&lt;/res&gt;&lt;/item&gt;&lt;/DIDL-Lite&gt;</CurrentURIMetaData>");
            status(result,200,"SetAVTransportURI");check(player.s.uri.endsWith("a=1&b=2"),"URI XML decoding");check(player.s.title.equals("测试影片"),"DIDL title");check(player.s.mime.equals("video/mp4"),"DIDL MIME");
            status(soap(port,"av","Play",instance+"<Speed>1</Speed>"),200,"Play");check(player.s.state.equals("PLAYING"),"playing state");
            result=soap(port,"av","GetTransportInfo",instance);xml(body(result));check(result.contains("<CurrentTransportState>PLAYING</CurrentTransportState>"),"transport response");
            status(soap(port,"av","Seek",instance+"<Unit>REL_TIME</Unit><Target>00:01:25</Target>"),200,"Seek");check(player.s.position==85000,"seek milliseconds");
            result=soap(port,"av","GetPositionInfo",instance);check(result.contains("<RelTime>00:01:25</RelTime>"),"position response");
            status(soap(port,"av","Pause",instance),200,"Pause");check(player.s.state.equals("PAUSED_PLAYBACK"),"paused state");
            status(soap(port,"rc","SetVolume",instance+"<Channel>Master</Channel><DesiredVolume>42</DesiredVolume>"),200,"SetVolume");check(player.s.volume==42,"volume state");
            status(soap(port,"rc","SetMute",instance+"<Channel>Master</Channel><DesiredMute>1</DesiredMute>"),200,"SetMute");check(player.s.muted,"mute state");
            result=soap(port,"rc","SetVolume",instance+"<Channel>Master</Channel><DesiredVolume>101</DesiredVolume>");status(result,500,"invalid volume");check(result.contains("<errorCode>402</errorCode>"),"volume error");
            result=soap(port,"av","GetTransportInfo","<InstanceID>9</InstanceID>");check(result.contains("<errorCode>718</errorCode>"),"instance error");
            result=soap(port,"av","Play",instance+"<Speed>2</Speed>");check(result.contains("<errorCode>717</errorCode>"),"speed error");
            result=soap(port,"av","Seek",instance+"<Unit>TRACK_NR</Unit><Target>2</Target>");check(result.contains("<errorCode>710</errorCode>"),"seek mode error");
            result=soap(port,"av","Unsupported",instance);check(result.contains("<errorCode>401</errorCode>"),"unsupported action");
            result=soap(port,"cm","GetProtocolInfo","");check(result.contains("http-get:*:video/mp4:*"),"sink formats");xml(body(result));
            for(String action:new String[]{"GetMediaInfo","GetDeviceCapabilities","GetTransportSettings","GetCurrentTransportActions"}) { result=soap(port,"av",action,instance);status(result,200,action);xml(body(result)); }
            result=request(port,"POST","/dlna/av/control","","<!DOCTYPE foo [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><x>&x;</x></s:Body></s:Envelope>");status(result,500,"XML entity denied");check(result.contains("402"),"XML entity fault");
            result=request(port,"GET","/range","Range: bytes=2-5\r\n","");status(result,206,"partial media");check(body(result).equals("2345"),"partial body");check(result.contains("Content-Range: bytes 2-5/10"),"range response");
            result=request(port,"HEAD","/range","Range: bytes=-3\r\n","");status(result,206,"HEAD range");check(body(result).isEmpty(),"HEAD has no body");check(result.contains("Content-Length: 3"),"HEAD media length");
            status(request(port,"GET","/range","Range: bytes=99-\r\n",""),416,"invalid media range");
            status(request(port,"POST","/echo","","123456789012345678901234567890123"),413,"bounded body");
            boolean multicast=Arrays.asList(args).contains("--multicast");
            dlna[0].start(multicast);
            if(multicast && !Net.interfaces().isEmpty()) {
                // Exercise SSDP over UDP, independently of the HTTP description tests.
                InetAddress local=Net.interfaces().values().iterator().next();
                try(DatagramSocket discovery=new DatagramSocket(new InetSocketAddress(local,0))) {
                    discovery.setSoTimeout(4500);
                    String search="M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\nST: urn:schemas-upnp-org:device:MediaRenderer:1\r\n\r\n";
                    byte[] bytes=search.getBytes(StandardCharsets.US_ASCII);
                    Thread.sleep(200);
                    System.out.println("SSDP startup status: "+dlna[0].error());
                    discovery.send(new DatagramPacket(bytes,bytes.length,InetAddress.getByName("239.255.255.250"),1900));
                    DatagramPacket response=new DatagramPacket(new byte[4096],4096);discovery.receive(response);
                    String ssdp=new String(response.getData(),0,response.getLength(),StandardCharsets.UTF_8);
                    check(ssdp.startsWith("HTTP/1.1 200 OK"),"SSDP discovery response");
                    check(ssdp.contains("LOCATION: http://"+local.getHostAddress()+":"+port+"/dlna/device.xml"),"SSDP correct location");
                    check(ssdp.contains("ST: urn:schemas-upnp-org:device:MediaRenderer:1"),"SSDP target");
                }
            } else System.out.println("SKIP: SSDP multicast requires --multicast and a LAN interface");
            status(request(port,"SUBSCRIBE","/dlna/av/event","CALLBACK: <http://192.0.2.1/notify>\r\nNT: upnp:event\r\n",""),412,"foreign callback denied");
            status(request(port,"SUBSCRIBE","/dlna/av/event","CALLBACK: <http://127.0.0.1:0/notify>\r\nNT: upnp:event\r\n",""),412,"zero callback port denied");
            status(request(port,"SUBSCRIBE","/dlna/av/event","CALLBACK: <http://127.0.0.1/notify#fragment>\r\nNT: upnp:event\r\n",""),412,"callback fragment denied");
            try(ServerSocket callback=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
                callback.setSoTimeout(5000);
                result=request(port,"SUBSCRIBE","/dlna/av/event","CALLBACK: <http://127.0.0.1:"+callback.getLocalPort()+"/notify>\r\nNT: upnp:event\r\nTIMEOUT: Second-300\r\n","");status(result,200,"event subscription");
                String sid="";for(String line:result.split("\r\n"))if(line.startsWith("SID: "))sid=line.substring(5);
                check(sid.startsWith("uuid:"),"subscription id");
                try(Socket notify=callback.accept()) {
                    notify.setSoTimeout(3000);HttpWire.Request event=HttpWire.read(notify.getInputStream());
                    check(event.method.equals("NOTIFY"),"initial event method");check(event.header("seq").equals("0"),"initial event sequence");
                    String payload=event.text(65536);xml(payload);check(payload.contains("LastChange"),"initial event payload");
                    // Change playback while the old notification is awaiting its response.
                    // The next event must still deliver the newer state, even across an event tick.
                    player.play(); Thread.sleep(1100);
                    notify.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                }
                try(Socket notify=callback.accept()) { notify.setSoTimeout(3000);HttpWire.Request event=HttpWire.read(notify.getInputStream());String payload=event.text(65536);xml(payload);check(payload.contains("PLAYING"),"state-change event reports play");check(!event.header("seq").equals("0"),"event sequence increases");notify.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); }
                player.pause();
                try(Socket notify=callback.accept()) {
                    notify.setSoTimeout(3000);HttpWire.Request event=HttpWire.read(notify.getInputStream());
                    check(event.text(65536).contains("PAUSED_PLAYBACK"),"pause notification");
                    notify.getOutputStream().write("HTTP/1.1 500 Failed\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                }
                try(Socket notify=callback.accept()) {
                    notify.setSoTimeout(3000);HttpWire.Request event=HttpWire.read(notify.getInputStream());
                    check(event.text(65536).contains("PAUSED_PLAYBACK"),"rejected notification is retried");
                    notify.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                }
                status(request(port,"SUBSCRIBE","/dlna/av/event","SID: "+sid+"\r\nTIMEOUT: Second-1800\r\n",""),200,"subscription renewal");
                status(request(port,"UNSUBSCRIBE","/dlna/av/event","SID: "+sid+"\r\n",""),200,"unsubscribe");
            }
            dlna[0].enabled(false);status(request(port,"GET","/dlna/device.xml","",""),503,"DLNA off");dlna[0].close();
        } finally { media.delete(); }
        boolean[][] qr=QrCode.encode("http://192.168.1.100:8192/#pin=123456");check(qr.length==33,"QR dimensions");
        StringBuilder svg=new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"410\" height=\"410\" viewBox=\"-4 -4 41 41\"><path fill=\"white\" d=\"M-4-4h41v41h-41z\"/>");
        for(int y=0;y<33;y++)for(int x=0;x<33;x++)if(qr[y][x])svg.append("<path d=\"M").append(x).append(' ').append(y).append("h1v1h-1z\"/>");
        svg.append("</svg>");Files.createDirectories(Paths.get("build"));Files.write(Paths.get("build/qr-test.svg"),svg.toString().getBytes(StandardCharsets.UTF_8));
        try { QrCode.encode(String.join("",Collections.nCopies(79,"x")));throw new AssertionError("QR overflow accepted"); }catch(IllegalArgumentException expected){checks++;}
        System.out.println("PASS: "+checks+" protocol and regression checks");
    }
}
