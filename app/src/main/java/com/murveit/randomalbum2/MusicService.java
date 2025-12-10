package com.murveit.randomalbum2;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.content.IntentFilter;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import java.util.Timer;
import java.util.TimerTask;
import android.app.PendingIntent;
import androidx.media.app.NotificationCompat.MediaStyle;
import android.telephony.TelephonyManager;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public class MusicService extends Service implements
        MediaPlayer.OnPreparedListener,
        MediaPlayer.OnErrorListener,
        MediaPlayer.OnCompletionListener {

    private static final String TAG = "MusicService";
    private final IBinder binder = new MusicBinder();

    // Notification constants
    private static final int NOTIFICATION_ID = 1;
    private static final String NOTIFICATION_CHANNEL_ID = "MusicServiceChannel";

    // Media Player and music library
    private MediaPlayer mediaPlayer;
    private List<Album> allAlbums = new ArrayList<>();
    private List<Album> albumHistory = new ArrayList<>();
    private Album currentAlbum;
    private int currentSongIndex = -1;
    private int historyIndex = -1;
    private final Random random = new Random();

    private final MutableLiveData<Album> currentAlbumData = new MutableLiveData<>();
    private final MutableLiveData<Song> currentSongData = new MutableLiveData<>();
    private final MutableLiveData<Boolean> isPlayingData = new MutableLiveData<>(false);
    private final MutableLiveData<Integer> playbackPositionData = new MutableLiveData<>(0);

    private Timer progressTimer;

    // --- Public Getters for LiveData ---
    public LiveData<Album> getCurrentAlbumData() { return currentAlbumData; }
    public LiveData<Song> getCurrentSongData() { return currentSongData; }
    public LiveData<Boolean> getIsPlayingData() { return isPlayingData; }
    public LiveData<Integer> getPlaybackPositionData() { return playbackPositionData; }


    // Actions (No changes here)
    public static final String ACTION_PLAY_PAUSE = "com.murveit.randomalbum2.action.PLAY_PAUSE";
    public static final String ACTION_NEXT_SONG = "com.murveit.randomalbum2.action.NEXT_SONG";
    public static final String ACTION_PREV_SONG = "com.murveit.randomalbum2.action.PREV_SONG";
    public static final String ACTION_NEXT_ALBUM = "com.murveit.randomalbum2.action.NEXT_ALBUM";
    public static final String ACTION_PREV_ALBUM = "com.murveit.randomalbum2.action.PREV_ALBUM";
    public static final String ACTION_PLAY_ALBUM_BY_ID = "com.murveit.randomalbum2.action.PLAY_ALBUM_BY_ID";
    public static final String EXTRA_ALBUM_ID = "com.murveit.randomalbum2.extra.ALBUM_ID";
    private PhoneStateReceiver phoneStateReceiver;
    private IntentFilter intentFilter;
    private static final String PREF_LAST_ALBUM_ID = "pref_last_album_id";
    private static final String PREF_LAST_SONG_INDEX = "pref_last_song_index";
    private static final String PREF_LAST_POSITION = "pref_last_position";

    public class MusicBinder extends Binder {
        MusicService getService() {
            return MusicService.this;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "MusicService onCreate");
        createNotificationChannel();
        initMediaPlayer();
        setupProgressTimer();

        phoneStateReceiver = new PhoneStateReceiver(this);
        intentFilter = new IntentFilter(TelephonyManager.ACTION_PHONE_STATE_CHANGED);
        registerReceiver(phoneStateReceiver, intentFilter);
        Log.d(TAG, "PhoneStateReceiver registered.");

        new Thread(() -> {
            MusicRepository repository = new MusicRepository(getApplicationContext());
            allAlbums = repository.getAlbums();
            Log.d(TAG, "Finished loading " + allAlbums.size() + " albums.");
            new android.os.Handler(getMainLooper()).post(this::loadLastPlaybackState);
        }).start();
    }
    public boolean isPlaying() {
        return mediaPlayer != null && mediaPlayer.isPlaying();
    }
    public List<Album> getAllAlbums() {
        return allAlbums;
    }
    public int getCurrentSongIndex() { return currentSongIndex; }


    private void setupProgressTimer() {
        progressTimer = new Timer();
        progressTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                if (mediaPlayer != null && mediaPlayer.isPlaying()) {
                    playbackPositionData.postValue(mediaPlayer.getCurrentPosition());
                }
            }
        }, 0, 500); // Update every 500ms
    }
    private void initMediaPlayer() {
        mediaPlayer = new MediaPlayer();
        // Set audio attributes for music playback
        mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .build());
        // Keep the CPU alive while playing music
        mediaPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);
        // Set listeners
        mediaPlayer.setOnPreparedListener(this);
        mediaPlayer.setOnCompletionListener(this);
        mediaPlayer.setOnErrorListener(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // Handle actions from notifications here in the future
        String action = intent != null ? intent.getAction() : null;
        if (action != null) {
            switch (action) {
                case ACTION_PLAY_PAUSE:
                    playPause();
                    break;
                case ACTION_NEXT_SONG:
                    nextSong();
                    break;
                case ACTION_PREV_SONG:
                    prevSong();
                    break;
            }
        }
        startForeground(NOTIFICATION_ID, createNotification());
        return START_STICKY;
    }

    // --- Public methods for the UI to call ---

    public void playPause() {
        if (mediaPlayer == null) return;
        if (mediaPlayer.isPlaying()) {
            mediaPlayer.pause();
            Log.d(TAG, "Playback paused");
            isPlayingData.postValue(false);
            savePlaybackState();
        } else {
            if (currentAlbum == null) {
                nextAlbum();
            } else {
                mediaPlayer.start();
                Log.d(TAG, "Playback resumed");
                isPlayingData.postValue(true);
            }
        }
        updateNotification();
    }
    private void savePlaybackState() {
        if (currentAlbum == null || mediaPlayer == null) return;

        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        SharedPreferences.Editor editor = prefs.edit();

        editor.putLong(PREF_LAST_ALBUM_ID, currentAlbum.id);
        editor.putInt(PREF_LAST_SONG_INDEX, currentSongIndex);
        editor.putInt(PREF_LAST_POSITION, mediaPlayer.getCurrentPosition());
        editor.apply(); // apply() saves in the background

        Log.d(TAG, "Saved playback state: Album ID " + currentAlbum.id + ", Position " + mediaPlayer.getCurrentPosition());
    }
    private void updateNotification() {
        // This method takes the notification created by createNotification()
        // and displays it. It's the standard way to update a foreground service's notification.
        Notification notification = createNotification();
        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, notification);
    }

    public void nextSong() {
        if (currentAlbum == null || currentAlbum.songs.isEmpty()) {
            Log.w(TAG, "No current album to play next song from.");
            return;
        }
        currentSongIndex++;
        if (currentSongIndex >= currentAlbum.songs.size()) {
            // If we've finished the album, go to the next random one
            Log.d(TAG, "Finished album, playing next album.");
            nextAlbum();
        } else {
            playSong();
        }
    }

    public void prevSong() {
        if (currentAlbum == null || currentAlbum.songs.isEmpty()) {
            Log.w(TAG, "No current album to play previous song from.");
            return;
        }

        // If more than 3 seconds into the song, or if it's not the first song, just restart/go back.
        if (mediaPlayer != null && mediaPlayer.getCurrentPosition() > 6000 || currentSongIndex > 0) {
            // If we are not on the first track, go back one.
            if (currentSongIndex > 0 && mediaPlayer.getCurrentPosition() <= 6000) {
                currentSongIndex--;
            }
            // If we were more than 3 seconds in, this will just restart the current track.
            // If we were less than 3 seconds in on track > 0, this will play the previous.
            playSong();
        } else {
            // We are on the first song and within the first 3 seconds.
            // Go to the last song of the previous album.
            Log.d(TAG, "At start of first song, going to previous album.");
            prevAlbum(true); // Pass true to indicate starting from the end
        }
    }
    public void playAlbumById(long albumId) {
        if (allAlbums.isEmpty()) {
            Log.e(TAG, "Cannot play album by ID, album list is not loaded yet.");
            return;
        }
        Album albumToPlay = null;
        for (Album album : allAlbums) {
            if (album.id == albumId) {
                albumToPlay = album;
                break;
            }
        }

        if (albumToPlay != null) {
            // A direct selection from the picker is a new choice and should clear any "forward" history.
            playAlbum(albumToPlay, 0, true);
        } else {
            Log.e(TAG, "Could not find album with ID: " + albumId);
        }
    }


    public void nextAlbum() {
        Log.d(TAG, "nextAlbum called. History size: " + albumHistory.size() + ", Index: " + historyIndex);
        if (allAlbums.isEmpty()) {
            Log.w(TAG, "Album list is empty, cannot play next album.");
            return;
        }

        // Check if a "forward" album exists in the history.
        if (historyIndex < albumHistory.size() - 1) {
            historyIndex++;
            Album nextInHistory = albumHistory.get(historyIndex);
            Log.d(TAG, "Playing next album from history: " + nextInHistory.title);
            playAlbum(nextInHistory, 0, false);
        } else {
            // At the end of history, find a new random album.
            Log.d(TAG, "End of history, finding a new random album.");
            Album next = currentAlbum;
            if (allAlbums.size() > 1) {
                while (next == currentAlbum) {
                    next = allAlbums.get(random.nextInt(allAlbums.size()));
                }
            } else if (!allAlbums.isEmpty()){
                next = allAlbums.get(0);
            }
            if (next != null) {
                playAlbum(next, 0, true);
            }
        }
    }

    public void prevAlbum() {
        // Public method defaults to starting from the beginning of the album.
        prevAlbum(false);
    }

    private void prevAlbum(boolean startFromLastSong) {
        Log.d(TAG, "prevAlbum called. History size: " + albumHistory.size() + ", Index: " + historyIndex);
        if (allAlbums.isEmpty()) {
            Log.w(TAG, "Album list is empty, cannot play previous album.");
            return;
        }

        // If a previous album exists in our history
        if (historyIndex > 0) {
            historyIndex--;
            Album prevInHistory = albumHistory.get(historyIndex);
            Log.d(TAG, "Playing previous album from history: " + prevInHistory.title);
            int songIndex = startFromLastSong ? prevInHistory.songs.size() - 1 : 0;
            playAlbum(prevInHistory, songIndex, false);
        } else {
            // No previous album in history, so find a new random one to be "previous".
            Log.d(TAG, "No previous album in history. Finding a new random one.");
            Album newPrevious = currentAlbum;
            if (allAlbums.size() > 1) {
                while (newPrevious == currentAlbum) {
                    newPrevious = allAlbums.get(random.nextInt(allAlbums.size()));
                }
                // Insert this new album at the beginning of the history.
                albumHistory.add(0, newPrevious);
                // Our index is now 0 (pointing to the album we just added).
                historyIndex = 0;
                int songIndex = startFromLastSong ? newPrevious.songs.size() - 1 : 0;
                playAlbum(newPrevious, songIndex, true);
            } else if (!allAlbums.isEmpty()){
                // Only one album exists, just play it.
                Album album = allAlbums.get(0);
                int songIndex = startFromLastSong ? album.songs.size() - 1 : 0;
                playAlbum(album, songIndex, true);
            }
        }
    }
    /**
     * The definitive method to play an album. Manages history and starts playback.
     * @param album The Album to play.
     * @param startSongIndex The index of the song to start with.
     * @param isNewChoice If true, clears any "forward" history.
     */
    private void playAlbum(Album album, int startSongIndex, boolean isNewChoice) {
        Log.d(TAG, "Playing album: " + album.title + " starting at index: " + startSongIndex);
        currentAlbum = album;
        currentSongIndex = startSongIndex;
        currentAlbumData.postValue(currentAlbum);

        // --- History Management Logic ---
        if (isNewChoice) {
            // If this is a new choice, remove all albums after the current index.
            if (historyIndex > -1 && historyIndex < albumHistory.size() - 1) {
                albumHistory.subList(historyIndex + 1, albumHistory.size()).clear();
            }
        }

        int existingIndex = albumHistory.indexOf(album);
        if (existingIndex == -1) {
            // Album is not in history, add it to the end.
            albumHistory.add(album);
            historyIndex = albumHistory.size() - 1;
        } else {
            // Album is already in history, just move the pointer to it.
            historyIndex = existingIndex;
        }
        // --- End History Management ---

        playSong();
    }


    private void playSong() {
        if (currentAlbum == null || currentSongIndex < 0 || currentSongIndex >= currentAlbum.songs.size()) {
            Log.e(TAG, "Cannot play song, invalid state.");
            return;
        }


        mediaPlayer.reset();
        isPlayingData.postValue(false); // Reset playing state
        Song songToPlay = currentAlbum.songs.get(currentSongIndex);
        currentSongData.postValue(songToPlay); // UPDATE
        Log.d(TAG, "Preparing to play: " + songToPlay.title);

        try {
            mediaPlayer.setDataSource(getApplicationContext(), songToPlay.contentUri);
            mediaPlayer.prepareAsync(); // Asynchronous preparation
        } catch (IOException e) {
            Log.e(TAG, "Error setting data source", e);
        }
    }

    // --- MediaPlayer Listeners ---

    @Override
    public void onCompletion(MediaPlayer mp) {
        isPlayingData.postValue(false); // UPDATE
        Log.d(TAG, "Song completed. Playing next.");
        nextSong();
    }

    @Override
    public boolean onError(MediaPlayer mp, int what, int extra) {
        Log.e(TAG, "MediaPlayer Error - what: " + what + " extra: " + extra);
        return false; // 'false' to allow onCompletion to be called
    }

    @Override
    public void onPrepared(MediaPlayer mp) {
        mp.start();
        isPlayingData.postValue(true);
        Log.d(TAG, "MediaPlayer prepared. Playback started.");
        updateNotification();
    }

    // --- Notification Management ---
    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel serviceChannel = new NotificationChannel(
                    NOTIFICATION_CHANNEL_ID,
                    "Music Playback",
                    NotificationManager.IMPORTANCE_LOW
            );
            NotificationManager manager = getSystemService(NotificationManager.class);
            manager.createNotificationChannel(serviceChannel);
        }
    }
    private Notification createNotification() {
        String contentText = "Press Next Album to Start";
        String contentTitle = "RandomAlbum2";
        Song currentSong = null;

        if (currentAlbum != null && currentSongIndex != -1 && currentSongIndex < currentAlbum.songs.size()) {
            currentSong = currentAlbum.songs.get(currentSongIndex);
            contentText = currentSong.artist;
            contentTitle = currentSong.title;
        }

        // Create PendingIntents for notification actions
        // The flag PendingIntent.FLAG_IMMUTABLE is required for newer Android versions.

        // Previous Song Intent
        Intent prevIntent = new Intent(this, MusicService.class);
        prevIntent.setAction(ACTION_PREV_SONG);
        PendingIntent prevPendingIntent = PendingIntent.getService(this, 0, prevIntent, PendingIntent.FLAG_IMMUTABLE);

        // Play/Pause Intent
        Intent playPauseIntent = new Intent(this, MusicService.class);
        playPauseIntent.setAction(ACTION_PLAY_PAUSE);
        PendingIntent playPausePendingIntent = PendingIntent.getService(this, 0, playPauseIntent, PendingIntent.FLAG_IMMUTABLE);

        // Next Song Intent
        Intent nextIntent = new Intent(this, MusicService.class);
        nextIntent.setAction(ACTION_NEXT_SONG);
        PendingIntent nextPendingIntent = PendingIntent.getService(this, 0, nextIntent, PendingIntent.FLAG_IMMUTABLE);

        // Determine which play/pause icon to show
        int playPauseIcon = isPlaying() ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play;

        // Open the app when the notification is tapped
        Intent openAppIntent = new Intent(this, MainActivity.class);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, openAppIntent, PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setContentTitle(contentTitle)
                .setContentText(contentText)
                .setSmallIcon(android.R.drawable.ic_media_play) // Use our placeholder icon
                .setContentIntent(contentIntent) // Set the intent to open the app on click
                .setSilent(true) // Don't make a sound for notification updates
                .addAction(new NotificationCompat.Action(android.R.drawable.ic_media_previous, "Previous", prevPendingIntent))
                .addAction(new NotificationCompat.Action(playPauseIcon, "Play/Pause", playPausePendingIntent))
                .addAction(new NotificationCompat.Action(android.R.drawable.ic_media_next, "Next", nextPendingIntent))
                .setStyle(new MediaStyle()
                        .setShowActionsInCompactView(0, 1, 2) // Show all 3 actions in compact view
                );

        // Add album art to the notification
        if (currentAlbum != null && currentAlbum.coverArtUri != null) {
            // Note: Loading bitmaps on the main thread is generally discouraged.
            // For a production app, you'd use Glide's notification target API,
            // but for simplicity, this is acceptable.
            try {
                builder.setLargeIcon(android.provider.MediaStore.Images.Media.getBitmap(this.getContentResolver(), currentAlbum.coverArtUri));
            } catch (IOException e) {
                Log.e(TAG, "Failed to load album art for notification", e);
            }
        }

        return builder.build();
    }

    private void playRandomAlbum() {
        if (allAlbums == null || allAlbums.isEmpty()) {
            Log.e(TAG, "Cannot play random album, list is empty or not loaded yet.");
            return;
        }

        // Get a random index and play the corresponding album
        int randomIndex = random.nextInt(allAlbums.size());
        Album randomAlbum = allAlbums.get(randomIndex);

        Log.d(TAG, "Playing random album on first start: " + randomAlbum.title);

        // Call your definitive play method. This is a new choice, so history should be managed.
        playAlbum(randomAlbum, 0, true);
    }
    private void loadLastPlaybackState() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        long lastAlbumId = prefs.getLong(PREF_LAST_ALBUM_ID, -1L);

        if (lastAlbumId == -1L) {
            Log.d(TAG, "No saved playback state found. Starting a random album.");
            playRandomAlbum();
            return;
        }

        Album lastAlbum = null;
        for (Album album : allAlbums) {
            if (album.id == lastAlbumId) {
                lastAlbum = album;
                break;
            }
        }

        if (lastAlbum != null) {
            Log.d(TAG, "Found last played album: " + lastAlbum.title);
            int lastSongIndex = prefs.getInt(PREF_LAST_SONG_INDEX, 0);
            int lastPosition = prefs.getInt(PREF_LAST_POSITION, 0);

            // Set the state but DON'T auto-play
            currentAlbum = lastAlbum;
            currentSongIndex = lastSongIndex;
            currentAlbumData.postValue(currentAlbum);

            if (currentSongIndex < currentAlbum.songs.size()) {
                Song songToPrepare = currentAlbum.songs.get(currentSongIndex);
                currentSongData.postValue(songToPrepare);
                try {
                    mediaPlayer.reset();
                    mediaPlayer.setDataSource(getApplicationContext(), songToPrepare.contentUri);
                    // Prepare the player and seek, but don't start it
                    mediaPlayer.setOnPreparedListener(mp -> {
                        mp.seekTo(lastPosition);
                        playbackPositionData.postValue(lastPosition);
                        Log.d(TAG, "Restored state to song: " + songToPrepare.title + ", Position: " + lastPosition);
                        // Reset the listener to the main one
                        mediaPlayer.setOnPreparedListener(this);
                    });
                    mediaPlayer.prepareAsync();
                } catch (IOException e) {
                    Log.e(TAG, "Error preparing restored song", e);
                }
            }
            // Clear the saved state so it doesn't auto-load again next time
            // unless the user pauses again.
            prefs.edit().clear().apply();
        }
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        Log.d(TAG, "Task removed by user. Shutting down service.");

        // Save the state if music is playing
        if (mediaPlayer != null && isPlaying()) {
            savePlaybackState();
        }

        // Stop the service properly. This will trigger onDestroy().
        stopSelf();

        super.onTaskRemoved(rootIntent);
    }
    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "MusicService onDestroy");
        if (mediaPlayer != null && isPlaying()) {
            savePlaybackState();
        }
        unregisterReceiver(phoneStateReceiver);
        Log.d(TAG, "PhoneStateReceiver unregistered.");
        if (mediaPlayer != null) {
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (progressTimer != null) {
            progressTimer.cancel(); // Stop the timer
            progressTimer = null;
        }
    }
}
