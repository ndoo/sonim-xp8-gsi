// SPDX-FileCopyrightText: 2026 no0406
// SPDX-License-Identifier: MIT

package sg.ndoo.xp8.buttons;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;

/**
 * Toggles the flashlight for the "Flashlight on/off" key action. The daemon sends
 * sg.ndoo.xp8.buttons.TORCH; going through CameraManager keeps the Quick Settings
 * tile and camera apps in step. CameraManager has no getter for the torch state, so
 * a TorchCallback reports it once on registration and the receiver flips it.
 */
public class TorchReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        CameraManager cm = context.getSystemService(CameraManager.class);
        String id = flashCamera(cm);
        if (id == null) return;
        PendingResult result = goAsync();
        Handler main = new Handler(Looper.getMainLooper());
        CameraManager.TorchCallback cb = new CameraManager.TorchCallback() {
            boolean done;

            @Override
            public void onTorchModeChanged(String cameraId, boolean enabled) {
                if (done || !cameraId.equals(id)) return;
                done = true;
                cm.unregisterTorchCallback(this);
                try { cm.setTorchMode(id, !enabled); } catch (Exception e) { }
                result.finish();
            }

            @Override
            public void onTorchModeUnavailable(String cameraId) {
                if (done || !cameraId.equals(id)) return;
                done = true;
                cm.unregisterTorchCallback(this);
                result.finish();
            }
        };
        cm.registerTorchCallback(cb, main);
    }

    // The first camera with a flash, preferring the rear one.
    static String flashCamera(CameraManager cm) {
        String any = null;
        try {
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                if (!Boolean.TRUE.equals(c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE))) continue;
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
                if (any == null) any = id;
            }
        } catch (Exception e) { }
        return any;
    }
}
