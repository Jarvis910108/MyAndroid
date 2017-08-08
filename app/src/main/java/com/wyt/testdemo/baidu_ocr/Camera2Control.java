/*
 * Copyright (C) 2017 Baidu, Inc. All Rights Reserved.
 */

package com.wyt.testdemo.baidu_ocr;

import android.Manifest;
import android.annotation.TargetApi;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;


@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class Camera2Control implements ICameraControl {

    /**
     * Conversion from screen rotation to JPEG orientation.  5.0以上的定制相机类
     * 继承了icameraControl接口
     * 封裝的相機類  這個裡面應該可以返回ICameraControl接口所需的一切數據
     * 这应该是最底层的一个类了
     */
    private static final SparseIntArray ORIENTATIONS = new SparseIntArray();
    private static final int MAX_PREVIEW_SIZE = 2048;//预览视图的最大值是2048

    static {
            ORIENTATIONS.append(Surface.ROTATION_0, 90);
        ORIENTATIONS.append(Surface.ROTATION_90, 0);
        ORIENTATIONS.append(Surface.ROTATION_180, 270);
        ORIENTATIONS.append(Surface.ROTATION_270, 180);
    }

    private static final int STATE_PREVIEW = 0;
    private static final int STATE_WAITING_FOR_LOCK = 1;
    private static final int STATE_WAITING_FOR_CAPTURE = 2;
    private static final int STATE_CAPTURING = 3;
    private static final int STATE_PICTURE_TAKEN = 4;
    private static final int MAX_PREVIEW_WIDTH = 1920;//最大宽
    private static final int MAX_PREVIEW_HEIGHT = 1080;//最大高

    private int flashMode;
    private int orientation = 0;//照片方向
    private int state = STATE_PREVIEW;//预览状态 默认是0
    private Context context;
    //主要作用就是剥离抽象出来一个通用的相机接口
    private OnTakePictureCallback onTakePictureCallback;
    private PermissionCallback permissionCallback;//自定义的权限接口
    private String cameraId;//相机id没有什么意义 只是个代号
    private TextureView textureView;//纹理视图  surfaceview的兄弟类  比surface更好用
    private Size previewSize;
    private Rect previewFrame = new Rect();//这就是那个白色的框吗？

    private HandlerThread backgroundThread;
    private Handler backgroundHandler;

    private ImageReader imageReader;//照片读取者 原生类
    private CameraCaptureSession captureSession;//相机的session
    private CameraDevice cameraDevice;//相机装置

    private CaptureRequest.Builder previewRequestBuilder;
    private CaptureRequest previewRequest;//发送预览请求

    //处理现成的管理类  许可数为1
    private Semaphore cameraLock = new Semaphore(1);
    private int sensorOrientation;

    @Override
    public void start() {
        startBackgroundThread();
        //纹理视图如果是有效的  拍照之后调用
        if (textureView.isAvailable()) {
            openCamera(textureView.getWidth(), textureView.getHeight());
            Log.e("texture", textureView.getWidth() + ":" + textureView.getHeight());
            textureView.setSurfaceTextureListener(surfaceTextureListener);

        } else {
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        }
    }

    @Override
    public void stop() {
        textureView.setSurfaceTextureListener(null);
        closeCamera();
        stopBackgroundThread();
    }

    @Override
    public void pause() {
        setFlashMode(FLASH_MODE_OFF);
    }

    @Override
    public void resume() {
        state = STATE_PREVIEW;
    }

    @Override
    public View getDisplayView() {
        return textureView;
    }

    @Override
    public Rect getPreviewFrame() {
        return previewFrame;
    }

    @Override
    public void takePicture(OnTakePictureCallback callback) {
        this.onTakePictureCallback = callback;
        // 拍照第一步，对焦
        lockFocus();
    }


    @Override
    public void setPermissionCallback(PermissionCallback callback) {
        this.permissionCallback = callback;
    }

    @Override
    public void setDisplayOrientation(@CameraView.Orientation int displayOrientation) {
        this.orientation = displayOrientation / 90;
    }

    @Override
    public void refreshPermission() {
        openCamera(textureView.getWidth(), textureView.getHeight());
    }

    @Override
    public void setFlashMode(@FlashMode int flashMode) {
        if (this.flashMode == flashMode) {
            return;
        }
        this.flashMode = flashMode;
        try {
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            updateFlashMode(flashMode, previewRequestBuilder);
            previewRequest = previewRequestBuilder.build();
            captureSession.setRepeatingRequest(previewRequest, captureCallback, backgroundHandler);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public int getFlashMode() {
        return flashMode;
    }

    public Camera2Control(Context activity) {
        this.context = activity;
        textureView = new TextureView(activity);
    }

    //初始化textrue.srfaceTextUreListener  预览控件监听
    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                @Override
                public void onSurfaceTextureAvailable(SurfaceTexture texture, int width, int height) {
                    openCamera(width, height);
                }

                @Override
                public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int width, int height) {
                    //配置变化
                    configureTransform(width, height);
                    previewFrame.left = 0;
                    previewFrame.top = 0;
                    previewFrame.right = width;
                    previewFrame.bottom = height;
                }

                @Override
                public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
                    stop();
                    return true;
                }

                @Override
                public void onSurfaceTextureUpdated(SurfaceTexture texture) {
                    // 这个方法要注意一下，因为每有一帧画面，都会回调一次此方法
                }


            };

    private void openCamera(int width, int height) {
        // 6.0+的系统需要检查系统权限 。
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            requestCameraPermission();
            return;
        }
        //相机设置的一些基本  应该是从这里开始看的  还设置了关于imageReader的东西
        setUpCameraOutputs(width, height);
        configureTransform(width, height);
        //创建相机管理者
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            //在2500内获取一个许可  单位是
            if (!cameraLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                throw new RuntimeException("Time out waiting to lock camera opening.");
            }
            //打开相机
            manager.openCamera(cameraId, deviceStateCallback, backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera opening.", e);
        }
    }

    //初始化 相机装置 的回调接口   注意返回参数  用户监听摄像头的状态
    private final CameraDevice.StateCallback deviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice cameraDevice) {
            //摄像头打开
            cameraLock.release();
            Camera2Control.this.cameraDevice = cameraDevice;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(@NonNull CameraDevice cameraDevice) {
            //摄像头断开连接
            cameraLock.release();
            cameraDevice.close();
            Camera2Control.this.cameraDevice = null;
        }

        @Override
        public void onError(@NonNull CameraDevice cameraDevice, int error) {
            //摄像头报错
            cameraLock.release();
            cameraDevice.close();
            Camera2Control.this.cameraDevice = null;
        }
    };

    //创建相机会话  里面用了 surfaceTexture   这个方法主要是创建相机的预览会话的
    private void createCameraPreviewSession() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            //设置纹理视图的默认大小
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            //放入texture
            Surface surface = new Surface(texture);
            //创建相机装置的请求  模板_预览模式
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            //预览视图的目标 -》surface、
            previewRequestBuilder.addTarget(surface);
            updateFlashMode(this.flashMode, previewRequestBuilder);
            //相机装置里面创建会话  这是预览控件
            // 第一个参数 封装了所有需要从该摄像头获取图片的Surface
            // 第二个参数 第二个参数用于监听CameraCaptureSession的创建过程
            //第三个参数代表执行callback的Handler，如果程序希望直接在当前线程中执行callback，则可将handler参数设为null。
            cameraDevice.createCaptureSession(Arrays.asList(surface, imageReader.getSurface()),
                    new CameraCaptureSession.StateCallback() {

                        @Override
                        public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                            // The camera is already closed
                            //配置正确  可以预览？
                            if (null == cameraDevice) {
                                return;
                            }
                            captureSession = cameraCaptureSession;
                            try {
                                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                                        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                previewRequest = previewRequestBuilder.build();
                                //这个会话是执行预览照片
                                captureSession.setRepeatingRequest(previewRequest,
                                        captureCallback, backgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(
                                @NonNull CameraCaptureSession cameraCaptureSession) {
                            // TODO
                        }
                    }, null
            );
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    //图片读取者的可用监听初始化  监听获取摄像头的图像数据  这个预览图片的可用监听 应该是在初始化的时候就已经创建了
    private final ImageReader.OnImageAvailableListener onImageAvailableListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    if (onTakePictureCallback != null) {
                        //图片读取者
                        Image image = reader.acquireNextImage();
                        ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);

                        image.close();
                        onTakePictureCallback.onPictureTaken(bytes);
                    }
                }
            };

    //初始化相机会议  捕获回调接口  和 StateCallback 一起在 session里面使用
    private CameraCaptureSession.CaptureCallback captureCallback =
            new CameraCaptureSession.CaptureCallback() {
                private void process(CaptureResult result) {
                    switch (state) {
                        case STATE_PREVIEW: {
                            break;
                        }
                        case STATE_WAITING_FOR_LOCK: {
                            Integer afState = result.get(CaptureResult.CONTROL_AF_STATE);
                            if (afState == null) {
                                captureStillPicture();
                                return;
                            }
                            switch (afState) {
                                case CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED:
                                case CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED:
                                case CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED: {
                                    Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                                    if (aeState == null
                                            || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                                        captureStillPicture();
                                    } else {
                                        runPreCaptureSequence();
                                    }
                                    break;
                                }
                                default:
                                    captureStillPicture();
                            }
                            break;
                        }
                        case STATE_WAITING_FOR_CAPTURE: {
                            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                            if (aeState == null
                                    || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE
                                    || aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED) {
                                state = STATE_CAPTURING;
                            } else {
                                if (aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                                    captureStillPicture();
                                }
                            }
                            break;
                        }
                        case STATE_CAPTURING: {
                            Integer aeState = result.get(CaptureResult.CONTROL_AE_STATE);
                            if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                                captureStillPicture();
                            }
                            break;
                        }
                        default:
                            break;
                    }
                }

                @Override
                public void onCaptureProgressed(@NonNull CameraCaptureSession session,
                                                @NonNull CaptureRequest request,
                                                @NonNull CaptureResult partialResult) {
                    process(partialResult);
                }

                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    process(result);
                }

            };

    private Size getOptimalSize(Size[] choices, int textureViewWidth,
                                int textureViewHeight, int maxWidth, int maxHeight, Size aspectRatio) {
        List<Size> bigEnough = new ArrayList<>();
        List<Size> notBigEnough = new ArrayList<>();
        int w = aspectRatio.getWidth();
        int h = aspectRatio.getHeight();
        for (Size option : choices) {
            if (option.getWidth() <= maxWidth && option.getHeight() <= maxHeight
                    && option.getHeight() == option.getWidth() * h / w) {
                if (option.getWidth() >= textureViewWidth
                        && option.getHeight() >= textureViewHeight) {
                    bigEnough.add(option);
                } else {
                    notBigEnough.add(option);
                }
            }
        }

        // Pick the smallest of those big enough. If there is no one big enough, pick the
        // largest of those not big enough.
        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, sizeComparator);
        }

        for (Size option : choices) {
            if (option.getWidth() >= maxWidth && option.getHeight() >= maxHeight) {
                return option;
            }
        }

        if (notBigEnough.size() > 0) {
            return Collections.max(notBigEnough, sizeComparator);
        }

        return choices[0];
    }

    private Comparator<Size> sizeComparator = new Comparator<Size>() {
        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    };

    private void requestCameraPermission() {
        if (permissionCallback != null) {
            permissionCallback.onRequestPermission();
        }
    }

    @SuppressWarnings("SuspiciousNameCombination")
    private void setUpCameraOutputs(int width, int height) {
        CameraManager manager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        try {
            //遍历管理类中的参数  会获取一个相机的id  这个id在打开相机的时候要放进去  cameraId
            for (String cameraId : manager.getCameraIdList()) {
                CameraCharacteristics characteristics =
                        manager.getCameraCharacteristics(cameraId);
                //是否支持前置摄像头
                Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    continue;
                }
                //以流的形式 从管道传回照片 信息和 压缩过的数据  应该是这样
                StreamConfigurationMap map = characteristics.get(
                        CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) {
                    continue;
                }

                //                int preferredPictureSize = (int) (Math.max(textureView.getWidth(), textureView
                // .getHeight()) * 1.5);
                //                preferredPictureSize = Math.min(preferredPictureSize, 2560);

                WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
                Point screenSize = new Point();
                windowManager.getDefaultDisplay().getSize(screenSize);
                int maxImageSize = Math.max(MAX_PREVIEW_SIZE, screenSize.y * 3 / 2);
                //获取图片输出的尺寸
                Size size = getOptimalSize(map.getOutputSizes(ImageFormat.JPEG), textureView.getWidth(),
                        textureView.getHeight(), maxImageSize, maxImageSize, new Size(4, 3));
                //创建图片读取者  宽  高 类型 最大个数
                imageReader = ImageReader.newInstance(size.getWidth(), size.getHeight(),
                        ImageFormat.JPEG, 1);
                //设置图片读取者的可用监听 参数  监听者  +  一个hander  如果需要在本线程那就来个 null
                imageReader.setOnImageAvailableListener(
                        onImageAvailableListener, backgroundHandler);

                int displayRotation = orientation;
                // noinspection ConstantConditions
                sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
                boolean swappedDimensions = false;
                switch (displayRotation) {
                    case Surface.ROTATION_0:
                    case Surface.ROTATION_180:
                        if (sensorOrientation == 90 || sensorOrientation == 270) {
                            swappedDimensions = true;
                        }
                        break;
                    case Surface.ROTATION_90:
                    case Surface.ROTATION_270:
                        if (sensorOrientation == 0 || sensorOrientation == 180) {
                            swappedDimensions = true;
                        }
                        break;
                    default:
                }

                int rotatedPreviewWidth = width;
                int rotatedPreviewHeight = height;
                int maxPreviewWidth = screenSize.x;
                int maxPreviewHeight = screenSize.y;

                if (swappedDimensions) {
                    rotatedPreviewWidth = height;
                    rotatedPreviewHeight = width;
                    maxPreviewWidth = screenSize.y;
                    maxPreviewHeight = screenSize.x;
                }
                maxPreviewWidth = Math.min(maxPreviewWidth, MAX_PREVIEW_WIDTH);
                maxPreviewHeight = Math.min(maxPreviewHeight, MAX_PREVIEW_HEIGHT);
                //获取预览画面输出的尺寸，因为我使用TextureView作为预览
                previewSize = getOptimalSize(map.getOutputSizes(SurfaceTexture.class),
                        rotatedPreviewWidth, rotatedPreviewHeight, maxPreviewWidth,
                        maxPreviewHeight, size);
                this.cameraId = cameraId;
                return;
            }
        } catch (CameraAccessException | NullPointerException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        try {
            cameraLock.acquire();
            if (null != captureSession) {
                captureSession.close();
                captureSession = null;
            }
            if (null != cameraDevice) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (null != imageReader) {
                imageReader.close();
                imageReader = null;
            }
        } catch (InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            cameraLock.release();
        }
    }

    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("ocr_camera");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (backgroundThread != null) {
            backgroundThread.quitSafely();
            backgroundThread = null;
            backgroundHandler = null;
        }
    }

    private void configureTransform(int viewWidth, int viewHeight) {
        if (null == textureView || null == previewSize || null == context) {
            return;
        }
        int rotation = orientation;
        Matrix matrix = new Matrix();
        //rectF 与rect很相似  前者要更精准一些  单位是浮点型
        RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        //缓冲矩形
        RectF bufferRect = new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / previewSize.getHeight(),
                    (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }

    // 拍照前，先对焦
    private void lockFocus() {
        if (captureSession != null && state == STATE_PREVIEW) {
            try {
                previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                        CameraMetadata.CONTROL_AF_TRIGGER_START);

                state = STATE_WAITING_FOR_LOCK;
                //拍照
                captureSession.capture(previewRequestBuilder.build(), captureCallback,
                        backgroundHandler);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
    }

    private void runPreCaptureSequence() {
        try {
            previewRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            state = STATE_WAITING_FOR_CAPTURE;
            //会话控制拍照  capture
            captureSession.capture(previewRequestBuilder.build(), captureCallback,
                    backgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // 拍照会话
    private void captureStillPicture() {
        try {
            if (null == context || null == cameraDevice) {
                return;
            }
            //设置参数  拍照 TEMPLATE_STILL_CAPTURE
            final CaptureRequest.Builder captureBuilder =
                    cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            captureBuilder.addTarget(imageReader.getSurface());
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getOrientation(orientation));
            updateFlashMode(this.flashMode, captureBuilder);
            CameraCaptureSession.CaptureCallback captureCallback =
                    new CameraCaptureSession.CaptureCallback() {
                        @Override
                        public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                                       @NonNull CaptureRequest request,
                                                       @NonNull TotalCaptureResult result) {
                            unlockFocus();
                        }

                        @Override
                        public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                                    @NonNull CaptureRequest request,
                                                    @NonNull CaptureFailure failure) {
                            super.onCaptureFailed(session, request, failure);
                        }
                    };

            // 停止预览
            captureSession.stopRepeating();
            //开始拍照
            captureSession.capture(captureBuilder.build(), captureCallback, backgroundHandler);
            state = STATE_PICTURE_TAKEN;
            //            unlockFocus();
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private int getOrientation(int rotation) {
        return (ORIENTATIONS.get(rotation) + sensorOrientation + 270) % 360;
    }

    // 停止对焦
    private void unlockFocus() {
        try {
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                    CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            captureSession.capture(previewRequestBuilder.build(), captureCallback,
                    backgroundHandler);
            state = STATE_PREVIEW;
            // 预览  捕获会话设置预览
            captureSession.setRepeatingRequest(previewRequest, captureCallback,
                    backgroundHandler);
            textureView.setSurfaceTextureListener(surfaceTextureListener);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void updateFlashMode(@FlashMode int flashMode, CaptureRequest.Builder builder) {
        switch (flashMode) {
            case FLASH_MODE_TORCH:
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
                break;
            case FLASH_MODE_OFF:
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
                break;
            case ICameraControl.FLASH_MODE_AUTO:
            default:
                builder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE);
                break;
        }
    }
}
