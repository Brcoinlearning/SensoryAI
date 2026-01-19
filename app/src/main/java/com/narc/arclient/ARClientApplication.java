package com.narc.arclient;

import android.app.Application;

import com.ffalcon.mercury.android.sdk.MercurySDK;


public class ARClientApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        MercurySDK.INSTANCE.init(this);
    }
}
