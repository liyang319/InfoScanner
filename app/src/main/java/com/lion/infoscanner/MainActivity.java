package com.lion.infoscanner;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.os.Bundle;
import android.os.Environment;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;

import com.benjaminwan.ocrlibrary.OcrEngine;
import com.benjaminwan.ocrlibrary.OcrResult;
import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    private PreviewView viewFinder;
    private ImageCapture imageCapture;
    private TextView resultTextView;

    private final ExecutorService ocrExecutor = Executors.newSingleThreadExecutor();
    private OcrEngine ocrEngine;

    private final ActivityResultLauncher<String> requestPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    startCamera();
                } else {
                    Toast.makeText(this, "需要相机权限才能拍照", Toast.LENGTH_SHORT).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        viewFinder = findViewById(R.id.viewFinder);
        resultTextView = findViewById(R.id.resultTextView);
        resultTextView.setMovementMethod(new ScrollingMovementMethod());

        Button captureButton = findViewById(R.id.captureButton);
        captureButton.setOnClickListener(v -> takePhoto());

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA);
        }

        ocrExecutor.execute(this::initOcrEngine);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ocrExecutor.shutdown();
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> cameraProviderFuture =
                ProcessCameraProvider.getInstance(this);

        cameraProviderFuture.addListener(() -> {
            try {
                ProcessCameraProvider cameraProvider = cameraProviderFuture.get();

                Preview preview = new Preview.Builder().build();
                preview.setSurfaceProvider(viewFinder.getSurfaceProvider());

                imageCapture = new ImageCapture.Builder().build();

                CameraSelector cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

                cameraProvider.unbindAll();
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, imageCapture);
            } catch (ExecutionException | InterruptedException e) {
                Toast.makeText(this, "启动相机失败", Toast.LENGTH_SHORT).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void takePhoto() {
        if (imageCapture == null) {
            return;
        }

        File photoFile = new File(getOutputDirectory(),
                new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(new Date()) + ".jpg");

        ImageCapture.OutputFileOptions outputOptions =
                new ImageCapture.OutputFileOptions.Builder(photoFile).build();

        imageCapture.takePicture(outputOptions, ContextCompat.getMainExecutor(this),
                new ImageCapture.OnImageSavedCallback() {
                    @Override
                    public void onImageSaved(ImageCapture.OutputFileResults results) {
                        Toast.makeText(MainActivity.this,
                                "照片已保存: " + photoFile.getAbsolutePath(), Toast.LENGTH_SHORT).show();
                        resultTextView.setText("识别中...");
                        recognizePhoto(photoFile);
                    }

                    @Override
                    public void onError(ImageCaptureException exception) {
                        Toast.makeText(MainActivity.this,
                                "拍照失败: " + exception.getMessage(), Toast.LENGTH_SHORT).show();
                    }
                });
    }

    private void initOcrEngine() {
        if (ocrEngine != null) {
            return;
        }
        try {
            ocrEngine = new OcrEngine(getApplicationContext());
        } catch (Exception e) {
            Log.e(TAG, "OCR engine init failed", e);
        }
    }

    private void recognizePhoto(File photoFile) {
        ocrExecutor.execute(() -> {
            OcrEngine engine = ocrEngine;
            if (engine == null) {
                initOcrEngine();
                engine = ocrEngine;
            }
            if (engine == null) {
                runOnUiThread(() -> {
                    resultTextView.setText("OCR 引擎初始化失败");
                    Toast.makeText(this, "OCR 引擎初始化失败", Toast.LENGTH_SHORT).show();
                });
                return;
            }

            Bitmap input = decodeSampledBitmap(photoFile.getAbsolutePath(), 2000, 2000);
            if (input == null) {
                runOnUiThread(() -> resultTextView.setText("图片解码失败"));
                return;
            }

            String text = "";
            try {
                Bitmap output = input.copy(Bitmap.Config.ARGB_8888, true);
                OcrResult result = engine.detect(input, output, 0);
                text = result != null ? result.getStrRes() : "";
            } catch (Exception e) {
                Log.e(TAG, "OCR detect failed", e);
            } finally {
                input.recycle();
            }

            final String display = text;
            runOnUiThread(() -> {
                if (display == null || display.isEmpty()) {
                    resultTextView.setText("未识别到文字");
                } else {
                    resultTextView.setText(display);
                }
            });
        });
    }

    private Bitmap decodeSampledBitmap(String path, int maxWidth, int maxHeight) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, opts);

        int sampleSize = 1;
        while (opts.outWidth / sampleSize > maxWidth || opts.outHeight / sampleSize > maxHeight) {
            sampleSize *= 2;
        }

        opts.inJustDecodeBounds = false;
        opts.inSampleSize = sampleSize;
        Bitmap bitmap = BitmapFactory.decodeFile(path, opts);
        if (bitmap == null) {
            return null;
        }

        int rotation = readExifRotation(path);
        if (rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);
            if (rotated != bitmap) {
                bitmap.recycle();
            }
            bitmap = rotated;
        }
        return bitmap;
    }

    private int readExifRotation(String path) {
        try {
            ExifInterface exif = new ExifInterface(path);
            int orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL);
            switch (orientation) {
                case ExifInterface.ORIENTATION_ROTATE_90:
                    return 90;
                case ExifInterface.ORIENTATION_ROTATE_180:
                    return 180;
                case ExifInterface.ORIENTATION_ROTATE_270:
                    return 270;
                default:
                    return 0;
            }
        } catch (IOException e) {
            return 0;
        }
    }

    private File getOutputDirectory() {
        File mediaDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES);
        if (mediaDir != null && !mediaDir.exists()) {
            mediaDir.mkdirs();
        }
        return mediaDir != null ? mediaDir : getFilesDir();
    }
}
