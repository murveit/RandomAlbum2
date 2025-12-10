package com.murveit.randomalbum2;

import android.Manifest;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.bumptech.glide.Glide;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private MusicService musicService;
    private boolean isBound = false;

    // UI Elements
    private ImageView ivAlbumArt;
    private TextView tvAlbumTitle, tvSongTitle, tvPlaybackInfo;
    // CHANGE: These are now ImageButtons
    private ImageButton btnPlayPause, btnPrevSong, btnNextSong, btnPrevAlbum, btnNextAlbum;
    // KEEP: This button remains
    private Button btnPickAlbum;

    private final ActivityResultLauncher<Intent> albumPickerLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(),
            result -> {
                if (result.getResultCode() == AppCompatActivity.RESULT_OK) {
                    Intent data = result.getData();
                    if (data != null && data.hasExtra(AlbumPickerActivity.EXTRA_SELECTED_ALBUM_ID)) {
                        long albumId = data.getLongExtra(AlbumPickerActivity.EXTRA_SELECTED_ALBUM_ID, -1L);
                        if (isBound && musicService != null && albumId != -1L) {
                            Log.d(TAG, "Received album ID from picker: " + albumId);
                            musicService.playAlbumById(albumId);
                        }
                    }
                } else {
                    Log.d(TAG, "Album picker was cancelled.");
                }
            });


    private ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            MusicService.MusicBinder binder = (MusicService.MusicBinder) service;
            musicService = binder.getService();
            isBound = true;
            Log.d(TAG, "MusicService connected");
            setupObservers();
        }

        @Override
        public void onServiceDisconnected(ComponentName arg0) {
            isBound = false;
            Log.d(TAG, "MusicService disconnected");
        }
    };

    // --- Permission Handling (No changes) ---
    private final ActivityResultLauncher<String[]> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestMultiplePermissions(), permissions -> {
                boolean allGranted = true;
                for (Boolean isGranted : permissions.values()) {
                    if (!isGranted) {
                        allGranted = false;
                        break;
                    }
                }
                if (allGranted) {
                    Log.d(TAG, "All permissions granted.");
                    startAndBindService();
                } else {
                    Log.e(TAG, "Not all permissions were granted.");
                    Toast.makeText(this, "Permissions are required to play music.", Toast.LENGTH_LONG).show();
                }
            });


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        initializeUI();
        checkAndRequestPermissions();
        setupClickListeners();
    }

    private void setupObservers() {
        if (!isBound) return;

        musicService.getCurrentAlbumData().observe(this, album -> {
            if (album == null) return;
            tvAlbumTitle.setText(album.title);
            Glide.with(this)
                    .load(album.coverArtUri)
                    .placeholder(android.R.drawable.ic_menu_gallery)
                    .error(android.R.drawable.ic_menu_gallery)
                    .into(ivAlbumArt);
        });

        musicService.getCurrentSongData().observe(this, song -> {
            if (song == null) return;
            tvSongTitle.setText(song.title);
        });

        musicService.getIsPlayingData().observe(this, isPlaying -> {
            // CHANGE: Update the icon resource instead of the text
            if (isPlaying) {
                btnPlayPause.setImageResource(android.R.drawable.ic_media_pause);
            } else {
                btnPlayPause.setImageResource(android.R.drawable.ic_media_play);
            }
        });

        musicService.getPlaybackPositionData().observe(this, position -> {
            if (musicService.getCurrentSongData().getValue() != null && musicService.getCurrentAlbumData().getValue() != null) {
                Song currentSong = musicService.getCurrentSongData().getValue();
                Album currentAlbum = musicService.getCurrentAlbumData().getValue();
                String progressText = String.format(Locale.getDefault(), "%s / %s",
                        formatDuration(position),
                        formatDuration(currentSong.duration));
                String trackInfo = String.format(Locale.getDefault(), "Track %d/%d",
                        musicService.getCurrentSongIndex() + 1,
                        currentAlbum.songs.size());
                tvPlaybackInfo.setText(trackInfo + "  |  " + progressText);
            }
        });
    }

    private String formatDuration(int durationMs) {
        long seconds = (durationMs / 1000) % 60;
        long minutes = (durationMs / (1000 * 60)) % 60;
        return String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds);
    }

    private void checkAndRequestPermissions() {
        // ... same as before
        List<String> permissionsToRequest = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_MEDIA_AUDIO);
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsToRequest.add(Manifest.permission.READ_EXTERNAL_STORAGE);
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) {
            permissionsToRequest.add(Manifest.permission.READ_PHONE_STATE);
        }

        if (!permissionsToRequest.isEmpty()) {
            requestPermissionLauncher.launch(permissionsToRequest.toArray(new String[0]));
        } else {
            startAndBindService();
        }
    }

    private void startAndBindService() {
        // ... same as before
        Intent serviceIntent = new Intent(this, MusicService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(serviceIntent);
        } else {
            startService(serviceIntent);
        }
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void initializeUI() {
        ivAlbumArt = findViewById(R.id.ivAlbumArt);
        tvAlbumTitle = findViewById(R.id.tvAlbumTitle);
        tvSongTitle = findViewById(R.id.tvSongTitle);
        tvPlaybackInfo = findViewById(R.id.tvPlaybackInfo);
        btnPlayPause = findViewById(R.id.btnPlayPause);
        btnPrevSong = findViewById(R.id.btnPrevSong);
        btnNextSong = findViewById(R.id.btnNextSong);
        btnPrevAlbum = findViewById(R.id.btnPrevAlbum);
        btnNextAlbum = findViewById(R.id.btnNextAlbum);
        btnPickAlbum = findViewById(R.id.btnPickAlbum);
    }

    private void setupClickListeners() {
        btnPlayPause.setOnClickListener(v -> { if (isBound) musicService.playPause(); });
        btnNextSong.setOnClickListener(v -> { if (isBound) musicService.nextSong(); });
        btnPrevSong.setOnClickListener(v -> { if (isBound) musicService.prevSong(); });
        btnNextAlbum.setOnClickListener(v -> { if (isBound) musicService.nextAlbum(); });
        btnPrevAlbum.setOnClickListener(v -> { if (isBound) musicService.prevAlbum(); });

        btnPickAlbum.setOnClickListener(v -> {
            if (isBound) {
                Intent intent = new Intent(MainActivity.this, AlbumPickerActivity.class);
                albumPickerLauncher.launch(intent);
            } else {
                Toast.makeText(this, "Service not ready yet.", Toast.LENGTH_SHORT).show();
            }
        });
        ivAlbumArt.setOnClickListener(v -> {
            if (isBound) {
                Intent intent = new Intent(MainActivity.this, AlbumPickerActivity.class);
                albumPickerLauncher.launch(intent);
            } else {
                Toast.makeText(this, "Service not ready yet.", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isBound) {
            unbindService(serviceConnection);
            isBound = false;
        }
    }
}

