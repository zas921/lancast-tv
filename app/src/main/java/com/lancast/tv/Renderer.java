package com.lancast.tv;

public interface Renderer {
    final class State {
        public String state="NO_MEDIA_PRESENT", uri="", title="等待投屏", mime="", error="", metadata="";
        public int duration, position, volume=70;
        public boolean muted;
    }
    State state();
    void load(String uri, String title, String mime, String metadata) throws Exception;
    void play() throws Exception;
    void pause() throws Exception;
    void stop() throws Exception;
    void seek(int milliseconds) throws Exception;
    void volume(int value) throws Exception;
    void mute(boolean value) throws Exception;
}
