package com.murveit.randomalbum2;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MusicRepository {

    private Context context;

    public MusicRepository(Context context) {
        this.context = context;
    }

    public List<Album> getAlbums() {
        List<Song> songList = getSongs();

        // Group songs by their album ID
        Map<Long, List<Song>> songsByAlbum = new HashMap<>();
        for (Song song : songList) {
            List<Song> albumSongs = songsByAlbum.get(song.albumId);
            if (albumSongs == null) {
                albumSongs = new ArrayList<>();
                songsByAlbum.put(song.albumId, albumSongs);
            }
            albumSongs.add(song);
        }

        List<Album> albums = new ArrayList<>();
        Uri albumArtUriBase = Uri.parse("content://media/external/audio/albumart");

        for (Map.Entry<Long, List<Song>> entry : songsByAlbum.entrySet()) {
            long albumId = entry.getKey();
            List<Song> songs = entry.getValue();

            if (!songs.isEmpty()) {
                Song firstSong = songs.get(0);
                // Sort songs by track number
                Collections.sort(songs, (s1, s2) -> Integer.compare(s1.trackNumber, s2.trackNumber));
                Uri coverArtUri = ContentUris.withAppendedId(albumArtUriBase, albumId);

                albums.add(new Album(
                        albumId,
                        firstSong.albumTitle,
                        firstSong.artist,
                        songs,
                        coverArtUri
                ));
            }
        }

        // Sort albums by title
        Collections.sort(albums, (a1, a2) -> a1.title.compareToIgnoreCase(a2.title));
        Log.d("MusicRepository", "Found " + albums.size() + " albums.");
        return albums;
    }

    private List<Song> getSongs() {
        List<Song> songs = new ArrayList<>();
        Uri collection;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL);
        } else {
            collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        }

        String[] projection = new String[]{
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.ALBUM_ID,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.TRACK
        };

        // Filter for only music files, ignoring ringtones etc.
        String selection = MediaStore.Audio.Media.IS_MUSIC + " = 1";
        String sortOrder = MediaStore.Audio.Media.ALBUM + " ASC, " + MediaStore.Audio.Media.TRACK + " ASC";

        try (Cursor cursor = context.getContentResolver().query(collection, projection, selection, null, sortOrder)) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                int titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                int artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                int albumTitleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM);
                int albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID);
                int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                int trackColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK);

                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    String title = cursor.getString(titleColumn);
                    String artist = cursor.getString(artistColumn);
                    String albumTitle = cursor.getString(albumTitleColumn);
                    long albumId = cursor.getLong(albumIdColumn);
                    int duration = cursor.getInt(durationColumn);
                    int trackNumberWithDisc = cursor.getInt(trackColumn);

                    // The track number can be like 1001 for disc 1, track 1. We only want the track number.
                    int trackNumber = trackNumberWithDisc % 1000;

                    Uri contentUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);

                    songs.add(new Song(id, title, artist, albumTitle, albumId, duration, contentUri, trackNumber));
                }
            }
        } catch (Exception e) {
            Log.e("MusicRepository", "Error fetching songs", e);
        }

        Log.d("MusicRepository", "Found " + songs.size() + " songs.");
        return songs;
    }
}
