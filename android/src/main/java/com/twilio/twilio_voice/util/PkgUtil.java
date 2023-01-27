package com.twilio.twilio_voice.util;

import android.app.PendingIntent;
public class PkgUtil {

    public static int getPendingIntentFlag() {
        return PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE;
    }

}
