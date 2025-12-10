package com.murveit.randomalbum2;

import android.net.Uri;
import java.util.List;

public class Album {
    public final long id;
    public final String title;
    public final String artist;
    public final List<Song> songs;
    public final Uri coverArtUri;

    public Album(long id, String title, String artist, List<Song> songs, Uri coverArtUri) {
        this.id = id;
        this.title = title;
        this.artist = artist;
        this.songs = songs;
        this.coverArtUri = coverArtUri;
    }
}
