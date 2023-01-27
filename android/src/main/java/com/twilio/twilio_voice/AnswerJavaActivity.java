package com.twilio.twilio_voice;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.SharedPreferences;
import android.nfc.Tag;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.ImageView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.twilio.voice.Call;
import com.twilio.voice.CallException;
import com.twilio.voice.CallInvite;


public class AnswerJavaActivity extends AppCompatActivity {

    private static String TAG = "AnswerActivity";
    public static final String TwilioPreferences = "com.twilio.twilio_voicePreferences";

    private NotificationManager notificationManager;
    private boolean isReceiverRegistered = false;
    private VoiceBroadcastReceiver voiceBroadcastReceiver;

    private boolean initiatedDisconnect = false;

    private CallInvite activeCallInvite;
    private int activeCallNotificationId;
    private static final int MIC_PERMISSION_REQUEST_CODE = 17893;
    private PowerManager.WakeLock wakeLock;
    private TextView tvUserName;
    private TextView tvCallStatus;
    private ImageView btnAnswer;
    private ImageView btnReject;
    Call.Listener callListener = callListener();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate: AnswerJavaActivity created");
        setContentView(R.layout.activity_answer);

        // Because for some reason, this activity loses the activeCall variable when ending the call from

        activeCall = TwilioVoicePlugin.getActiveCall();

        tvUserName = (TextView) findViewById(R.id.tvUserName);
        tvCallStatus = (TextView) findViewById(R.id.tvCallStatus);
        btnAnswer = (ImageView) findViewById(R.id.btnAnswer);
        btnReject = (ImageView) findViewById(R.id.btnReject);

        KeyguardManager kgm = (KeyguardManager) getSystemService(Context.KEYGUARD_SERVICE);
        boolean isKeyguardUp = kgm.inKeyguardRestrictedInputMode();

        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        voiceBroadcastReceiver = new VoiceBroadcastReceiver();
        registerReceiver();

        Log.d(TAG, "isKeyguardUp in AnswerActivity -> " + isKeyguardUp);
        if (isKeyguardUp) {

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setTurnScreenOn(true);
                setShowWhenLocked(true);
//                kgm.requestDismissKeyguard(this, null);
            } else {
                PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.FULL_WAKE_LOCK, TAG);
                wakeLock.acquire(60 * 1000L /*10 minutes*/);

                getWindow().addFlags(
                        WindowManager.LayoutParams.FLAG_FULLSCREEN |
                                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD |
                                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                );
            }

        }

        handleIncomingCallIntent(getIntent());
    }

    private void handleIncomingCallIntent(Intent intent) {
        if (intent != null && intent.getAction() != null) {
            String action = intent.getAction();
            Log.d(TAG, "handleIncomingCallIntent for action -> " + action);

            String source = intent.getStringExtra("Source");
            if (source != null) {
                Log.d(TAG, "handleIncomingCallIntent: Source -> " + source);
            }

            activeCallInvite = intent.getParcelableExtra(Constants.INCOMING_CALL_INVITE);
            activeCallNotificationId = intent.getIntExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, 0);
            tvCallStatus.setText(R.string.incoming_call_title);
            switch (action) {
                case Constants.ACTION_INCOMING_CALL:
                case Constants.ACTION_INCOMING_CALL_NOTIFICATION:
                    configCallUI();
                    break;
                case Constants.ACTION_CANCEL_CALL:
                    newCancelCallClickListener();
                    break;
                case Constants.ACTION_ACCEPT:
                    checkPermissionsAndAccept();
                    break;
                case Constants.ACTION_END_CALL:
                    Log.d(TAG, "ending call" + activeCall != null ? "TRue" : "False");
                    try {
                        getActiveCall().disconnect();
                        initiatedDisconnect = true;
                    } catch (NullPointerException e) {
                        e.printStackTrace();
                        Log.e(TAG, "handleIncomingCallIntent: " + e.getMessage());
                    } finally {
                        // This removes the app from the app switcher/recents
                        // This works because we use a different taskAffinity of this activity from the
                        // main flutter application
                        finishAndRemoveTask();
                    }
                    break;
                case Constants.ACTION_TOGGLE_MUTE:
                    boolean muted = activeCall.isMuted();
                    activeCall.mute(!muted);
                    break;
                default: {
                }
            }
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d(TAG, "onNewIntent-");
        if (intent != null && intent.getAction() != null) {
            Log.d(TAG, intent.getAction());
            switch (intent.getAction()) {
                case Constants.ACTION_CANCEL_CALL:
                    newCancelCallClickListener();
                    break;
                default: {
                }
            }
        }
    }


    private void configCallUI() {
        Log.d(TAG, "configCallUI");
        if (activeCallInvite != null) {

            String fromId = activeCallInvite.getFrom().replace("client:", "");
            SharedPreferences preferences = getApplicationContext().getSharedPreferences(TwilioPreferences, Context.MODE_PRIVATE);
            String caller = preferences.getString(fromId, preferences.getString("defaultCaller", getString(R.string.unknown_caller)));
            tvUserName.setText(fromId);

            btnAnswer.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    Log.d(TAG, "onCLick");
                    checkPermissionsAndAccept();
                }
            });

            btnReject.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    rejectCallClickListener();
                }
            });
        }
    }

    private void checkPermissionsAndAccept() {
        Log.d(TAG, "Clicked accept");
        if (!checkPermissionForMicrophone()) {
            Log.d(TAG, "configCallUI-requestAudioPermissions");
            requestAudioPermissions();
        } else {
            Log.d(TAG, "configCallUI-newAnswerCallClickListener");
            acceptCall();
        }
    }


    private void acceptCall() {
        Log.d(TAG, "Accepting call");
        Intent acceptIntent = new Intent(this, IncomingCallNotificationService.class);
        acceptIntent.setAction(Constants.ACTION_ACCEPT);
        acceptIntent.putExtra(Constants.INCOMING_CALL_INVITE, activeCallInvite);
        acceptIntent.putExtra(Constants.ACCEPT_CALL_ORIGIN, 1);
        acceptIntent.putExtra(Constants.INCOMING_CALL_NOTIFICATION_ID, activeCallNotificationId);
        acceptIntent.putExtra("Source", "AnswerActivity#acceptCall");
        Log.d(TAG, "Clicked accept startService");
        // Starting the service calls endForeground in IncomingCallNotificationService to remove the notification
        startService(acceptIntent);
        Log.d(TAG, "Answering call");
        activeCallInvite.accept(this, callListener);
        Log.d(TAG, "acceptCall: canceling notification with id -> " + activeCallNotificationId);
        notificationManager.cancel(activeCallNotificationId);

        // Codes below don't work as expected, answering the call with activeCallInvite.accept()
        // Connects the call and launches the BackgroundCallJavaActivity as intended
//        startService(acceptIntent);
//        if (TwilioVoicePlugin.hasStarted) {
//            finish();
//        } else {
//            Log.d(TAG, "Answering call");
//            activeCallInvite.accept(this, callListener);
//            notificationManager.cancel(activeCallNotificationId);
//        }
    }

    // This is called in the Twilio call listener when the call connects
    // Which is async and there might be an unintended delay that'll retain the call ringing page
    // even after the call is picked up like nothing happened.
    // Solution would be to have a Connecting... state while waiting for the call listener
    private void startAnswerActivity(Call call) {
        Intent intent = new Intent(this, BackgroundCallJavaActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra(Constants.CALL_FROM, call.getFrom());
        startActivity(intent);
        Log.d(TAG, "Connected");

        // End AnswerJavaActivity
        finish();
    }

    private void endCall() {
        Log.d(TAG, "endCall: with initiatedDisconnect -> " + initiatedDisconnect);

        if (!initiatedDisconnect) {
            Intent intent = new Intent(this, BackgroundCallJavaActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            intent.setAction(Constants.ACTION_CANCEL_CALL);

            this.startActivity(intent);
            finish();
        }

    }

    Call activeCall;

    private  Call getActiveCall() {
        if (activeCall != null) return activeCall;
        return TwilioVoicePlugin.getActiveCall();
    }

    private Call.Listener callListener() {
        return new Call.Listener() {


            @Override
            public void onConnectFailure(@NonNull Call call, @NonNull CallException error) {
                Log.d(TAG, "Connect failure");
                Log.e(TAG, "Call Error: %d, %s" + error.getErrorCode() + error.getMessage());
            }

            @Override
            public void onRinging(@NonNull Call call) {

            }

            @Override
            public void onConnected(@NonNull Call call) {
                activeCall = call;
                // Update the call variable sent to Flutter
                TwilioVoicePlugin.setActiveCall(call);
                startAnswerActivity(call);
            }

            @Override
            public void onReconnecting(@NonNull Call call, @NonNull CallException callException) {
                Log.d(TAG, "onReconnecting");
            }

            @Override
            public void onReconnected(@NonNull Call call) {
                Log.d(TAG, "onReconnected");
            }

            @Override
            public void onDisconnected(@NonNull Call call, CallException error) {
                Log.d(TAG, "Disconnected");
                endCall();
            }

        };
    }

    private class VoiceBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "Received broadcast for action " + action);

            if (action != null)
                switch (action) {
                    case Constants.ACTION_INCOMING_CALL:
                    case Constants.ACTION_CANCEL_CALL:
                    case Constants.ACTION_TOGGLE_MUTE:
                    case Constants.ACTION_END_CALL:
                        /*
                         * Handle the incoming or cancelled call invite
                         */
                        Log.d(TAG, "received intent to answerActivity");
                        handleIncomingCallIntent(intent);
                        break;
                    default:
                        Log.d(TAG, "Received broadcast for other action " + action);
                        break;

                }
        }
    }

    private void registerReceiver() {
        Log.d(TAG, "Registering answerJavaActivity receiver");
        if (!isReceiverRegistered) {
            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(Constants.ACTION_TOGGLE_MUTE);
            intentFilter.addAction(Constants.ACTION_CANCEL_CALL);
            intentFilter.addAction(Constants.ACTION_END_CALL);
            LocalBroadcastManager.getInstance(this).registerReceiver(
                    voiceBroadcastReceiver, intentFilter);
            isReceiverRegistered = true;
        }
    }

    private void unregisterReceiver() {
        Log.d(TAG, "Unregistering receiver");
        if (isReceiverRegistered) {
            LocalBroadcastManager.getInstance(this).unregisterReceiver(voiceBroadcastReceiver);
            isReceiverRegistered = false;
        }
    }


    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver();
    }

    // We still want to listen messages from backgroundCallJavaActivity
//    @Override
//    protected void onPause() {
//        super.onPause();
//        unregisterReceiver();
//    }

    private void newCancelCallClickListener() {
        finishAndRemoveTask();
    }

    private void rejectCallClickListener() {
        Log.d(TAG, "Reject Call Click listener");
        if (activeCallInvite != null) {
            Intent rejectIntent = new Intent(this, IncomingCallNotificationService.class);
            rejectIntent.setAction(Constants.ACTION_REJECT);
            rejectIntent.putExtra(Constants.INCOMING_CALL_INVITE, activeCallInvite);
            startService(rejectIntent);
            finishAndRemoveTask();
        }
    }

    private Boolean checkPermissionForMicrophone() {
        int resultMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO);
        return resultMic == PackageManager.PERMISSION_GRANTED;
    }

    private void requestAudioPermissions() {
        String[] permissions = {Manifest.permission.RECORD_AUDIO};
        Log.d(TAG, "requestAudioPermissions");
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            if (ActivityCompat.shouldShowRequestPermissionRationale(this, Manifest.permission.RECORD_AUDIO)) {
                ActivityCompat.requestPermissions(this, permissions, MIC_PERMISSION_REQUEST_CODE);
            } else {
                ActivityCompat.requestPermissions(this, permissions, MIC_PERMISSION_REQUEST_CODE);
            }
        } else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "requestAudioPermissions-> permission granted->newAnswerCallClickListener");
            acceptCall();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if (requestCode == MIC_PERMISSION_REQUEST_CODE) {
            if (grantResults.length == 0 || grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Microphone permissions needed. Please allow in your application settings.", Toast.LENGTH_LONG).show();
                rejectCallClickListener();
            } else {
                acceptCall();
            }
        } else {
            throw new IllegalStateException("Unexpected value: " + requestCode);
        }
    }

    @Override
    protected void onDestroy() {
        Log.d(TAG, "AnserJAvaActivity ondestroy");
//        unregisterReceiver();
        super.onDestroy();
        if (wakeLock != null) {
            wakeLock.release();
        }
    }

}
