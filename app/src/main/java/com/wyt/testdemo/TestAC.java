package com.wyt.testdemo;

import android.content.Intent;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.TextView;

import com.wyt.testdemo.baidu_main.MainActivity;
import com.wyt.testdemo.my_test.TestAC01;

/**
 * Created by wyt on 2017/7/10.
 */

public class TestAC extends AppCompatActivity implements View.OnClickListener {

    private static final int REQUEST_CAMERA = 250;


    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        init();
    }

    private void init() {
        setContentView(R.layout.activtiy_main);
        TextView tv_baidu = (TextView) findViewById(R.id.tv_baidu);
        TextView tv_huitong = (TextView) findViewById(R.id.tv_huitong);
        TextView tv_openCamera = (TextView) findViewById(R.id.tv_openCamera);
        tv_baidu.setOnClickListener(this);
        tv_huitong.setOnClickListener(this);
        tv_openCamera.setOnClickListener(this);


    }


    @Override
    public void onClick(View view) {
        switch (view.getId()) {
            case R.id.tv_baidu:
                Intent intent = new Intent(this, MainActivity.class);
                startActivity(intent);
                break;
            case R.id.tv_huitong:
                Intent intent1 = new Intent(this, TestAC01.class);
                startActivity(intent1);
                break;
            case R.id.tv_openCamera:

                break;
            default:
                break;
        }
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

    }
}
