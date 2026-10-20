package com.lancast.tv;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

/** Small bounded HTTP/1.1 server. Each connection handles exactly one request. */
public final class HttpWire implements Closeable {
    public interface Handler { void handle(Request request, Response response) throws Exception; }
    public static final class HttpError extends IOException {
        public final int status;
        public HttpError(int status, String message) { super(message); this.status = status; }
    }
    public static final class Request {
        public String method, path, target;
        public final Map<String, String> headers = new LinkedHashMap<>();
        public final Map<String, String> query = new LinkedHashMap<>();
        public InetAddress peer, local;
        public InputStream body;
        public long length;
        public String header(String key) { return headers.get(key.toLowerCase(Locale.US)); }
        public byte[] bytes(int max) throws IOException {
            if (length < 0 || length > max) throw new HttpError(413, "Request too large");
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            copy(body, out, length); return out.toByteArray();
        }
        public String text(int max) throws IOException { return new String(bytes(max), StandardCharsets.UTF_8); }
    }
    public static final class Response {
        private final OutputStream output;
        private final boolean head;
        public boolean sent;
        Response(OutputStream output, boolean head) { this.output = output; this.head = head; }
        public void text(int status, String type, String body) throws IOException { bytes(status, type, body.getBytes(StandardCharsets.UTF_8), Collections.emptyMap()); }
        public void bytes(int status, String type, byte[] bytes, Map<String, String> extra) throws IOException {
            headers(status, type, bytes.length, extra); if (!head) output.write(bytes); output.flush();
        }
        public void headers(int status, String type, long size, Map<String, String> extra) throws IOException {
            String reason;
            switch (status) {
                case 200: reason="OK"; break; case 204: reason="No Content"; break;
                case 206: reason="Partial Content"; break; case 400: reason="Bad Request"; break;
                case 401: reason="Unauthorized"; break; case 403: reason="Forbidden"; break;
                case 404: reason="Not Found"; break; case 405: reason="Method Not Allowed"; break;
                case 409: reason="Conflict"; break; case 411: reason="Length Required"; break;
                case 412: reason="Precondition Failed"; break; case 413: reason="Content Too Large"; break;
                case 416: reason="Range Not Satisfiable"; break; case 429: reason="Too Many Requests"; break;
                case 503: reason="Service Unavailable"; break; case 507: reason="Insufficient Storage"; break;
                default: reason="Internal Server Error";
            }
            StringBuilder h = new StringBuilder("HTTP/1.1 ").append(status).append(' ').append(reason)
                .append("\r\nContent-Type: ").append(type).append("\r\nContent-Length: ").append(size)
                .append("\r\nConnection: close\r\nServer: Android/5.1 UPnP/1.0 LanCast/0.1\r\n")
                .append("X-Content-Type-Options: nosniff\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\n");
            for (Map.Entry<String,String> e : extra.entrySet()) h.append(e.getKey()).append(": ").append(e.getValue()).append("\r\n");
            h.append("\r\n"); sent = true; output.write(h.toString().getBytes(StandardCharsets.UTF_8)); output.flush();
        }
        public void file(File file, String mime, String range) throws IOException {
            long total = file.length();
            long[] span;
            try { span = range(range, total); }
            catch (HttpError e) { bytes(416, "text/plain", new byte[0], Collections.singletonMap("Content-Range", "bytes */" + total)); return; }
            Map<String,String> extra = new LinkedHashMap<>(); extra.put("Accept-Ranges", "bytes");
            if (range != null) extra.put("Content-Range", "bytes " + span[0] + "-" + span[1] + "/" + total);
            long size = total == 0 ? 0 : span[1] - span[0] + 1;
            headers(range == null ? 200 : 206, mime, size, extra);
            if (!head && size > 0) try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
                in.seek(span[0]); byte[] buffer = new byte[65536];
                while (size > 0) { int n = in.read(buffer, 0, (int)Math.min(size, buffer.length)); if (n < 0) throw new EOFException(); output.write(buffer,0,n); size -= n; }
            }
            output.flush();
        }
    }
    private final Handler handler;
    private final ServerSocket server;
    private final ThreadPoolExecutor workers;
    private final Set<Socket> active = Collections.newSetFromMap(new ConcurrentHashMap<Socket, Boolean>());
    private volatile boolean closed;
    public HttpWire(int port, Handler handler) throws IOException {
        this.handler = handler;
        server = new ServerSocket(); server.setReuseAddress(true); server.bind(new InetSocketAddress(port), 16);
        workers = new ThreadPoolExecutor(3, 8, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(16), r -> { Thread t = new Thread(r, "lancast-http"); t.setDaemon(true); return t; });
    }
    public int port() { return server.getLocalPort(); }
    public void start() {
        Thread accept = new Thread(() -> {
            while (!closed) try {
                Socket socket = server.accept(); socket.setSoTimeout(30000); active.add(socket);
                try { workers.execute(() -> serve(socket)); } catch (RejectedExecutionException e) { active.remove(socket); socket.close(); }
            } catch (IOException ignored) {}
        }, "lancast-accept"); accept.setDaemon(true); accept.start();
    }
    private void serve(Socket socket) {
        Response response = null;
        try (Socket ignored = socket) {
            InputStream input = new BufferedInputStream(socket.getInputStream(), 65536);
            OutputStream output = new BufferedOutputStream(socket.getOutputStream(), 65536);
            Request request;
            try { request = read(input); }
            catch (HttpError e) { new Response(output, false).text(e.status, "text/plain; charset=utf-8", e.getMessage()); return; }
            request.peer = socket.getInetAddress(); request.local = socket.getLocalAddress();
            response = new Response(output, "HEAD".equals(request.method));
            if (!Net.local(request.peer)) { response.text(403,"text/plain","LAN only"); return; }
            if ("100-continue".equalsIgnoreCase(request.header("expect"))) { output.write("HTTP/1.1 100 Continue\r\n\r\n".getBytes(StandardCharsets.US_ASCII)); output.flush(); }
            try { handler.handle(request, response); }
            catch (HttpError e) { if (!response.sent) response.text(e.status,"text/plain; charset=utf-8",e.getMessage()); }
            catch (Exception e) { if (!response.sent) response.text(500,"text/plain; charset=utf-8","Request failed"); }
        } catch (IOException ignored) {} finally { active.remove(socket); }
    }
    public static Request read(InputStream input) throws IOException {
        Request r = new Request();
        String line = line(input, 4096); String[] start = line.split(" ", -1);
        if (start.length != 3 || !(start[2].equals("HTTP/1.0") || start[2].equals("HTTP/1.1")) || !start[1].startsWith("/") || !token(start[0])) throw new HttpError(400,"Invalid request");
        r.method = start[0]; r.target = start[1];
        try {
            URI uri = new URI(start[1]);
            if (uri.getRawAuthority() != null || uri.getRawFragment() != null) throw new IllegalArgumentException();
            r.path = uri.getPath();
            if (uri.getRawQuery() != null) for (String part : uri.getRawQuery().split("&")) {
                String[] kv = part.split("=",2); r.query.put(URLDecoder.decode(kv[0],"UTF-8"),kv.length>1 ? URLDecoder.decode(kv[1],"UTF-8") : "");
            }
        } catch (Exception e) { throw new HttpError(400,"Invalid URL"); }
        int total = 0;
        while (!(line = line(input, 8192)).isEmpty()) {
            total += line.length(); if (total > 16384) throw new HttpError(400,"Headers too large");
            int colon = line.indexOf(':'); if (colon <= 0) throw new HttpError(400,"Invalid header");
            String key = line.substring(0,colon).toLowerCase(Locale.US), value = line.substring(colon+1).trim();
            if (!token(key)) throw new HttpError(400,"Invalid header name");
            for (int i=0;i<value.length();i++) if ((value.charAt(i)<32 && value.charAt(i)!='\t') || value.charAt(i)==127) throw new HttpError(400,"Invalid header value");
            if (r.headers.containsKey(key)) throw new HttpError(400,"Duplicate header"); r.headers.put(key,value);
        }
        if (r.header("transfer-encoding") != null) throw new HttpError(400,"Chunked requests are not supported");
        try { String length=r.header("content-length"); if(length!=null && !length.matches("[0-9]+")) throw new NumberFormatException(); r.length = length == null ? 0 : Long.parseLong(length); }
        catch (NumberFormatException e) { throw new HttpError(400,"Invalid length"); }
        r.body = input; return r;
    }
    private static boolean token(String text) { return !text.isEmpty() && text.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+"); }
    static String line(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int previous = -1;
        for (int n=0;n<max;n++) {
            int b = in.read(); if (b < 0) throw new EOFException();
            if (b == 10) { if (previous != 13) throw new HttpError(400,"Invalid line ending"); byte[] bytes = out.toByteArray(); return new String(bytes,0,bytes.length-1,StandardCharsets.US_ASCII); }
            out.write(b); previous = b;
        }
        throw new HttpError(400,"Line too long");
    }
    public static long[] range(String value, long size) throws HttpError {
        if (value == null) return new long[] {0, size-1};
        try {
            if (!value.startsWith("bytes=") || value.indexOf(',') >= 0 || size == 0) throw new NumberFormatException();
            String[] parts = value.substring(6).split("-", -1); if (parts.length != 2) throw new NumberFormatException();
            long from, to;
            if (parts[0].isEmpty()) { long tail = Long.parseLong(parts[1]); if (tail <= 0) throw new NumberFormatException(); from = Math.max(0,size-tail); to = size-1; }
            else { from = Long.parseLong(parts[0]); to = parts[1].isEmpty() ? size-1 : Math.min(size-1,Long.parseLong(parts[1])); }
            if (from < 0 || from >= size || to < from) throw new NumberFormatException();
            return new long[] {from,to};
        } catch (Exception e) { throw new HttpError(416,"Invalid range"); }
    }
    public static void copy(InputStream in, OutputStream out, long length) throws IOException {
        byte[] buffer = new byte[65536];
        while (length > 0) { int count = in.read(buffer,0,(int)Math.min(length,buffer.length)); if (count < 0) throw new EOFException("Incomplete body"); out.write(buffer,0,count); length -= count; }
    }
    @Override public void close() {
        closed = true; try { server.close(); } catch (IOException ignored) {}
        for (Socket s : active) try { s.close(); } catch (IOException ignored) {}
        workers.shutdownNow();
    }
}
