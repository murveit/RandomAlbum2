package com.murveit.randomalbum2;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.TelephonyManager;
import android.util.Log;

public class PhoneStateReceiver extends BroadcastReceiver {
    private static final String TAG = "PhoneStateReceiver";
    private static boolean isCallActive = false;
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

        // A call is incoming or active (ringing or off-hook)
        if (state.equals(TelephonyManager.EXTRA_STATE_RINGING) || state.equals(TelephonyManager.EXTRA_STATE_OFFHOOK)) {
            // Only act if a call is not already considered active.
            // This prevents the OFFHOOK event from overwriting the state set by the RINGING event.
            if (!isCallActive) {
                if (musicService.isPlaying()) {
                    wasPlayingWhenCallStarted = true;
                    musicService.pause(); // Pause the music
                } else {
                    wasPlayingWhenCallStarted = false;
                }
                isCallActive = true; // Mark that a call is now active.
            }
        }
        // The phone is now idle (call ended)
        else if (state.equals(TelephonyManager.EXTRA_STATE_IDLE)) {
            // If music was playing when the call started, resume it.
            if (wasPlayingWhenCallStarted) {
                Log.d(TAG, "Call ended, resuming playback.");
                musicService.play(); // It's clearer to call play() directly
            }
            // Reset flags now that the call is completely over.
            isCallActive = false;
            wasPlayingWhenCallStarted = false;
        }
    }

}
