package com.lpin.realtime_camera;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.LayoutInflater;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.app.Fragment;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

@SuppressLint("ValidFragment")
public class CameraFragment extends Fragment {
    // 로그 출력할 때 태그를 상수로 지정
    public static final String TAG = "[IC]CameraFragment";

    // 일반적으로 Callback이나 Listener라는 용어는 이벤트가 발생했을 때
    // 작업을 수행하기 위한 함수나 클래스 또는 인터페이스에 붙이는 단어입니다.
    // 특히 Listener 는 java에서는 Interface로 한정합니다.
    // 이 경우 구현해야 함는 메소드가 하나라면 lambda로 대체가 가능합니다ㅣ
    // -> 라는 표현이 보이면 lambda입니다.
    private ConnectionCallback connectionCallback;
    private ImageReader.OnImageAvailableListener imageAvailableListener;

    // 카메라 크기
    private Size inputSize;
    // 카메라 아이디
    // 0 : 후면카메라, 1: 전면 카메라
    private String cameraId;
    
    // 동영상 출력하기 위한 사용자 정의 뷰
    private AutoFitTextureView autoFitTextureView = null;

    // thread 사용을 위한 변수
    private HandlerThread backgroundThread = null;
    private Handler backgroundHandler = null;

    // 미리보기 크기와 기기의 방향을 저장할 변수
    private Size previewSize;
    private int sensorOrientation;

    // thread를 사용하기 때문에 multi thread 환경에서 공유자원의
    // 사용문제를 해결하기 위한 인스턴스
    // 정수는 자원을 동시에 사용할 thread 의 개수
    private final Semaphore cameraOpenCloseLock = new Semaphore(1);

    // 카메라 관련 변수
    private CameraDevice cameraDevice;
    private CaptureRequest.Builder previewRequestBuilder;
    private ImageReader previewReader;
    private CameraCaptureSession captureSession;

    // 생성자의 접근 지정자 private : 외부에서 인스턴스 생성을 못함
    private CameraFragment(final ConnectionCallback callback,
                           final ImageReader.OnImageAvailableListener imageAvailableListener,
                           final Size inputSize,
                           final String cameraId) {
        this.connectionCallback = callback;
        this.imageAvailableListener = imageAvailableListener;
        this.inputSize = inputSize;
        this.cameraId = cameraId;
    }

    // 인스턴스를 생성해서 리턴해주는 static 메소드 - 팩토리 패턴
    // 인스턴스를 생성자를 이용하지 않고 별도의 메소드에서 생성
    // 그 이유는 생성하는 과정이 복잡해서 생성자를 노출시키지 않기 위한 목적과 효율을 위해서임
    public static CameraFragment newInstance(
            final ConnectionCallback callback,
            final ImageReader.OnImageAvailableListener imageAvailableListener,
            final Size inputSize,
            final String cameraId) {
        return new CameraFragment(callback, imageAvailableListener, inputSize, cameraId);
    }
    // 화면을 출력하기 위한 뷰를 만들 때 호출되는 메소드
    // 레이아웃 파일의 내용만 불러서 리턴
    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_camera, container, false);
    }

    // 화면 출력이 된 후 호출되는 메소드
    // 동영상 미리보기 view를 찾아옵니다.
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        autoFitTextureView = view.findViewById(R.id.autoFitTextureView);
    }

    // Activity 나 Fragment 가 화면에 보여질 때마다 호출되는 메소드
    // 스레드를 시작
    // TextureView 가 사용 가능하지 않으면 리스너를 설정하고
    // 그렇지 않으면 카메라의 내용을 출력
    @Override
    public void onResume() {
        super.onResume();
        startBackgroundThread();

        if(!autoFitTextureView.isAvailable())
            autoFitTextureView.setSurfaceTextureListener(surfaceTextureListener);
        else
            openCamera(autoFitTextureView.getWidth(), autoFitTextureView.getHeight());
    }

    // 출력이 중지될 때 호출되는 메소드
    // 카메라를 닫고 스레드를 중지
    @Override
    public void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    // 스레드를 생성해서 시작하는 메소드
    private void startBackgroundThread() {
        backgroundThread = new HandlerThread("ImageListener");
        backgroundThread.start();
        backgroundHandler = new Handler(backgroundThread.getLooper());
    }

    // 스레드를 중지하는 메소드
    private void stopBackgroundThread() {
        backgroundThread.quitSafely();
        try {
            backgroundThread.join();
            backgroundThread = null;
            backgroundHandler = null;
        } catch (final InterruptedException e) {
            e.printStackTrace();
        }
    }

    // TextureView 의 리스너
    private final TextureView.SurfaceTextureListener surfaceTextureListener =
            new TextureView.SurfaceTextureListener() {
                // Texture 가 유효하면 호출되는 메소드
                @Override
                public void onSurfaceTextureAvailable(
                        final SurfaceTexture texture, final int width, final int height) {
                    openCamera(width, height);
                }
                // Texture 의 사이즈가 변경되면 회출되는 메소드
                // 회전이 발생하면 호출되어 크기가 바뀌는게 아니라 가로와 세로의 크기가 바뀝니다.
                @Override
                public void onSurfaceTextureSizeChanged(
                        final SurfaceTexture texture, final int width, final int height) {
                    configureTransform(width, height);
                }
                // Texture 가 소멸될 때 호출되는 메소드
                @Override
                public boolean onSurfaceTextureDestroyed(final SurfaceTexture texture) {
                    return true;
                }
                // Texture 의 내용이 변경될 때 호출되는 메소드
                @Override
                public void onSurfaceTextureUpdated(final SurfaceTexture texture) {
                }
            };

    // 카메라를 사용할 수 있도록 해주는 메소드
    @SuppressLint("MissingPermission")
    private void openCamera(final int width, final int height) {
        // 카메라 사용 객체 찾아오기
        final Activity activity = getActivity();
        final CameraManager manager =
                (CameraManager)activity.getSystemService(Context.CAMERA_SERVICE);

        // 카메라를 설정하고 크기를 정의하는 사용자 정의 메소드를 호출
        setupCameraOutputs(manager);
        configureTransform(width, height);

        try {
            // 2.5초동안 카메라를 가져오지 못하면 화면 중지
            if (!cameraOpenCloseLock.tryAcquire(2500, TimeUnit.MILLISECONDS)) {
                Toast.makeText(getContext(),
                        "Time out waiting to lock camera opening.",
                        Toast.LENGTH_LONG).show();
                activity.finish();
            } else {
                // 카메라 사용
                // cameraId가 0이면 후면카메라, 1이면 전면 카메라
                manager.openCamera(cameraId, stateCallback, backgroundHandler);
            }
        } catch (final InterruptedException | CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // 카메라 설정하는 메소드
    // 수정할 부분이 거의 없음
    private void setupCameraOutputs(CameraManager manager) {
        try {
            final CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

            final StreamConfigurationMap map =characteristics.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);

            sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);

            previewSize = chooseOptimalSize(
                    map.getOutputSizes(SurfaceTexture.class),
                    inputSize.getWidth(),
                    inputSize.getHeight());

            final int orientation = getResources().getConfiguration().orientation;
            if (orientation == Configuration.ORIENTATION_LANDSCAPE) {
                autoFitTextureView.setAspectRatio(previewSize.getWidth(), previewSize.getHeight());
            } else {
                autoFitTextureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
            }
        } catch (final CameraAccessException cae) {
            cae.printStackTrace();
        }

        connectionCallback.onPreviewSizeChosen(previewSize, sensorOrientation);
    }

    // 회전 처리를 위한 메소드
    private void configureTransform(final int viewWidth, final int viewHeight) {
        final Activity activity = getActivity();
        if (null == autoFitTextureView || null == previewSize || null == activity) {
            return;
        }

        final int rotation = activity.getWindowManager().getDefaultDisplay().getRotation();
        final Matrix matrix = new Matrix();
        final RectF viewRect = new RectF(0, 0, viewWidth, viewHeight);
        final RectF bufferRect =
                new RectF(0, 0, previewSize.getHeight(), previewSize.getWidth());
        final float centerX = viewRect.centerX();
        final float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(
                    centerX - bufferRect.centerX(),
                    centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, Matrix.ScaleToFit.FILL);
            final float scale = Math.max(
                    (float) viewHeight / previewSize.getHeight(),
                    (float) viewWidth / previewSize.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        autoFitTextureView.setTransform(matrix);
    }
    // 카메라의 크기를 설정하는 메소드
    // 정사각형형태로 만들기 위해서 너비와 높이 중에서 작은 것을 선택하여 비율을 조정
    protected Size chooseOptimalSize(final Size[] choices, final int width, final int height) {
        final int minSize = Math.min(width, height);
        final Size desiredSize = new Size(width, height);

        final List<Size> bigEnough = new ArrayList<Size>();
        final List<Size> tooSmall = new ArrayList<Size>();
        for (final Size option : choices) {
            if (option.equals(desiredSize)) {
                return desiredSize;
            }

            if (option.getHeight() >= minSize && option.getWidth() >= minSize) {
                bigEnough.add(option);
            } else {
                tooSmall.add(option);
            }
        }

        if (bigEnough.size() > 0) {
            return Collections.min(bigEnough, new CompareSizesByArea());
        } else {
            return Collections.max(tooSmall, new CompareSizesByArea());
        }
    }
    
    // 카메라의 상태가 변경될 때 호출되는 리스너
    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        // 세마포어를 취득해서 락을 해제
        @Override
        public void onOpened(final CameraDevice cd) {
            cameraOpenCloseLock.release();
            cameraDevice = cd;
            createCameraPreviewSession();
        }

        @Override
        public void onDisconnected(final CameraDevice cd) {
            cameraOpenCloseLock.release();
            cd.close();
            cameraDevice = null;
        }

        @Override
        public void onError(final CameraDevice cd, final int error) {
            cameraOpenCloseLock.release();
            cd.close();
            cameraDevice = null;
            final Activity activity = getActivity();
            if (null != activity) {
                activity.finish();
            }
        }
    };
    
    // 카메라 사용 권한을 취득하고 사용하기 직전에 호출하기 위한 메소드
    // 미리보기 와 카메라에 관련된 설정을 수행
    private void createCameraPreviewSession() {
        try {
            final SurfaceTexture texture = autoFitTextureView.getSurfaceTexture();
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());

            final Surface surface = new Surface(texture);

            // 미리보기 포맷을 설정 yuv 에서 rgb 로 변경 해야 함
            previewReader = ImageReader.newInstance(previewSize.getWidth(),
                    previewSize.getHeight(), ImageFormat.YUV_420_888, 2);
            previewReader.setOnImageAvailableListener(imageAvailableListener,
                    backgroundHandler);

            previewRequestBuilder = cameraDevice.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(surface);
            previewRequestBuilder.addTarget(previewReader.getSurface());

            previewRequestBuilder.set(
                    CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            previewRequestBuilder.set(
                    CaptureRequest.CONTROL_AE_MODE,
                    CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
            previewRequestBuilder.set(
                    CaptureRequest.FLASH_MODE,
                    CameraMetadata.FLASH_MODE_TORCH);

            cameraDevice.createCaptureSession(
                    Arrays.asList(surface, previewReader.getSurface()),
                    sessionStateCallback,
                    null);
        } catch (final CameraAccessException e) {
            e.printStackTrace();
        }
    }

    // 카메라 화면을 가져온 후 호출되는 리스너
    private final CameraCaptureSession.StateCallback sessionStateCallback =
            new CameraCaptureSession.StateCallback() {
                // 설정에 성공한 경우
                @Override
                public void onConfigured(final CameraCaptureSession cameraCaptureSession) {
                    if (null == cameraDevice) {
                        return;
                    }

                    captureSession = cameraCaptureSession;
                    try {
                        captureSession.setRepeatingRequest(previewRequestBuilder.build(),
                                null, backgroundHandler);
                    } catch (final CameraAccessException e) {
                        e.printStackTrace();
                    }
                }
                // 설정에 실패한 경우
                @Override
                public void onConfigureFailed(final CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(getActivity(), "CameraCaptureSession Failed", Toast.LENGTH_SHORT).show();
                }
            };
    
    // 카메라 종료하는 메소드
    // 이 메소드는 구현하지 않아도 애플리케이션 자체에 아무런 영향도 없음
    private void closeCamera() {
        try {
            cameraOpenCloseLock.acquire();
            if (null != captureSession) {
                captureSession.close();
                captureSession = null;
            }
            if (null != cameraDevice) {
                cameraDevice.close();
                cameraDevice = null;
            }
            if (null != previewReader) {
                previewReader.close();
                previewReader = null;
            }
        } catch (final InterruptedException e) {
            throw new RuntimeException("Interrupted while trying to lock camera closing.", e);
        } finally {
            cameraOpenCloseLock.release();
        }
    }

    public interface ConnectionCallback {
        void onPreviewSizeChosen(Size size, int cameraRotation);
    }

    // java 는 크기비교를 할 때 숫자 데이터는 부등호로 하지만
    // 숫자 데이터가 아닌 경우는 comparator 의 compare 메소드를 이용
    // 크기 비교를 해서 정렬을 하고자 할 때 구현하는 인터페이스가 Comparator 와 Comparable
    static class CompareSizesByArea implements Comparator<Size> {
        @Override
        public int compare(final Size lhs, final Size rhs) {
            return Long.signum(
                    (long) lhs.getWidth() * lhs.getHeight() - (long) rhs.getWidth() * rhs.getHeight());
        }
    }
}