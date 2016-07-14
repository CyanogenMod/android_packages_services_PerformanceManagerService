# Copyright (C) 2016 The CyanogenMod Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

LOCAL_PATH:= $(call my-dir)

include $(CLEAR_VARS)

LOCAL_SRC_FILES := \
    src/org_cyanogenmod_cmperformance_service_PerformanceManagerService.cpp

LOCAL_C_INCLUDES := \
    $(JNI_H_INCLUDE) \
    $(TOP)/frameworks/base/core/jni \
    $(TOP)/frameworks/av/include \
    $(TOP)/hardware/libhardware/include

LOCAL_SHARED_LIBRARIES := \
    liblog \
    libandroid_runtime \
    libnativehelper \
    libcutils \
    libhardware \
    libmedia \
    libutils

LOCAL_MODULE := libcmperformancemanager_jni

LOCAL_CPPFLAGS += $(JNI_CFLAGS)
LOCAL_LDLIBS := -llog
LOCAL_NDK_STL_VARIANT := stlport_shared
LOCAL_ARM_MODE := arm

LOCAL_MODULE_TAGS := optional

LOCAL_CFLAGS += -Wall -Werror -Wno-unused-parameter

include $(BUILD_SHARED_LIBRARY)

