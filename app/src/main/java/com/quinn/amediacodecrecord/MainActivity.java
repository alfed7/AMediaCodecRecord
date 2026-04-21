package com.quinn.amediacodecrecord;

import android.Manifest;
import android.app.Activity;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.Camera;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;

import java.io.File;
import java.io.IOException;
import java.util.List;

public class MainActivity extends Activity {

    private static final int REQUEST_RECORD_PERMISSIONS = 1;
    private static final String POSTFIX = ".mp4";
    private static final int DEFAULT_RECORD_WIDTH = 1920;
    private static final int DEFAULT_RECORD_HEIGHT = 1080;
    private static final int DEFAULT_FRAME_RATE = 30;

    private Button button;
    private TextureView textureView;
    private SurfaceTexture previewSurface;

    private Camera camera;

    private NativeRecord record;
    private String path;
    private MediaScannerConnection msc;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private int recordWidth = DEFAULT_RECORD_WIDTH;
    private int recordHeight = DEFAULT_RECORD_HEIGHT;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        init();

        initView();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_RECORD_PERMISSIONS && hasRequiredPermissions()) {
            startCameraIfReady();
        }
    }

    private void init() {
        record = new NativeRecord();
        msc = new MediaScannerConnection(this, client);
    }

    private void initView() {
        button = (Button) findViewById(R.id.btn);
        textureView = (TextureView) findViewById(R.id.surface);
        textureView.setSurfaceTextureListener(surfaceTextureListener);
        button.setOnClickListener(onClickListener);
        if (!hasRequiredPermissions()) {
            requestPermissions(new String[]{
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO
            }, REQUEST_RECORD_PERMISSIONS);
        }
    }

    private MediaScannerConnection.MediaScannerConnectionClient client = new MediaScannerConnection.MediaScannerConnectionClient() {
        @Override
        public void onMediaScannerConnected() {
            if (path != null && !path.isEmpty() && msc.isConnected()) {
                msc.scanFile(path, null);
            }
        }

        @Override
        public void onScanCompleted(String path, Uri uri) {
            msc.disconnect();
        }
    };

    private View.OnClickListener onClickListener = new View.OnClickListener() {
        @Override
        public void onClick(View v) {
            if (!hasRequiredPermissions()) {
                requestPermissions(new String[]{
                        Manifest.permission.CAMERA,
                        Manifest.permission.RECORD_AUDIO
                }, REQUEST_RECORD_PERMISSIONS);
                return;
            }
            if (!record.isRecording()) {
                File outputFile = createOutputFile();
                if (outputFile == null) {
                    return;
                }
                path = outputFile.getAbsolutePath();
                if (record.recordPreper(path, recordWidth, recordHeight, DEFAULT_FRAME_RATE)) {
                    record.start();
                    button.setText(R.string.stop);
                }
            } else {
                record.stop();
                button.setText(R.string.start);
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        msc.connect();
                    }
                }, 1000);
            }
        }
    };

    private TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            previewSurface = surface;
            startCameraIfReady();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            if (camera != null) {
                camera.stopPreview();
                camera.setPreviewCallback(null);
                camera.release();
                camera = null;
            }
            previewSurface = null;
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };

    private boolean hasRequiredPermissions() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void startCameraIfReady() {
        if (!hasRequiredPermissions() || previewSurface == null || camera != null) {
            return;
        }

        try {
            camera = Camera.open(Camera.CameraInfo.CAMERA_FACING_BACK);
            Camera.Parameters parameters = camera.getParameters();
            Camera.Size previewSize = findPreferredPreviewSize(parameters.getSupportedPreviewSizes());
            parameters.setPreviewSize(previewSize.width, previewSize.height);
            recordWidth = previewSize.width;
            recordHeight = previewSize.height;
            camera.setParameters(parameters);
            camera.setPreviewTexture(previewSurface);
            camera.setPreviewCallback(previewCallback);
            camera.startPreview();
        } catch (IOException | RuntimeException e) {
            e.printStackTrace();
            if (camera != null) {
                camera.release();
                camera = null;
            }
        }
    }

    private Camera.Size findPreferredPreviewSize(List<Camera.Size> previewSizes) {
        Camera.Size fallback = previewSizes.get(0);
        for (Camera.Size size : previewSizes) {
            if (size.width == DEFAULT_RECORD_WIDTH && size.height == DEFAULT_RECORD_HEIGHT) {
                return size;
            }
            if ((long) size.width * size.height > (long) fallback.width * fallback.height) {
                fallback = size;
            }
        }
        return fallback;
    }

    private File createOutputFile() {
        File directory = getExternalFilesDir(Environment.DIRECTORY_MOVIES);
        if (directory == null) {
            directory = getFilesDir();
        }
        if (!directory.exists() && !directory.mkdirs()) {
            return null;
        }
        return new File(directory, System.currentTimeMillis() + POSTFIX);
    }

    private Camera.PreviewCallback previewCallback = new Camera.PreviewCallback() {
        @Override
        public void onPreviewFrame(final byte[] data, Camera camera) {
            if (record.isRecording())
                record.sendVideoData(data);
        }
    };
}
