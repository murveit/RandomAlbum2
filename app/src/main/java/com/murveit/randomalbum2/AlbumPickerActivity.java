package com.murveit.randomalbum2;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.MenuItem;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class AlbumPickerActivity extends AppCompatActivity {

    public static final String EXTRA_SELECTED_ALBUM_ID = "com.murveit.randomalbum2.EXTRA_SELECTED_ALBUM_ID";
    private static final String TAG = "AlbumPickerActivity";

    private RecyclerView recyclerView;
    private AlbumAdapter albumAdapter;
    private MusicService musicService;
    private boolean isBound = false;

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            MusicService.MusicBinder binder = (MusicService.MusicBinder) service;
            musicService = binder.getService();
            isBound = true;
            Log.d(TAG, "MusicService connected. Loading albums into picker.");
            // Once the service is connected, we can get the album list
            populateAlbumList();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            isBound = false;
            Log.d(TAG, "MusicService disconnected.");
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_album_picker);

        // Add a back arrow to the toolbar
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("Select an Album");
        }

        recyclerView = findViewById(R.id.rvAlbums);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));

        // Bind to the service to get the list of albums
        Intent intent = new Intent(this, MusicService.class);
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);
    }

    private void populateAlbumList() {
        if (!isBound || musicService == null) {
            Log.e(TAG, "Service not bound, cannot populate album list.");
            return;
        }

        // We need a way to get all albums from the service. Let's add a getter there.
        List<Album> allAlbums = musicService.getAllAlbums();

        albumAdapter = new AlbumAdapter(allAlbums, album -> {
            Log.d(TAG, "User selected album: " + album.title);
            Intent resultIntent = new Intent();
            resultIntent.putExtra(EXTRA_SELECTED_ALBUM_ID, album.id);
            setResult(RESULT_OK, resultIntent);
            finish(); // Close the picker and return to MainActivity
        });

        recyclerView.setAdapter(albumAdapter);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        // Handle the back arrow click
        if (item.getItemId() == android.R.id.home) {
            setResult(RESULT_CANCELED);
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
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
