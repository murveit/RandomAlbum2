package com.murveit.randomalbum2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;
import android.util.Log;

public class PhoneStateReceiver extends BroadcastReceiver {
    private static final String TAG = "PhoneStateReceiver";
    private static boolean wasPlayingWhenCallStarted = false;
    private final MusicService musicService;

    public PhoneStateReceiver(MusicService musicService) {
        this.musicService = musicService;
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent.getAction() == null || !intent.getAction().equals(TelephonyManager.ACTION_PHONE_STATE_CHANGED)) {
            return;
        }

        String state = intent.getStringExtra(TelephonyManager.EXTRA_STATE);
        Log.d(TAG, "Phone state changed to: " + state);

        if (musicService == null) {
            Log.e(TAG, "MusicService is null, cannot process phone state.");
            return;
        }

        if (state.equals(TelephonyManager.EXTRA_STATE_RINGING) || state.equals(TelephonyManager.EXTRA_STATE_OFFHOOK)) {
            // A call is incoming or has been answered, pause the music.
            if (musicService.isPlaying()) {
                wasPlayingWhenCallStarted = true;
                musicService.playPause(); // This will pause the playback
            } else {
                wasPlayingWhenCallStarted = false;
            }
        } else if (state.equals(TelephonyManager.EXTRA_STATE_IDLE)) {
            // The call has ended.
            if (wasPlayingWhenCallStarted) {
                musicService.playPause(); // This will resume the playback
                wasPlayingWhenCallStarted = false;
            }
        }
    }
}
