package org.cloudveil.messenger.util;

import android.Manifest;
import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraManager;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;

/**
 * Created by Dmitriy on 03.03.2018.
 */

public class CameraUtil {
    public static boolean isCameraEnabled(@NonNull Context context) {
        DevicePolicyManager dpm = (DevicePolicyManager) context.getSystemService(Context.DEVICE_POLICY_SERVICE);
        if (dpm != null) {
            boolean cameraDisabled = dpm.getCameraDisabled(null);
            if (cameraDisabled) {
                return false;
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
            try {
                if (ActivityCompat.checkSelfPermission(context, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                    return false;
                }

                String[] cameraIdList = manager.getCameraIdList();
            } catch (CameraAccessException e) {
                return false;
            }
        }
        return true;
    }

    public static void guardCameraEnabled(@NonNull Context context) throws Exception {
        if(!isCameraEnabled(context)) {
            throw new Exception("Camera is disabled by admin or somehow else");
        }
    }
}
