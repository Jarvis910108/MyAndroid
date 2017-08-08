package com.wyt.testdemo.my_test;

import android.Manifest;
import android.annotation.TargetApi;
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
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.support.annotation.Nullable;
import android.support.annotation.RequiresApi;
import android.support.v4.content.ContextCompat;
import android.support.v7.app.AppCompatActivity;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;

import com.wyt.testdemo.R;

import java.nio.ByteBuffer;
import java.util.Arrays;

/**
 * Created by wyt on 2017/7/11.
 */

@TargetApi(Build.VERSION_CODES.LOLLIPOP)
public class TestAC01 extends AppCompatActivity implements TextureView.SurfaceTextureListener {
    private TextureView tvv_test;
    private Handler mHandler;
    private HandlerThread mThreadHandler;
    private Size mPreviewSize;
    private CaptureRequest.Builder mPreviewBuilder;
    private ImageReader mImageReader;
    private CameraDevice mCameraDevice;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_test);
        init();
    }

    //很多过程都变成了异步的了，所以这里需要一个子线程的looper
    private void initLooper() {
        mThreadHandler = new HandlerThread("CAMERA2");
        mThreadHandler.start();
        mHandler = new Handler(mThreadHandler.getLooper());
    }


    private void init() {

        initLooper();
    }


    /**
     * 属于SurfaceTextureListener
     *
     * @param surfaceTexture
     * @param i
     * @param i1
     */

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture surfaceTexture, int i, int i1) {

    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture surfaceTexture, int i, int i1) {
        try {
            //获得所有摄像头的管理者CameraManager
            CameraManager cameraManager = (CameraManager) this.getSystemService(Context.CAMERA_SERVICE);
            //获得某个摄像头的特征，支持的参数
            CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics("0");
            //支持的STREAM CONFIGURATION
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            //摄像头支持的预览Size数组
            mPreviewSize = map.getOutputSizes(SurfaceTexture.class)[0];
            //打开相机
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                //判断权限的简要方法 如果不匹配那么就返回
                // requestCameraPermission();
                return;
            }
            //cameraID  相机策略回调  handler
            cameraManager.openCamera("0", mCameraDeviceStateCallback, mHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }


    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {

        //相机打开回调
        @Override
        public void onOpened(CameraDevice camera) {
            try {
                startPreview(camera);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }
        //相机断开连接回调
        @Override
        public void onDisconnected(CameraDevice camera) {

        }
        //相机打开回调
        @Override
        public void onError(CameraDevice camera, int error) {

        }
    };

    /**
     * SurfaceTextureListener  属于
     *
     * @param surfaceTexture
     * @return
     */
    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture surfaceTexture) {
        return false;
    }

    // 这个方法要注意一下，因为每有一帧画面，都会回调一次此方法
    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture surfaceTexture) {

    }

    @Override
    public void onPointerCaptureChanged(boolean hasCapture) {

    }

    /**
     * 开始预览
     */
    // 开始预览，主要是camera.createCaptureSession这段代码很重要，创建会话
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    private void startPreview(CameraDevice camera) throws CameraAccessException {
        SurfaceTexture texture = tvv_test.getSurfaceTexture();
        this.mCameraDevice = camera;
//      这里设置的就是预览大小
        texture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        Surface surface = new Surface(texture);
        try {
            // 设置捕获请求为预览，这里还有拍照啊，录像等
            mPreviewBuilder = camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

//      就是在这里，通过这个set(key,value)方法，设置曝光啊，自动聚焦等参数！！ 如下举例：
//      mPreviewBuilder.set(CaptureRequest.CONTROL_AE_MODE,CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);


        //此处还有很多格式，比如我所用到YUV等*/,2/*最大的图片数，mImageReader里能获取到图片数，但是实际中是2+1张图片，就是多一张*/
        mImageReader = ImageReader.newInstance(tvv_test.getWidth(), tvv_test.getHeight(), ImageFormat.JPEG, 1);

        mImageReader.setOnImageAvailableListener(mOnImageAvailableListener, mHandler);
        // 这里一定分别add两个surface，一个Textureview的，一个ImageReader的，如果没add，会造成没摄像头预览，或者没有ImageReader的那个回调！！
        mPreviewBuilder.addTarget(surface);
        mPreviewBuilder.addTarget(mImageReader.getSurface());
        mCameraDevice.createCaptureSession(Arrays.asList(surface, mImageReader.getSurface()), mSessionStateCallback, mHandler);
    }


    /**
     * 创建相机捕获获取
     */
    private CameraCaptureSession.StateCallback mSessionStateCallback = new CameraCaptureSession.StateCallback() {

        @Override
        public void onConfigured(CameraCaptureSession session) {
            try {
                updatePreview(session);
            } catch (CameraAccessException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {

        }
    };

    private void updatePreview(CameraCaptureSession session) throws CameraAccessException {
        session.setRepeatingRequest(mPreviewBuilder.build(), null, mHandler);
    }


    /**
     * 创建图片读取者
     */

    private ImageReader.OnImageAvailableListener mOnImageAvailableListener
            = new ImageReader.OnImageAvailableListener() {

        /**
         *  当有一张图片可用时会回调此方法，但有一点一定要注意：
         *  一定要调用 reader.acquireNextImage()和close()方法，否则画面就会卡住！！！！！我被这个坑坑了好久！！！
         *    很多人可能写Demo就在这里打一个Log，结果卡住了，或者方法不能一直被回调。
         **/
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image img = reader.acquireNextImage();
            /**
             *  因为Camera2并没有Camera1的Priview回调！！！所以该怎么能到预览图像的byte[]呢？就是在这里了！！！我找了好久的办法！！！
             **/
            ByteBuffer buffer = img.getPlanes()[0].getBuffer();
            byte[] data = new byte[buffer.remaining()];
            buffer.get(data);
            img.close();
        }
    };

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if(null!=mImageReader){
            mImageReader.close();
            mImageReader = null;
        }
        if(mCameraDevice!=null){
            mCameraDevice.close();
            mCameraDevice=null;
        }
    }
}
