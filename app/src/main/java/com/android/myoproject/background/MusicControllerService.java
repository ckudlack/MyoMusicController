package com.android.myoproject.background;

import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.media.AudioManager;
import android.os.Handler;
import android.os.IBinder;
import android.support.v4.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;

import com.android.myoproject.BusEvent;
import com.android.myoproject.R;
import com.android.myoproject.application.MyoApplication;
import com.android.myoproject.callbacks.DeviceCallback;
import com.android.myoproject.custommyo.MyoDeviceListener;
import com.google.sample.castcompanionlibrary.cast.BaseCastManager;
import com.google.sample.castcompanionlibrary.cast.VideoCastManager;
import com.squareup.otto.Subscribe;
import com.thalmic.myo.Arm;
import com.thalmic.myo.Hub;
import com.thalmic.myo.Myo;
import com.thalmic.myo.Pose;

public class MusicControllerService extends Service implements DeviceCallback {

    private static float ROLL_THRESHOLD = 1.3f;
    private static float PITCH_THRESHOLD = 0.0f;
    private static float YAW_THRESHOLD = 0.0f;

    private static float UNLOCK_THRESHOLD = 15;
    private static int BLOCK_TIME = 2000;
    private static final int NOTIFICATION_ID = 50990;

    private boolean blockEverything = false;

    private double currentRoll = 0;
    private double currentYaw = 0;
    private double currentPitch = 0;

    private boolean fistMade = false;
    private boolean lockToggleMode = false;
    private boolean unlocked = false;

    private double referenceRoll = 0;
    private double referenceYaw = 0;
    private double referencePitch = 0;

    private Myo currentMyo;

    private AudioManager audioManager;
    private MyoDeviceListener deviceListener;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        MyoApplication.bus.register(this);

        audioManager = (AudioManager) getSystemService(Context.AUDIO_SERVICE);
        deviceListener = new MyoDeviceListener(this, this);

        Hub hub = Hub.getInstance();
        if (!hub.init(this, getPackageName())) {
            Log.e("TAG", "Could not init Hub");
            this.stopSelf();
            return;
        }

        hub.addListener(deviceListener);
        hub.attachToAdjacentMyo();

        //Create an Intent for the BroadcastReceiver
        Intent buttonIntent = new Intent(this, ButtonReceiver.class);
        buttonIntent.putExtra("notificationId", NOTIFICATION_ID);

        //Create the PendingIntent
        PendingIntent btPendingIntent = PendingIntent.getBroadcast(this, 0, buttonIntent, 0);

        NotificationCompat.Builder builder =
                new NotificationCompat.Builder(this)
                        .setContentTitle("Myo")
                        .setOngoing(true)
                        .setSmallIcon(R.drawable.ic_launcher)
                        .setContentText("Running")
                        .addAction(R.drawable.ic_action_cancel, "Close", btPendingIntent);

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        manager.notify(NOTIFICATION_ID, builder.build());
    }

    private void handleCast() {
        VideoCastManager castManager;
        castManager = VideoCastManager.initialize(this, "appId", null, null);
        castManager.enableFeatures(BaseCastManager.FEATURE_LOCKSCREEN);
        castManager.updateVolume(1);
    }

    @Override
    public void onDestroy() {
        Hub.getInstance().getScanner().stopScanning();
        Hub.getInstance().removeListener(deviceListener);
        Hub.getInstance().shutdown();

        MyoApplication.bus.unregister(this);
        Log.d("TAG", "Service stopped");

        super.onDestroy();
    }

    private void setToUnlockMode() {
        if (!blockEverything && !lockToggleMode) {
            lockToggleMode = true;

            new Handler().postDelayed(new Runnable() {
                @Override
                public void run() {
                    lockToggleMode = false;
                }
            }, 500);
        }
    }

    private void toggleLock() {
        // Unlocked when the user does the THUMB_TO_PINKY gesture and a clockwise hand rotation within 500ms

        lockToggleMode = false;
        unlocked = !unlocked;

        blockEverything = true;

        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                blockEverything = false;
            }
        }, BLOCK_TIME);

        currentMyo.vibrate(Myo.VibrationType.SHORT);
        currentMyo.vibrate(Myo.VibrationType.SHORT);

        Toast.makeText(this, unlocked ? "Unlocked" : "Locked", Toast.LENGTH_SHORT).show();
    }

    private void volUp() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI);
    }

    private void volDown() {
        audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI);
    }

    @Override
    public void handlePose(Pose pose) {
        switch (pose) {
            case FINGERS_SPREAD:
                if (unlocked) {
                    currentMyo.vibrate(Myo.VibrationType.SHORT);
                    playOrPause();
                    Toast.makeText(this, pose.name(), Toast.LENGTH_SHORT).show();
                }
                break;
            case FIST:
                if (unlocked && !fistMade) {
                    fistMade = true;
                }
                break;
            case REST:
                resetFist();
                break;
            case THUMB_TO_PINKY:
                setToUnlockMode();
                break;
            case WAVE_IN:
                if (unlocked) {
                    currentMyo.vibrate(Myo.VibrationType.SHORT);
                    goToPrevSong();
                    Toast.makeText(this, pose.name(), Toast.LENGTH_SHORT).show();
                }
                break;
            case WAVE_OUT:
                if (unlocked) {
                    currentMyo.vibrate(Myo.VibrationType.SHORT);
                    goToNextSong();
                    Toast.makeText(this, pose.name(), Toast.LENGTH_SHORT).show();
                }
                break;
            default:
                break;
        }
    }

    @Override
    public void resetFist() {
        fistMade = false;
        referenceRoll = currentRoll;
    }

    @Override
    public void setCurrentMyo(Myo myo) {
        this.currentMyo = myo;
    }

    @Override
    public void toggleUnlocked(boolean isUnlocked) {
        unlocked = isUnlocked;
    }

    @Override
    public void handleRotationCalc(float pitch, float roll, float yaw) {
        if (fistMade) {
            handleRoll(roll);
        }

        if (lockToggleMode) {
            double subtractive = currentRoll - referenceRoll;

            if (subtractive > UNLOCK_THRESHOLD) {
                toggleLock();
                referenceRoll = currentRoll;
            }
        }
    }

    @Override
    public void initReferenceRoll() {
        referenceRoll *= -1;
    }

    private int handleAxis(double current, double reference, float threshold) {
        double subtractive = current - reference;
        if (subtractive > threshold) {
            reference = current;
            return 1;
        } else if (subtractive < -threshold) {
            reference = current;
            return -1;
        } else {
            return 0;
        }
    }

    private void handleRoll(float roll) {
        currentRoll = roll;

        double subtractive = currentRoll - referenceRoll;
        if (subtractive > ROLL_THRESHOLD) {
            volUp();
            referenceRoll = currentRoll;
        } else if (subtractive < -ROLL_THRESHOLD) {
            volDown();
            referenceRoll = currentRoll;
        }
    }

    private void handlePitch(float pitch) {
        currentPitch = pitch;

        double subtractive = currentPitch - referencePitch;
        if (subtractive > PITCH_THRESHOLD) {
//            volUp();
            referencePitch = currentPitch;
        } else if (subtractive < -PITCH_THRESHOLD) {
//            volDown();
            referencePitch = currentPitch;
        }
    }

    private void handleYaw(float yaw) {
        currentYaw = yaw;

        double subtractive = currentYaw - referenceYaw;
        if (subtractive > YAW_THRESHOLD) {
//            volUp();
            referenceYaw = currentYaw;
        } else if (subtractive < -YAW_THRESHOLD) {
//            volDown();
            referenceYaw = currentYaw;
        }
    }

    private void goToNextSong() {
        Intent next = new Intent("com.android.music.musicservicecommand");
        next.putExtra("command", deviceListener.getArm() == Arm.RIGHT ? "next" : "previous");
        sendBroadcast(next);
    }

    private void goToPrevSong() {
        Intent previous = new Intent("com.android.music.musicservicecommand");
        previous.putExtra("command", deviceListener.getArm() == Arm.RIGHT ? "previous" : "next");
        sendBroadcast(previous);
    }

    private void playOrPause() {
        Intent pause = new Intent("com.android.music.musicservicecommand");
        pause.putExtra("command", "togglepause");
        sendBroadcast(pause);
    }

    @Subscribe
    public void playbackUpdated(BusEvent.PlaybackUpdatedEvent event) {
        Log.d("TAG", "Got player callback");
    }

    @Subscribe
    public void destroyServiceEvent(BusEvent.DestroyServiceEvent event) {
        this.stopSelf();
    }
}
