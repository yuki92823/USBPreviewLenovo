package jp.ubiq.usbpreviewlenovo;

import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceTexture;
import android.view.TextureView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.widget.UVCCameraTextureView;

/**
 * Activity that previews a connected USB Video Class (UVC) camera on Lenovo tablets.
 */
public class MainActivity extends AppCompatActivity implements CameraDialog.CameraDialogParent {

    private static final String TAG = "LenovoUVC";

    private UVCCameraTextureView cameraView;
    private USBMonitor usbMonitor;
    private final Object cameraLock = new Object();
    private UVCCamera uvcCamera;

    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            Log.i(TAG, "SurfaceTexture available; attempting to start preview");
            startPreviewIfReady();
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            // No-op; preview will auto-scale due to TextureView behaviour.
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            Log.i(TAG, "SurfaceTexture destroyed; stopping preview");
            synchronized (cameraLock) {
                if (uvcCamera != null) {
                    try {
                        uvcCamera.stopPreview();
                    } catch (Exception e) {
                        Log.w(TAG, "Error stopping preview on surface destroy", e);
                    }
                }
            }
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            // No-op
        }
    };

    private final USBMonitor.OnDeviceConnectListener deviceConnectListener = new USBMonitor.OnDeviceConnectListener() {
        @Override
        public void onAttach(@NonNull UsbDevice device) {
            logDevice("onAttach", device);
            if (usbMonitor != null) {
                Log.i(TAG, "Requesting permission for attached USB device");
                usbMonitor.requestPermission(device);
            }
        }

        @Override
        public void onDettach(@NonNull UsbDevice device) {
            logDevice("onDettach", device);
        }

        @Override
        public void onConnect(@NonNull UsbDevice device, @NonNull USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
            logDevice("onConnect", device);
            Log.i(TAG, "Opening UVC camera with exclusive access");
            synchronized (cameraLock) {
                releaseCameraLocked();
                try {
                    uvcCamera = new UVCCamera();
                    uvcCamera.open(ctrlBlock);
                    try {
                        uvcCamera.setPreviewSize(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG);
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, "Preferred preview size unsupported; falling back to default", e);
                        try {
                            uvcCamera.setPreviewSize(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT);
                        } catch (IllegalArgumentException ex) {
                            Log.e(TAG, "Unable to set preview size", ex);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Failed to open UVC camera. Ensure no other app holds exclusive access.", e);
                    Toast.makeText(MainActivity.this, R.string.usb_open_error, Toast.LENGTH_LONG).show();
                    releaseCameraLocked();
                    return;
                }
            }
            runOnUiThread(() -> {
                if (cameraView.isAvailable()) {
                    startPreviewIfReady();
                } else {
                    Log.i(TAG, "Surface not yet available; waiting via listener");
                }
            });
        }

        @Override
        public void onDisconnect(@NonNull UsbDevice device, USBMonitor.UsbControlBlock ctrlBlock) {
            logDevice("onDisconnect", device);
            synchronized (cameraLock) {
                releaseCameraLocked();
            }
        }

        @Override
        public void onCancel(@NonNull UsbDevice device) {
            logDevice("onCancel", device);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        cameraView = findViewById(R.id.camera_view);
        cameraView.setSurfaceTextureListener(surfaceTextureListener);
        cameraView.setKeepScreenOn(true);
        cameraView.setAspectRatio(UVCCamera.DEFAULT_PREVIEW_WIDTH / (float) UVCCamera.DEFAULT_PREVIEW_HEIGHT);

        TextView hintText = findViewById(R.id.hint_text);
        hintText.setSelected(true);

        usbMonitor = new USBMonitor(this, deviceConnectListener);

        Toast.makeText(this, R.string.power_hint, Toast.LENGTH_LONG).show();
        Log.i(TAG, "Initialised USB monitor. Lenovo tablets may prioritise internal cameras; close stock camera apps if preview does not start.");
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (usbMonitor != null) {
            usbMonitor.register();
        }
        cameraView.onResume();
    }

    @Override
    protected void onStop() {
        cameraView.onPause();
        if (usbMonitor != null) {
            usbMonitor.unregister();
        }
        synchronized (cameraLock) {
            releaseCameraLocked();
        }
        super.onStop();
    }

    @Override
    protected void onDestroy() {
        if (usbMonitor != null) {
            usbMonitor.destroy();
            usbMonitor = null;
        }
        super.onDestroy();
    }

    private void startPreviewIfReady() {
        synchronized (cameraLock) {
            if (uvcCamera == null) {
                Log.w(TAG, "startPreviewIfReady: camera is not ready");
                return;
            }
            SurfaceTexture surfaceTexture = cameraView.getSurfaceTexture();
            if (surfaceTexture == null) {
                Log.w(TAG, "startPreviewIfReady: surface texture is null");
                return;
            }
            try {
                uvcCamera.setPreviewTexture(surfaceTexture);
                uvcCamera.startPreview();
                Log.i(TAG, "startPreview: streaming from USB camera");
            } catch (Exception e) {
                Log.e(TAG, "Unable to start camera preview", e);
            }
        }
    }

    private void releaseCameraLocked() {
        if (uvcCamera != null) {
            try {
                uvcCamera.stopPreview();
            } catch (Exception e) {
                Log.w(TAG, "Error stopping preview during release", e);
            }
            try {
                uvcCamera.close();
            } catch (Exception e) {
                Log.w(TAG, "Error closing camera", e);
            }
            try {
                uvcCamera.destroy();
            } catch (Exception e) {
                Log.w(TAG, "Error destroying camera", e);
            }
            uvcCamera = null;
        }
    }

    private void logDevice(@NonNull String event, @NonNull UsbDevice device) {
        Log.i(TAG, event + ": vendorId=" + device.getVendorId() + ", productId=" + device.getProductId());
    }

    @Override
    public USBMonitor getUSBMonitor() {
        return usbMonitor;
    }

    @Override
    public void onDialogResult(boolean canceled) {
        Log.i(TAG, "Camera dialog result: " + (canceled ? "cancelled" : "granted"));
    }
}
