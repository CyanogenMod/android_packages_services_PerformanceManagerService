/*
 * Copyright (C) 2014 The CyanogenMod Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.cyanogenmod.cmperformance.service;

import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.PowerManager;
import android.os.Process;
import android.os.IPowerManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;
import android.util.Slog;

import java.util.regex.Pattern;

import cyanogenmod.power.IPerformanceManager;
import cyanogenmod.power.PerformanceManager;
import cyanogenmod.power.PerformanceManagerInternal;
import cyanogenmod.providers.CMSettings;

import org.cyanogenmod.internal.power.IPerformanceManagerProvider;

/** @hide */
public class PerformanceManagerService extends Service {

    private static final String TAG = "PerformanceManager";

    static {
        System.loadLibrary("cmperformancemanager_jni");
        Log.d(TAG, "Loaded jni library");
    }

    private HandlerThread mHandlerThread;
    private PerformanceManagerHandler mHandler;
    private IPowerManager mPm;

    private Pattern[] mPatterns = null;
    private int[] mProfiles = null;

    /** Active profile that based on low power mode, user and app rules */
    private int mCurrentProfile = -1;
    private int mNumProfiles = 0;

    // keep in sync with hardware/libhardware/include/hardware/power.h
    private final int POWER_HINT_CPU_BOOST    = 0x00000010;
    private final int POWER_HINT_LAUNCH_BOOST = 0x00000011;
    private final int POWER_HINT_SET_PROFILE  = 0x00000030;


    private boolean mLowPowerModeEnabled = false;
    private String mCurrentActivityName = null;

    // Max time (microseconds) to allow a CPU boost for
    private static final int MAX_CPU_BOOST_TIME = 5000000;
    private static final boolean DEBUG = true;

    @Override
    public void onCreate() {
        Log.d(TAG, "onCreate() called with: " + "");
        super.onCreate();

        String[] activities = getResources().getStringArray(
                R.array.config_auto_perf_activities);

        if (activities != null && activities.length > 0) {
            mPatterns = new Pattern[activities.length];
            mProfiles = new int[activities.length];
            for (int i = 0; i < activities.length; i++) {
                String[] info = activities[i].split(",");
                if (info.length == 2) {
                    mPatterns[i] = Pattern.compile(info[0]);
                    mProfiles[i] = Integer.valueOf(info[1]);
                    if (DEBUG) {
                        Slog.d(TAG, String.format("App profile #%d: %s => %s",
                                i, info[0], info[1]));
                    }
                }
            }
        }

        mPm = IPowerManager.Stub.asInterface(ServiceManager.getService(Context.POWER_SERVICE));

        // We need a high priority thread to handle these requests in front of
        // everything else asynchronously
        mHandlerThread = new HandlerThread(TAG,
                Process.THREAD_PRIORITY_URGENT_DISPLAY + 1);
        mHandlerThread.start();

        mHandler = new PerformanceManagerHandler(mHandlerThread.getLooper());

        mNumProfiles = PerformanceManager.getInstance(this).getNumberOfProfiles();
        if (mNumProfiles > 0) {
            int profile = getUserProfile();
            if (profile == PerformanceManager.PROFILE_HIGH_PERFORMANCE) {
                Slog.i(TAG, String.format("Reverting profile %d to %d",
                        profile, PerformanceManager.PROFILE_BALANCED));
                setPowerProfileInternal(
                        PerformanceManager.PROFILE_BALANCED, true);
            } else {
                setPowerProfileInternal(profile, false);
            }
        }
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy() called with: " + "");
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
        mHandlerThread.quit();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private boolean hasAppProfiles() {
        return mNumProfiles > 0 && mPatterns != null &&
               (CMSettings.Secure.getInt(getContentResolver(),
                       CMSettings.Secure.APP_PERFORMANCE_PROFILES_ENABLED, 1) == 1);
    }

    private boolean getProfileHasAppProfilesInternal(int profile) {
        if (profile < 0 || profile > mNumProfiles) {
            Slog.e(TAG, "Invalid profile: " + profile);
            return false;
        }

        if (profile == PerformanceManager.PROFILE_BALANCED) {
            return mPatterns != null;
        }

        return false;
    }

    /**
     * Get the profile saved by the user
     */
    private int getUserProfile() {
        return CMSettings.Secure.getInt(getContentResolver(),
                CMSettings.Secure.PERFORMANCE_PROFILE,
                PerformanceManager.PROFILE_BALANCED);
    }

    /**
     * Apply a power profile and persist if fromUser = true
     *
     * @param  profile  power profile
     * @param  fromUser true to persist the profile
     * @return          true if the active profile changed
     */
    private synchronized boolean setPowerProfileInternal(int profile, boolean fromUser) {
        if (DEBUG) {
            Slog.v(TAG, String.format(
                "setPowerProfileInternal(profile=%d, fromUser=%b)",
                profile, fromUser));
        }
        // Enforce the performance access permission declared by cm's res package
        enforceCallingOrSelfPermission(
                cyanogenmod.platform.Manifest.permission.PERFORMANCE_ACCESS, null);
        if (mPm == null) {
            Slog.e(TAG, "System is not ready, dropping profile request");
            return false;
        }
        if (profile < 0 || profile > mNumProfiles) {
            Slog.e(TAG, "Invalid profile: " + profile);
            return false;
        }

        boolean isProfileSame = profile == mCurrentProfile;

        if (!isProfileSame) {
            if (profile == PerformanceManager.PROFILE_POWER_SAVE) {
                // Handle the case where toggle power saver mode
                // failed
                long token = Binder.clearCallingIdentity();
                try {
                    if (!mPm.setPowerSaveMode(true)) {
                        return false;
                    }
                } catch (RemoteException e) {
                    // todo?
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            } else if (mCurrentProfile == PerformanceManager.PROFILE_POWER_SAVE) {
                long token = Binder.clearCallingIdentity();
                try {
                    if (!mPm.setPowerSaveMode(false)) {
                        return false;
                    }
                } catch (RemoteException e) {
                    // todo?
                } finally {
                    Binder.restoreCallingIdentity(token);
                }
            }
        }

        /**
         * It's possible that mCurrrentProfile != getUserProfile() because of a
         * per-app profile. Store the user's profile preference and then bail
         * early if there is no work to be done.
         */
        if (fromUser) {
            CMSettings.Secure.putInt(getContentResolver(),
                    CMSettings.Secure.PERFORMANCE_PROFILE, profile);
        }

        if (isProfileSame) {
            return false;
        }

        mCurrentProfile = profile;
        mHandler.obtainMessage(MSG_SET_PROFILE, profile,
                               (fromUser ? 1 : 0)).sendToTarget();

        return true;
    }

    private int getProfileForActivity(String componentName) {
        if (componentName != null) {
            for (int i = 0; i < mPatterns.length; i++) {
                if (mPatterns[i].matcher(componentName).matches()) {
                    return mProfiles[i];
                }
            }
        }
        return PerformanceManager.PROFILE_BALANCED;
    }

    private void cpuBoostInternal(int duration) {
        synchronized (PerformanceManagerService.this) {
            if (mPm == null) {
                Slog.e(TAG, "System is not ready, dropping cpu boost request");
                return;
            }
        }
        if (duration > 0 && duration <= MAX_CPU_BOOST_TIME) {
            // Don't send boosts if we're in another power profile
            if (mCurrentProfile == PerformanceManager.PROFILE_POWER_SAVE ||
                    mCurrentProfile == PerformanceManager.PROFILE_HIGH_PERFORMANCE) {
                return;
            }
            mHandler.obtainMessage(MSG_CPU_BOOST, duration, 0).sendToTarget();
        } else {
            Slog.e(TAG, "Invalid boost duration: " + duration);
        }
    }

    private void applyProfile(boolean fromUser) {
        if (mNumProfiles < 1) {
            // don't have profiles, bail.
            return;
        }

        int profile;
        if (mLowPowerModeEnabled) {
            // LPM always wins
            profile = PerformanceManager.PROFILE_POWER_SAVE;
        } else if (fromUser && mCurrentProfile == PerformanceManager.PROFILE_POWER_SAVE) {
            profile = PerformanceManager.PROFILE_BALANCED;
        } else {
            profile = getUserProfile();
            // use app specific rules if profile is balanced
            if (hasAppProfiles() && getProfileHasAppProfilesInternal(profile)) {
                profile = getProfileForActivity(mCurrentActivityName);
            }
        }
        setPowerProfileInternal(profile, fromUser);
    }

    private final IBinder mBinder = new IPerformanceManagerProvider.Stub() {

        @Override
        public boolean setPowerProfile(int profile) {
            return setPowerProfileInternal(profile, true);
        }

        @Override
        public void cpuBoost(int duration) {
            cpuBoostInternal(duration);
        }

        @Override
        public int getPowerProfile() {
            return getUserProfile();
        }

        @Override
        public boolean getProfileHasAppProfiles(int profile) {
            return getProfileHasAppProfilesInternal(profile);
        }

        @Override
        public void launchBoost(int pid, String packageName) throws RemoteException {
            synchronized (PerformanceManagerService.this) {
                if (mPm == null) {
                    Slog.e(TAG, "System is not ready, dropping launch boost request");
                    return;
                }
            }
            // Don't send boosts if we're in another power profile
            if (mCurrentProfile == PerformanceManager.PROFILE_POWER_SAVE ||
                    mCurrentProfile == PerformanceManager.PROFILE_HIGH_PERFORMANCE) {
                return;
            }
            mHandler.obtainMessage(MSG_LAUNCH_BOOST, pid, 0, packageName).sendToTarget();
        }

        @Override
        public void activityResumed(Intent intent) throws RemoteException {
            String activityName = null;
            if (intent != null) {
                final ComponentName cn = intent.getComponent();
                if (cn != null) {
                    activityName = cn.flattenToString();
                }
            }

            mCurrentActivityName = activityName;
            applyProfile(false);
        }

        @Override
        public void onLowPowerModeChanged(boolean enabled) {
            if (enabled == mLowPowerModeEnabled) {
                return;
            }
            if (DEBUG) {
                Slog.d(TAG, "low power mode enabled: " + enabled);
            }
            mLowPowerModeEnabled = enabled;
            applyProfile(true);
        }
    };

    private static final int MSG_CPU_BOOST = 1;
    private static final int MSG_LAUNCH_BOOST = 2;
    private static final int MSG_SET_PROFILE = 3;

    /**
     * Handler for asynchronous operations performed by the performance manager.
     */
    private final class PerformanceManagerHandler extends Handler {
        public PerformanceManagerHandler(Looper looper) {
            super(looper, null, true /*async*/);
        }

        @Override
        public void handleMessage(Message msg) {
            long token;
            switch (msg.what) {
                case MSG_CPU_BOOST:
                    token = Binder.clearCallingIdentity();
                    try {
                        mPm.powerHint(POWER_HINT_CPU_BOOST, msg.arg1);
                    } catch (RemoteException e) {
                        // ignored
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }
                    break;
                case MSG_LAUNCH_BOOST:
                    int pid = msg.arg1;
                    String packageName = (String) msg.obj;
                    native_launchBoost(pid, packageName);
                    break;
                case MSG_SET_PROFILE:
                    token = Binder.clearCallingIdentity();
                    try {
                        mPm.powerHint(POWER_HINT_SET_PROFILE, msg.arg1);
                    } catch (RemoteException e) {
                        // ignored
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }

                    break;
            }
        }
    }


    private native void native_launchBoost(int pid, String packageName);
}
