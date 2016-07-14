package org.cyanogenmod.cmperformance.service;

import android.app.Application;
import android.util.Log;

public class CMPerformanceApplication extends Application {

    private static final String TAG = "CMPerformanceApplication";

    static {
//        System.loadLibrary("");
        Log.d(TAG, "Loaded jni library");
    }

    @Override
    public void onCreate() {
        super.onCreate();
    }
}
