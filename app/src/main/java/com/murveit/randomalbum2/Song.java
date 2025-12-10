package com.murveit.randomalbum2;

import android.net.Uri;

public class Song {
    public final long id;
    public final String title;
    public final String artist;
    public final String albumTitle;
    public final long albumId;
    public final int duration; // in milliseconds
    public final Uri contentUri;
    public final int trackNumber;

    public Song(long id, String title, String artist, String albumTitle, long albumId, int duration, Uri contentUri, int trackNumber) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.albumTitle = albumTitle;
        this.albumId = albumId;
        this.duration = duration;
        this.contentUri = contentUri;
        this.trackNumber = trackNumber;
    }
}
