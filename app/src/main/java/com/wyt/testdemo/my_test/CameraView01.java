package com.wyt.testdemo.my_test;

import android.app.Activity;
import android.os.Bundle;
import android.support.annotation.Nullable;

import com.wyt.testdemo.R;

/**
 * Created by wyt on 2017/7/13.
 */

public class CameraView01 extends Activity {
    public static final String CONTENT_TYPE_GENERAL = "general";//普通文字識別


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.bd_ocr_activity_camera);
    }


    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
