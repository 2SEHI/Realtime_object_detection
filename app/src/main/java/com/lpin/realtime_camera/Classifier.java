package com.lpin.realtime_camera;

import android.content.Context;
import android.graphics.Bitmap;
import android.util.Pair;
import android.util.Size;

import org.tensorflow.lite.Tensor;
import org.tensorflow.lite.support.common.FileUtil;
import org.tensorflow.lite.support.common.ops.NormalizeOp;
import org.tensorflow.lite.support.image.ImageProcessor;
import org.tensorflow.lite.support.image.TensorImage;
import org.tensorflow.lite.support.image.ops.ResizeOp;
import org.tensorflow.lite.support.image.ops.ResizeWithCropOrPadOp;
import org.tensorflow.lite.support.image.ops.Rot90Op;
import org.tensorflow.lite.support.label.TensorLabel;
import org.tensorflow.lite.support.model.Model;
import org.tensorflow.lite.support.tensorbuffer.TensorBuffer;
import static org.tensorflow.lite.support.image.ops.ResizeOp.ResizeMethod.NEAREST_NEIGHBOR;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class Classifier {
    
    //  추론을 위한 2개의 파일의 이름을 상수로 설정
    private static final String MODEL_NAME = "mobilenet_imagenet_model.tflite";
    private static final String LABEL_FILE = "labels.txt";

    // 앱내의 자원을 사용하기 위한 인스턴스 참조 변수
    Context context;
    
    // 추론을 하기 위한 인스턴스 참조 변수
    Model model;
    
    // 추론을 위해서 사용할 입력에 관한 변수
    // 전처리를 위해 사용
    int modelInputWidth, modelInputHeight, modelInputChannel;
    TensorImage inputImage;
    
    // 추론 결과를 저장하기 위한 변수
    TensorBuffer outputBuffer;
    
    // 추론 결과 해석을 위해서 레이블 파일의 내용을 저장할 변수
    private List<String> labels;
    // 초기화 수행 여부를 저장할 변수
    private boolean isInitialized = false;

    // Classifier 생성자
    // Context 만 넘겨받아서 대입합니다.
    public Classifier(Context context) {
        this.context = context;
    }

    // 초기화 메소드
    public void init() throws IOException {
        // 모델 생성
        model = Model.createModel(context, MODEL_NAME);
        // 입출력 관련 데이터를 설정하는 메소드 호출
        initModelShape();
        // 레이블 파일의 내용을 읽어옵니다.
        labels = FileUtil.loadLabels(context, LABEL_FILE);
        // 초기화를 수행했다고 표시
        isInitialized = true;
    }

    // 초기화 여부를 저장한 변수를 리턴하는 메소드
    public boolean isInitialized() {
        return isInitialized;
    }

    // 입출력 정보를 설정하기 위한 메소드
    private void initModelShape() {
        // 모델의 입력 데이터에 대한 정보 가져오기
        Tensor inputTensor = model.getInputTensor(0);
        
        // 입력데이터의 모양을 변수에 저장
        int[] shape = inputTensor.shape();
        modelInputChannel = shape[0];
        modelInputWidth = shape[1];
        modelInputHeight = shape[2];
        
        // 입력데이터 모양을 설정
        inputImage = new TensorImage(inputTensor.dataType());

        // 출력 데이터 모양을 설정
        Tensor outputTensor = model.getOutputTensor(0);
        outputBuffer = TensorBuffer.createFixedSize(outputTensor.shape(),
                outputTensor.dataType());
    }
    
    // 입력에 사용할 이미지의 크기를 리턴하는 메소드
    public Size getModelInputSize() {
        if (!isInitialized)
            return new Size(0, 0);
        return new Size(modelInputWidth, modelInputHeight);
    }

    // Android 카메라로 촬영한 이미지를 추론에 맞는 형태로 변환해주는 메소드
    private Bitmap convertBitmapToARGB8888(Bitmap bitmap) {
        return bitmap.copy(Bitmap.Config.ARGB_8888, true);
    }

    // 
    private TensorImage loadImage(final Bitmap bitmap, int sensorOrientation) {
        // 형식변환
        if (bitmap.getConfig() != Bitmap.Config.ARGB_8888) {
            inputImage.load(convertBitmapToARGB8888(bitmap));
        } else {
            inputImage.load(bitmap);
        }
        // 자를 이미지의 크기를 설정
        // 최솟값의 크기를 찾아서 이미지를 최솟값 크기에 맞는 정사각형으로 만들기 위해서   
        int cropSize = Math.min(bitmap.getWidth(), bitmap.getHeight());
        // 회전을 처리하기 위한 설정
        int numRotation = sensorOrientation / 90;

        // 이미지 전처리

        ImageProcessor imageProcessor = new ImageProcessor.Builder()
                // 1. 이미지 확대 축소 - 정사각형인지 직사각형인지에 따라 다르므로 논문을 읽어봐야 합니다.
                // 이미지 잡음이 들어가므로 작은 사이즈에 맞춰서 해야됨.
                // 확대의 경우는 padding으로 옆의 데이터를 설정하므로 잡음이 확대 될수도 잇음
                // 직사각형이면 하지 않아도 됩니다.
                .add(new ResizeWithCropOrPadOp(cropSize, cropSize))
                // 2. 이미지 사이즈조정
                .add(new ResizeOp(modelInputWidth, modelInputHeight, NEAREST_NEIGHBOR))
                // 3. 회전
                .add(new Rot90Op(numRotation))
                // 4. 정규화 - 전이 학습을 하는 경우는 논문을 읽어봐야 합니다.
                .add(new NormalizeOp(0.0f, 255.0f))
                .build();
        return imageProcessor.process(inputImage);
    }

    // 추론 메소드
    public Pair<String, Float> classify(Bitmap image, int sensorOrientation) {
        // 입력데이터 생성
        inputImage = loadImage(image, sensorOrientation);
        
        Object[] inputs = new Object[]{inputImage.getBuffer()};
        Map<Integer, Object> outputs = new HashMap();
        outputs.put(0, outputBuffer.getBuffer().rewind());
        // 추론
        model.run(inputs, outputs);
        // 추론 결과를 저장
        Map<String, Float> output =
                new TensorLabel(labels, outputBuffer).getMapWithFloatValue();
        // 추론 결과를 해석하는 메소드를 호출해서 리턴
        return argmax(output);
    }

    // 기기 방향이 없을 때 추론하는 메소드
    public Pair<String, Float> classify(Bitmap image) {
        return classify(image, 0);
    }
    
    // 추론 결과 해석 메소드
    // 추론을 하면 클래스의 레이블이 리턴되지 않고 인덱스가 리턴되므로
    // 인덱스를 레이블로 변경하고 가장 확률이 높은 데이터만 추출
    private Pair<String, Float> argmax(Map<String, Float> map) {
        String maxKey = "";
        float maxVal = -1;
        for (Map.Entry<String, Float> entry : map.entrySet()) {
            float f = entry.getValue();
            if (f > maxVal) {
                maxKey = entry.getKey();
                maxVal = f;
            }
        }
        return new Pair<>(maxKey, maxVal);
    }

    // 메모리 정리하는 메소드
    public void finish() {
        if (model != null) {
            model.close();
            isInitialized = false;
        }
    }

}