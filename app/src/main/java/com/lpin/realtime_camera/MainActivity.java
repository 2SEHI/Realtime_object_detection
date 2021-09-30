package com.lpin.realtime_camera;


import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.Manifest;
import android.app.Fragment;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Pair;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {
    public static final String TAG = "[IC]MainActivity";

    //카메라 사용 권한을 위한 변수
    private static final String CAMERA_PERMISSION = Manifest.permission.CAMERA;
    //사용 권한을 요청하고 구분하기 위한 변수
    private static final int PERMISSION_REQUEST_CODE = 1;

    //결과를 출력할 텍스트 뷰
    private TextView textView;
    //분류기
    private Classifier cls;
    // thread 참조 변수
    private HandlerThread handlerThread;
    // thread 가 작업을 수행하다가 화면 출력을 하기 위해 사용하는 개개=
    private Handler handler;
    // 카메라 미리보기의 크기
    private int previewWidth = 0;
    private int previewHeight = 0;
    // 카메라 이미지
    private Bitmap rgbFrameBitmap = null;
    // 작업 중인지 확인
    // boolean 변수는 앞에 is를 붙여서 구분
    private boolean isProcessingFrame = false;
    // 기기 방향을 위한 변수
    private int sensorOrientation = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(null);
        setContentView(R.layout.activity_main);

        // 액티비티가 실행되는 동안 화면이 계속 켜져 있도록 설정
        // 안드로이드나  카메라는 일정시간 이후 저절로 화면이 꺼짐
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        textView = findViewById(R.id.textView);

        try {
            cls = new Classifier(this);
            cls.init();
        } catch (IOException ioe) {
            ioe.printStackTrace();
        }
        
        // 동적 권한을 설정
        // 권한이 있는 경우
        if(checkSelfPermission(CAMERA_PERMISSION) == PackageManager.PERMISSION_GRANTED) {
            // Fragment 설정을 위한 메소드 호출
            setFragment();
        } else {
            // 권한이 없는 경우, 권한 요청
            requestPermissions(new String[]{CAMERA_PERMISSION}, PERMISSION_REQUEST_CODE);
        }
    }

    //동적 권한 요청을 한 후 선택하면 호출되는 콜백 메소드
    @Override
    public void onRequestPermissionsResult(
            int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        if(requestCode == PERMISSION_REQUEST_CODE) {
            // 권한 사용을 허가하면 호출
            if(grantResults.length > 0 && allPermissionsGranted(grantResults)) {
                setFragment();
            }
            // 권한 사용을 취소하면 호출
            else {
                Toast.makeText(this, "permission denied", Toast.LENGTH_LONG).show();
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    }

    //여러 권한을 요청한 경우 모든 권한을 확인하는 사용자 정의 메소드
    private boolean allPermissionsGranted(final int[] grantResults) {
        for (int result : grantResults) {
            if (result != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    // Fragment 설정 메소드
    protected void setFragment() {
        // model 의 input 크기 가져오기
        Size inputSize = cls.getModelInputSize();
        // 카메라의 전면 또는 후면을 선택
        String cameraId = chooseCamera();

        // 모델의 크기를 가지고 카메라 화면의 크기를 설정
        if(inputSize.getWidth() > 0 && inputSize.getHeight() > 0 && !cameraId.isEmpty()) {
            Fragment fragment = CameraFragment.newInstance(

                    (size, rotation) -> {
                        previewWidth = size.getWidth();
                        previewHeight = size.getHeight();
                        sensorOrientation = rotation - getScreenOrientation();
                    },
                    // 카메라로부터 이미지 받아오기
                    reader->processImage(reader),
                    inputSize,
                    cameraId);

            Log.d(TAG, "inputSize : " + cls.getModelInputSize() +
                    "sensorOrientation : " + sensorOrientation);
            // 작업을 반영(Transaction)하기 위한 프레그먼트 설정
            getFragmentManager().beginTransaction().replace(
                    R.id.fragment, fragment).commit();
        } else {
            Toast.makeText(this, "Can't find camera", Toast.LENGTH_SHORT).show();
        }
    }

    // 카메라에서 넘겨받은 이미지를 이용해서 추론하는 메소드
    protected void processImage(ImageReader reader) {
        // 이미지의 크기가 없으면 종료
        if (previewWidth == 0 || previewHeight == 0) {
            return;
        }
        // 이미지를 저장할 비트맵이 준비되어 있지 않으면 생성함
        if(rgbFrameBitmap == null) {
            rgbFrameBitmap = Bitmap.createBitmap(
                    previewWidth,
                    previewHeight,
                    Bitmap.Config.ARGB_8888);
        }

        if (isProcessingFrame) {
            return;
        }

        isProcessingFrame = true;

        final Image image = reader.acquireLatestImage();
        if (image == null) {
            isProcessingFrame = false;
            return;
        }
        // Yuv 포맷을 rgb 포맷으로 변경 
        YuvToRGBConverter.yuvToRgb(this, image, rgbFrameBitmap);

        // lambda 를 이용한 thread 처리
        runInBackground(() -> {
            if (cls != null && cls.isInitialized()) {
                // 추론
                final Pair<String, Float> output = cls.classify(rgbFrameBitmap, sensorOrientation);

                runOnUiThread(() -> {
                    // 추론한 결과를 출력
                    String resultStr = String.format(Locale.ENGLISH,
                            "class : %s, prob : %.2f%%",
                            output.first, output.second * 100);
                    // 추론결과를 화면에 표시
                    textView.setText(resultStr);
                });
            }
            //
            image.close();
            isProcessingFrame = false;
        });
    }

    // synchronized 는 동기화 메소드를 만들어 줍니다.
    // 이 메소드는 동시에 호출되기 않음
    protected synchronized void runInBackground(final Runnable r) {
        if (handler != null) {
            // 메시지 큐에 미시지를 전달
            // POST 로 호출하면 다른 작업이 없을 때 작업을 수행
            handler.post(r);
        }
    }

    // Activity 가 활성화될 때 Thread 와 handler 를 생성
    @Override
    public synchronized void onResume() {
        super.onResume();
        handlerThread = new HandlerThread("InferenceThread");
        handlerThread.start();
        handler = new Handler(handlerThread.getLooper());
    }

    // Activity 가 중지되었을 때 Thread 중지
    @Override
    public synchronized void onPause() {
        handlerThread.quitSafely();
        try {
            handlerThread.join();
            handlerThread = null;
            handler = null;
        } catch (final InterruptedException e) {
            e.printStackTrace();
        }
        super.onPause();
    }

    // Activity 가 파괴될 때 메모리 정리
    @Override
    protected synchronized void onDestroy() {
        cls.finish();
        super.onDestroy();
    }

    // Activity 가 시작될 때 호출되는 메소드
    // 호출된다는 보장이 없어서 잘 안쓰임
    @Override
    public synchronized void onStart() {
        super.onStart();
    }

    // Activity 가 중지될 때 호출되는 메소드
    // 호출된다는 보장이 없어서 잘 안쓰임
    @Override
    public synchronized void onStop() {
        super.onStop();
    }

    // 기기의 방향을 리턴하는 메소드
    protected int getScreenOrientation() {
        switch (getWindowManager().getDefaultDisplay().getRotation()) {
            case Surface.ROTATION_270:
                return 270;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_90:
                return 90;
            default:
                return 0;
        }
    }

    // 카메라의 전면 또는 후면을 선택하는 메소드
    // 전면이나 후면 카메라로 카메라를 고정할 것이라면 1이나 0을 사용하면 되므로 이 메소드를 구현할 필요가 없습니다.
    private String chooseCamera() {
        final CameraManager manager =
                (CameraManager)getSystemService(Context.CAMERA_SERVICE);
        try {
            for (final String cameraId : manager.getCameraIdList()) {
                final CameraCharacteristics characteristics =
                        manager.getCameraCharacteristics(cameraId);

                final Integer facing = characteristics.get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    return cameraId;
                }
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }

        return "";
    }

}