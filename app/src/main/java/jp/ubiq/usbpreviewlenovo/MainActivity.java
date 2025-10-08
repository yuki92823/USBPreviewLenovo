package jp.ubiq.usbpreviewlenovo;

import android.hardware.usb.UsbDevice;
import android.os.Bundle;
import android.util.Log;
import android.view.SurfaceTexture;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.serenegiant.usb.DeviceFilter;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.widget.UVCCameraTextureView;

import java.util.List;
import java.util.Locale;

/**
 * Main activity that renders a full-screen USB (UVC) camera preview on Lenovo tablets.
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int PREVIEW_WIDTH = 1280;
    private static final int PREVIEW_HEIGHT = 720;

    private UVCCameraTextureView textureView;
    private TextView powerHintView;
    @Nullable
    private USBMonitor usbMonitor;
    @Nullable
    private UVCCamera uvcCamera;
    @Nullable
    private USBMonitor.UsbControlBlock currentControlBlock;

    private final Object cameraSync = new Object();
    private boolean powerHintShown;
    private boolean lenovoHintShown;

    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            Log.d(TAG, "Surface texture became available: " + width + "x" + height);
            startPreviewSafely();
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
            Log.d(TAG, "Surface texture size changed: " + width + "x" + height);
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            Log.d(TAG, "Surface texture destroyed");
            synchronized (cameraSync) {
                if (uvcCamera != null) {
                    try {
                        uvcCamera.stopPreview();
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to stop preview while destroying surface", e);
                    }
                }
            }
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
            // No-op: preview updates continuously.
        }
    };

    private final USBMonitor.OnDeviceConnectListener deviceConnectListener = new USBMonitor.OnDeviceConnectListener() {
        @Override
        public void onAttach(@NonNull UsbDevice device) {
            Log.i(TAG, "USB device attached: " + describeDevice(device));
            showLenovoHint();
            showPoweredHubRecommendation();
            if (usbMonitor != null) {
                Log.i(TAG, "Requesting USB permission to ensure exclusive external camera access over Lenovo's internal camera.");
                usbMonitor.requestPermission(device);
            }
        }

        @Override
        public void onDetach(@NonNull UsbDevice device) {
            Log.i(TAG, "USB device detached: " + describeDevice(device));
            synchronized (cameraSync) {
                releaseCameraLocked();
            }
            runOnUiThread(() -> powerHintView.setVisibility(View.VISIBLE));
        }

        @Override
        public void onConnect(@NonNull UsbDevice device, @NonNull USBMonitor.UsbControlBlock ctrlBlock, boolean createNew) {
            Log.i(TAG, "USB device connected: " + describeDevice(device));
            Log.i(TAG, "Opening UVC camera with exclusive access and preparing preview stream.");
            synchronized (cameraSync) {
                releaseCameraLocked();
                currentControlBlock = ctrlBlock;
                UVCCamera camera = new UVCCamera();
                try {
                    camera.open(ctrlBlock);
                    configurePreview(camera);
                    uvcCamera = camera;
                } catch (Exception e) {
                    Log.e(TAG, "Unable to open USB camera", e);
                    showPoweredHubRecommendation();
                    releaseCameraLocked();
                    return;
                }
            }
            runOnUiThread(() -> {
                startPreviewSafely();
                powerHintView.setVisibility(View.GONE);
            });
        }

        @Override
        public void onDisconnect(@NonNull UsbDevice device, @NonNull USBMonitor.UsbControlBlock ctrlBlock) {
            Log.i(TAG, "USB device disconnected: " + describeDevice(device));
            synchronized (cameraSync) {
                releaseCameraLocked();
            }
            runOnUiThread(() -> powerHintView.setVisibility(View.VISIBLE));
        }

        @Override
        public void onCancel(@NonNull UsbDevice device) {
            Log.w(TAG, "USB permission request cancelled for device: " + describeDevice(device));
        }
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        textureView = findViewById(R.id.uvc_texture_view);
        textureView.setSurfaceTextureListener(surfaceTextureListener);
        powerHintView = findViewById(R.id.power_hint);

        usbMonitor = new USBMonitor(this, deviceConnectListener);
        final List<DeviceFilter> deviceFilters = DeviceFilter.getDeviceFilters(this, R.xml.device_filter);
        usbMonitor.setDeviceFilter(deviceFilters);
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (textureView != null) {
            textureView.onResume();
        }
        if (usbMonitor != null) {
            usbMonitor.register();
        }
    }

    @Override
    protected void onStop() {
        if (usbMonitor != null) {
            usbMonitor.unregister();
        }
        if (textureView != null) {
            textureView.onPause();
        }
        synchronized (cameraSync) {
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

    private void startPreviewSafely() {
        synchronized (cameraSync) {
            if (uvcCamera == null) {
                Log.d(TAG, "startPreviewSafely: camera not ready yet");
                return;
            }
            if (!textureView.isAvailable()) {
                Log.d(TAG, "startPreviewSafely: texture view not yet available");
                return;
            }
            final SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            if (surfaceTexture == null) {
                Log.d(TAG, "startPreviewSafely: surface texture is null");
                return;
            }
            try {
                uvcCamera.setPreviewTexture(surfaceTexture);
                uvcCamera.startPreview();
                Log.i(TAG, "USB camera preview started");
            } catch (Exception e) {
                Log.e(TAG, "Failed to start USB camera preview", e);
                showPoweredHubRecommendation();
            }
        }
    }

    private void configurePreview(@NonNull UVCCamera camera) {
        try {
            camera.setPreviewSize(PREVIEW_WIDTH, PREVIEW_HEIGHT, UVCCamera.FRAME_FORMAT_MJPEG);
        } catch (IllegalArgumentException e) {
            Log.w(TAG, "Falling back to YUYV preview due to unsupported MJPEG size", e);
            try {
                camera.setPreviewSize(UVCCamera.DEFAULT_PREVIEW_WIDTH, UVCCamera.DEFAULT_PREVIEW_HEIGHT, UVCCamera.FRAME_FORMAT_YUYV);
            } catch (IllegalArgumentException ex) {
                Log.e(TAG, "Unable to configure preview size", ex);
            }
        }
        try {
            camera.setAutoFocus(true);
        } catch (Exception e) {
            Log.w(TAG, "Auto focus not supported on this USB camera", e);
        }
    }

    private void releaseCameraLocked() {
        if (uvcCamera != null) {
            try {
                uvcCamera.stopPreview();
            } catch (Exception e) {
                Log.w(TAG, "Error while stopping preview during release", e);
            }
            try {
                uvcCamera.destroy();
            } catch (Exception e) {
                Log.w(TAG, "Error while destroying camera", e);
            }
            uvcCamera = null;
        }
        if (currentControlBlock != null) {
            try {
                currentControlBlock.close();
            } catch (Exception e) {
                Log.w(TAG, "Error while closing control block", e);
            }
            currentControlBlock = null;
        }
    }

    private void showPoweredHubRecommendation() {
        if (powerHintShown) {
            return;
        }
        powerHintShown = true;
        Log.w(TAG, "USB camera may need more power. Recommend connecting through a powered OTG hub.");
        runOnUiThread(() -> Toast.makeText(MainActivity.this, R.string.usb_power_hint, Toast.LENGTH_LONG).show());
    }

    private void showLenovoHint() {
        if (lenovoHintShown) {
            return;
        }
        lenovoHintShown = true;
        Log.i(TAG, "Reminding user about Lenovo internal camera priority quirks.");
        runOnUiThread(() -> Toast.makeText(MainActivity.this, R.string.lenovo_internal_camera_hint, Toast.LENGTH_LONG).show());
    }

    private String describeDevice(@NonNull UsbDevice device) {
        return String.format(Locale.US, "VID=0x%04X, PID=0x%04X", device.getVendorId(), device.getProductId());
    }
}
