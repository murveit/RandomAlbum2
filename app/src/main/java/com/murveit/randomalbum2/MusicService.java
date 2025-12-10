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

import androidx.media.MediaBrowserServiceCompat;
import androidx.media.app.NotificationCompat.MediaStyle;
import android.telephony.TelephonyManager;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

import android.support.v4.media.MediaMetadataCompat;
import android.support.v4.media.session.MediaSessionCompat;
import android.support.v4.media.session.PlaybackStateCompat;
import androidx.media.MediaBrowserServiceCompat;
import java.util.List;
import android.os.Bundle;
import androidx.annotation.NonNull;
import android.support.v4.media.MediaBrowserCompat;

public class MusicService extends MediaBrowserServiceCompat implements
        MediaPlayer.OnPreparedListener,
        MediaPlayer.OnErrorListener,
        MediaPlayer.OnCompletionListener {

    private static final String TAG = "MusicService";
    private final IBinder binder = new MusicBinder();

    private MediaSessionCompat mediaSession;
    private MediaMetadataCompat.Builder metadataBuilder = new MediaMetadataCompat.Builder();

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


    // Actions
    public static final String ACTION_PLAY_PAUSE = "com.murveit.randomalbum2.action.PLAY_PAUSE";
    public static final String ACTION_PLAY = "com.murveit.randomalbum2.action.PLAY";
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

        // Create a MediaSessionCompat
        mediaSession = new MediaSessionCompat(this, TAG);

        // Set the session's callbacks
        mediaSession.setCallback(new MediaSessionCompat.Callback() {
            @Override
            public void onPlay() {
                Log.d("MediaSessionCallback", "====== ON PLAY RECEIVED ======");
                // Your existing logic for playing
                play();
            }

            @Override
            public void onPause() {
                Log.d("MediaSessionCallback", "====== ON PAUSE RECEIVED ======");
                // If the system tells us to pause, but we are already paused,
                // treat it as a command to PLAY.
                if (mediaPlayer != null && !mediaPlayer.isPlaying()) {
                    Log.d("MediaSessionCallback", "Was already paused, so interpreting ON PAUSE as PLAY.");
                    play();
                } else {
                    // Otherwise, perform a normal pause.
                    Log.d("MediaSessionCallback", "Was playing, performing a normal PAUSE.");
                    pause();
                }
            }

            @Override
            public void onSkipToNext() {
                Log.d("MediaSessionCallback", "====== ON SKIP TO NEXT RECEIVED ======");
                // Your existing logic for next song
                nextSong();
            }

            @Override
            public void onSkipToPrevious() {
                Log.d("MediaSessionCallback", "====== ON SKIP TO PREVIOUS RECEIVED ======");
                // Your existing logic for previous song
                prevSong();
            }
            @Override
            public void onStop() {
                Log.d("MediaSessionCallback", "====== ON STOP RECEIVED ======");
                // Your logic to stop playback completely
                stopPlayback();
            }
        });
        // Set the session's token so that client activities can communicate with it.
        setSessionToken(mediaSession.getSessionToken());

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

    private void updateMediaSessionState() {
        if (mediaPlayer == null || mediaSession == null) {
            return;
        }
        int state = isPlayingData.getValue() != null && isPlayingData.getValue()
                ? PlaybackStateCompat.STATE_PLAYING
                : PlaybackStateCompat.STATE_PAUSED;

        // Use a long for the actions bitmask
        long actions = PlaybackStateCompat.ACTION_PLAY_PAUSE |
                PlaybackStateCompat.ACTION_PLAY |
                PlaybackStateCompat.ACTION_PAUSE |
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT |
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS |
                PlaybackStateCompat.ACTION_STOP;

        mediaSession.setPlaybackState(new PlaybackStateCompat.Builder()
                .setState(state, mediaPlayer.getCurrentPosition(), 1.0f)
                .setActions(actions) // Use the actions bitmask
                .build());
    }

    private void updateMediaSessionMetadata(Song song) {
        if (song == null || mediaSession == null) {
            return;
        }

        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist);
        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM, currentAlbum.title);
        metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title);
        metadataBuilder.putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.duration);

        // If you have album art, you'd load it here and add it:
        // metadataBuilder.putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, albumArtBitmap);

        mediaSession.setMetadata(metadataBuilder.build());
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
                case ACTION_PLAY:
                    play();
                    break;
            }
        }
        return START_STICKY;
    }

    // Replace your single playPause() method with these three methods:

    public void playPause() {
        if (mediaPlayer != null && mediaPlayer.isPlaying()) {
            pause();
        } else {
            play();
        }
    }

    public void play() {
        if (mediaPlayer == null) return;

        if (currentAlbum == null) {
            nextAlbum();
        } else {
            mediaPlayer.start();
            isPlayingData.postValue(true);
            Log.d(TAG, "Playback started/resumed");
            mediaSession.setActive(true); // **CRITICAL**: Take audio focus
            updateNotification();
            updateMediaSessionState();
        }
    }

    public void pause() {
        if (mediaPlayer == null || !mediaPlayer.isPlaying()) return;

        mediaPlayer.pause();
        isPlayingData.postValue(false);
        Log.d(TAG, "Playback paused");
        savePlaybackState(); // Save state when user pauses

        stopForeground(false); // Keep notification but allow service to be stopped if idle
        updateNotification();
        updateMediaSessionState();
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
        // and displays it.
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

        if (mediaPlayer != null && mediaPlayer.getCurrentPosition() > 6000 || currentSongIndex > 0) {
            // If we are not on the first track, go back one.
            if (currentSongIndex > 0 && mediaPlayer.getCurrentPosition() <= 6000) {
                currentSongIndex--;
            }
            playSong();
        } else {
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

        if (!album.songs.isEmpty() && startSongIndex < album.songs.size()) {
            Song firstSong = album.songs.get(startSongIndex);
            updateMediaSessionMetadata(firstSong); // Update metadata for the first track
        }

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
        playSong();
    }
    public void stopPlayback() {
        if (mediaPlayer != null) {
            if (mediaPlayer.isPlaying()) {
                mediaPlayer.stop();
            }
            mediaPlayer.reset(); // Reset the player to an idle state
        }
        isPlayingData.postValue(false);
        updateMediaSessionState(); // Update the state to PAUSED
        mediaSession.setActive(false); // **CRITICAL**: Release the active session
        stopForeground(true); // Stop the foreground service and remove notification
        Log.d(TAG, "Playback stopped and session released.");
    }

    private void playSong() {
        if (currentAlbum == null || currentSongIndex < 0 || currentSongIndex >= currentAlbum.songs.size()) {
            Log.e(TAG, "playSong failed: Invalid state.");
            return;
        }
        Song songToPlay = currentAlbum.songs.get(currentSongIndex);
        Log.d(TAG, "playSong: " + songToPlay.title);

        // --- ADD THIS LINE ---
        updateMediaSessionMetadata(songToPlay);

        currentSongData.postValue(songToPlay);
        try {
            mediaPlayer.reset();
            mediaPlayer.setDataSource(getApplicationContext(), songToPlay.contentUri);
            mediaPlayer.prepareAsync(); // onPrepared will be called, which starts playback
        } catch (IOException e) {
            Log.e(TAG, "Error setting data source", e);
        }
    }

    @Nullable
    @Override
    public BrowserRoot onGetRoot(@NonNull String clientPackageName, int clientUid, @Nullable Bundle rootHints) {
        // Returning a non-null root allows clients to connect to this service.
        return new BrowserRoot("root", null);
    }

    @Override
    public void onLoadChildren(@NonNull String parentId, @NonNull Result<List<MediaBrowserCompat.MediaItem>> result) {
        // For now, we don't need to provide a browsable library, so we send an empty list.
        // This must be called or the client will hang.
        result.sendResult(new ArrayList<>());
    }

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

        // 1. Take control of the media session. This tells the system you are the active player.
        mediaSession.setActive(true);

        // 2. Create the media-styled notification.
        Notification notification = createNotification();

        // 3. Instead of calling updateNotification(), directly call startForeground() here.
        //    Because the notification is tied to an active MediaSession, the system allows this.
        startForeground(NOTIFICATION_ID, notification);

        // 4. Update the MediaSession's state to Playing.
        updateMediaSessionState();
    }

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

    // In MusicService.java

    private void loadLastPlaybackState() {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        long lastAlbumId = prefs.getLong(PREF_LAST_ALBUM_ID, -1);
        int lastSongIndex = prefs.getInt(PREF_LAST_SONG_INDEX, 0);
        int lastPosition = prefs.getInt(PREF_LAST_POSITION, 0);

        if (lastAlbumId != -1 && !allAlbums.isEmpty()) {
            Album foundAlbum = null;
            for (Album album : allAlbums) {
                if (album.id == lastAlbumId) {
                    foundAlbum = album;
                    break;
                }
            }

            if (foundAlbum != null) {
                Log.d(TAG, "Restoring last playback state: Album '" + foundAlbum.title + "', Song index " + lastSongIndex);
                currentAlbum = foundAlbum;
                currentSongIndex = lastSongIndex;
                currentAlbumData.postValue(currentAlbum);

                if (currentSongIndex < currentAlbum.songs.size()) {
                    Song songToPlay = currentAlbum.songs.get(currentSongIndex);
                    currentSongData.postValue(songToPlay);
                    try {
                        mediaPlayer.reset();
                        mediaPlayer.setDataSource(getApplicationContext(), songToPlay.contentUri);
                        // Prepare the player but don't start it.
                        mediaPlayer.prepare();
                        // Seek to the last known position.
                        mediaPlayer.seekTo(lastPosition);
                        playbackPositionData.postValue(lastPosition);

                        // 1. Update the metadata for the display (e.g., car screen)
                        updateMediaSessionMetadata(songToPlay);

                        // 2. Update the session state to PAUSED.
                        //    This tells the system you are ready to receive a PLAY command.
                        updateMediaSessionState();

                    } catch (IOException e) {
                        Log.e(TAG, "Error loading last playback state", e);
                    }
                }
            }
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
