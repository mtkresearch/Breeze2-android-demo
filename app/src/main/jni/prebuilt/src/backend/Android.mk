LOCAL_PATH := $(call my-dir)
USER_LOCAL_C_INCLUDES := $(LOCAL_C_INCLUDES)

include $(CLEAR_VARS)
LOCAL_MODULE := backend
LOCAL_SRC_FILES := backend.cpp
LOCAL_C_INCLUDES := $(USER_LOCAL_C_INCLUDES)
LOCAL_SHARED_LIBRARIES += common
include $(BUILD_STATIC_LIBRARY)

LOCAL_C_INCLUDES := $(USER_LOCAL_C_INCLUDES)