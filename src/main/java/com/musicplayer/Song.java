package com.musicplayer;

import java.io.File;

public class Song {

    private String  title;
    private String  fileName;
    private String  filePath;
    private boolean cloud;
    private String  artist;

    // Local file
    public Song(File file) {
        this.filePath = file.toURI().toString();
        this.fileName = file.getName();
        this.title    = fileName.replace(".mp3", "").replace(".wav", "");
        this.cloud    = false;
    }

    // Cloud URL (Google Drive, Dropbox, direct URL)
    public Song(String url, String displayTitle) {
        this.filePath = url;
        this.title    = displayTitle;
        this.fileName = displayTitle;
        this.cloud    = true;
    }

    public String  getTitle()    { return title; }
    public String  getFileName() { return fileName; }
    public String  getFilePath() { return filePath; }
    public boolean isCloud()     { return cloud; }
    public String  getArtist()   { return artist; }
    public void    setArtist(String a) { this.artist = a; }

    @Override
    public String toString() { return title; }
}
