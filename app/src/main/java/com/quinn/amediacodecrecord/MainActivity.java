package com.quinn.amediacodecrecord;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.Button;

import java.io.File;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Comparator;

public class MainActivity extends Activity {

    private static final int REQUEST_RECORD_PERMISSIONS = 1;
    private static final String POSTFIX = ".mp4";
    private static final int DEFAULT_RECORD_WIDTH = 1920;
    private static final int DEFAULT_RECORD_HEIGHT = 1080;
    private static final int DEFAULT_FRAME_RATE = 30;

    private Button button;
    private TextureView textureView;
    private SurfaceTexture previewSurfaceTexture;

    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private ImageReader imageReader;
    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private String cameraId;

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
    protected void onResume() {
        super.onResume();
        startCameraThread();
        if (textureView != null && textureView.isAvailable()) {
            previewSurfaceTexture = textureView.getSurfaceTexture();
            startCameraIfReady();
        }
    }

    @Override
    protected void onPause() {
        stopRecordingIfNeeded();
        closeCamera();
        stopCameraThread();
        super.onPause();
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

    private final MediaScannerConnection.MediaScannerConnectionClient client = new MediaScannerConnection.MediaScannerConnectionClient() {
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

    private final View.OnClickListener onClickListener = new View.OnClickListener() {
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
                stopRecordingIfNeeded();
            }
        }
    };

    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            previewSurfaceTexture = surface;
            startCameraIfReady();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            closeCamera();
            previewSurfaceTexture = null;
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {
        }
    };

    private final CameraDevice.StateCallback cameraStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            cameraDevice = camera;
            createCameraSession();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            cameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            cameraDevice = null;
        }
    };

    private final ImageReader.OnImageAvailableListener imageAvailableListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = null;
            try {
                image = reader.acquireLatestImage();
                if (image != null && record.isRecording()) {
                    record.sendVideoData(yuv420ToNv21(image));
                }
            } finally {
                if (image != null) {
                    image.close();
                }
            }
        }
    };

    private boolean hasRequiredPermissions() {
        return checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void startCameraIfReady() {
        if (!hasRequiredPermissions() || previewSurfaceTexture == null || cameraDevice != null) {
            return;
        }

        try {
            CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
            if (manager == null) {
                return;
            }
            cameraId = findBackCameraId(manager);
            if (cameraId == null) {
                return;
            }
            Size selectedSize = findPreferredPreviewSize(manager, cameraId);
            recordWidth = selectedSize.getWidth();
            recordHeight = selectedSize.getHeight();
            imageReader = ImageReader.newInstance(recordWidth, recordHeight, ImageFormat.YUV_420_888, 3);
            imageReader.setOnImageAvailableListener(imageAvailableListener, cameraHandler);
            manager.openCamera(cameraId, cameraStateCallback, cameraHandler);
        } catch (CameraAccessException | SecurityException e) {
            e.printStackTrace();
            closeCamera();
        }
    }

    private String findBackCameraId(CameraManager manager) throws CameraAccessException {
        for (String id : manager.getCameraIdList()) {
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
            Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                return id;
            }
        }
        String[] ids = manager.getCameraIdList();
        return ids.length > 0 ? ids[0] : null;
    }

    private Size findPreferredPreviewSize(CameraManager manager, String id) throws CameraAccessException {
        CameraCharacteristics characteristics = manager.getCameraCharacteristics(id);
        StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        Size[] sizes = map == null ? null : map.getOutputSizes(ImageFormat.YUV_420_888);
        if (sizes == null || sizes.length == 0) {
            return new Size(DEFAULT_RECORD_WIDTH, DEFAULT_RECORD_HEIGHT);
        }
        for (Size size : sizes) {
            if (size.getWidth() == DEFAULT_RECORD_WIDTH && size.getHeight() == DEFAULT_RECORD_HEIGHT) {
                return size;
            }
        }
        Size largest = null;
        Comparator<Size> areaComparator = Comparator.comparingLong(
                size -> (long) size.getWidth() * size.getHeight());
        for (Size size : sizes) {
            if (size.getWidth() > DEFAULT_RECORD_WIDTH || size.getHeight() > DEFAULT_RECORD_HEIGHT) {
                continue;
            }
            if (largest == null || areaComparator.compare(size, largest) > 0) {
                largest = size;
            }
        }
        return largest == null ? sizes[0] : largest;
    }

    private void createCameraSession() {
        if (cameraDevice == null || previewSurfaceTexture == null || imageReader == null) {
            return;
        }

        try {
            previewSurfaceTexture.setDefaultBufferSize(recordWidth, recordHeight);
            Surface previewSurface = new Surface(previewSurfaceTexture);
            Surface imageSurface = imageReader.getSurface();
            final CaptureRequest.Builder requestBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            requestBuilder.addTarget(previewSurface);
            requestBuilder.addTarget(imageSurface);
            requestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO);

            CameraCaptureSession.StateCallback stateCallback = new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        captureSession.setRepeatingRequest(requestBuilder.build(), null, cameraHandler);
                    } catch (CameraAccessException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    closeCamera();
                }
            };

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                SessionConfiguration configuration = new SessionConfiguration(
                        SessionConfiguration.SESSION_REGULAR,
                        Arrays.asList(new OutputConfiguration(previewSurface),
                                new OutputConfiguration(imageSurface)),
                        getMainExecutor(),
                        stateCallback);
                cameraDevice.createCaptureSession(configuration);
            } else {
                createLegacyCameraSession(previewSurface, imageSurface, stateCallback);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
            closeCamera();
        }
    }

    @SuppressWarnings("deprecation")
    private void createLegacyCameraSession(Surface previewSurface, Surface imageSurface,
                                           CameraCaptureSession.StateCallback stateCallback)
            throws CameraAccessException {
        cameraDevice.createCaptureSession(Arrays.asList(previewSurface, imageSurface),
                stateCallback, cameraHandler);
    }

    private void closeCamera() {
        if (captureSession != null) {
            captureSession.close();
            captureSession = null;
        }
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (imageReader != null) {
            imageReader.close();
            imageReader = null;
        }
    }

    private void startCameraThread() {
        if (cameraThread != null) {
            return;
        }
        cameraThread = new HandlerThread("Camera2Background");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread == null) {
            return;
        }
        cameraThread.quitSafely();
        try {
            cameraThread.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        cameraThread = null;
        cameraHandler = null;
    }

    private void stopRecordingIfNeeded() {
        if (!record.isRecording()) {
            return;
        }
        record.stop();
        button.setText(R.string.start);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                msc.connect();
            }
        }, 1000);
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

    private byte[] yuv420ToNv21(Image image) {
        int width = image.getWidth();
        int height = image.getHeight();
        int ySize = width * height;
        int uvSize = width * height / 4;
        byte[] output = new byte[ySize + uvSize * 2];

        copyPlane(image.getPlanes()[0], width, height, output, 0, 1);
        copyPlane(image.getPlanes()[2], width / 2, height / 2, output, ySize, 2);
        copyPlane(image.getPlanes()[1], width / 2, height / 2, output, ySize + 1, 2);
        return output;
    }

    private void copyPlane(Image.Plane plane, int width, int height, byte[] output, int offset, int pixelStride) {
        ByteBuffer buffer = plane.getBuffer();
        int rowStride = plane.getRowStride();
        int sourcePixelStride = plane.getPixelStride();
        byte[] rowData = new byte[rowStride];

        for (int row = 0; row < height; row++) {
            int bytesPerPixelRow = (width - 1) * sourcePixelStride + 1;
            buffer.get(rowData, 0, bytesPerPixelRow);
            for (int col = 0; col < width; col++) {
                output[offset] = rowData[col * sourcePixelStride];
                offset += pixelStride;
            }
            if (row < height - 1) {
                buffer.position(buffer.position() + rowStride - bytesPerPixelRow);
            }
        }
    }
}
