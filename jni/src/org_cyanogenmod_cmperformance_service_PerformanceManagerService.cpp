/*
**
** Copyright 2016, The CyanogenMod Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

//#define LOG_NDEBUG 0

#define LOG_TAG "PerformanceManagerService-JNI"

#include <utils/Log.h>

#include <JNIHelp.h>
#include <jni.h>
#include "core_jni_helpers.h"

#include <hardware/power.h>

// ----------------------------------------------------------------------------

namespace android {



static struct power_module* gPowerModule;

// ----------------------------------------------------------------------------

static void
org_cyanogenmod_cmperformance_service_PerformanceManagerService_launchBoost(
        JNIEnv *env, jobject thiz, jint pid, jstring jPackageName)
{
    if (env == NULL || jPackageName == NULL) {
        return;
    }

    if (gPowerModule && gPowerModule->powerHint) {
        const char *packageName = env->GetStringUTFChars(jPackageName, 0);
        launch_boost_info_t *info = (launch_boost_info_t *)malloc(sizeof(launch_boost_info_t));
        info->pid = pid;
        info->packageName = packageName;
        gPowerModule->powerHint(gPowerModule, POWER_HINT_LAUNCH_BOOST, (void *)info);
        ALOGV("Sent LAUNCH BOOST for %s (pid=%d)", info->packageName, info->pid);

        env->ReleaseStringUTFChars(jPackageName, packageName);
        free(info);
    }
}

} /* namespace android */

// ----------------------------------------------------------------------------

static const char* const kClassPathName =
    "org/cyanogenmod/cmperformance/service/PerformanceManagerService";

static JNINativeMethod gMethods[] = {
     {"native_launchBoost", "(ILjava/lang/String;)V",
            (void *)android::org_cyanogenmod_cmperformance_service_PerformanceManagerService_launchBoost},
};

static int registerNativeMethods(JNIEnv* env, const char* className,
        JNINativeMethod* gMethods, int numMethods) {
    jclass clazz;
    clazz = env->FindClass(className);
    if (clazz == NULL) {
        ALOGE("Native registration unable to find class '%s'", className);
        return JNI_FALSE;
    }
    if (env->RegisterNatives(clazz, gMethods, numMethods) < 0) {
        ALOGE("RegisterNatives failed for '%s'", className);
        return JNI_FALSE;
    }
    return JNI_TRUE;
}

jint JNI_OnLoad(JavaVM* vm, void* reserved)
{
    ALOGV("OnLoad");
    JNIEnv* env = 0;

    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        ALOGE("Error: GetEnv failed in JNI_OnLoad");
        return -1;
    }
    jclass serviceClass = env->FindClass(kClassPathName);
    if (!serviceClass) {
        ALOGE("Failed to get class reference");
        return -1;
    }

    if (!registerNativeMethods(env, kClassPathName, gMethods, NELEM(gMethods))) {
        ALOGE("Error: could not register native methods cmaudio service");
        return -1;
    }

    android::status_t err = hw_get_module(POWER_HARDWARE_MODULE_ID,
                (hw_module_t const**)&android::gPowerModule);
    if (!err) {
        android::gPowerModule->init(android::gPowerModule);
    } else {
        ALOGE("Couldn't load %s module (%s)", POWER_HARDWARE_MODULE_ID, strerror(-err));
    }

    return JNI_VERSION_1_6;
}

