// Tencent is pleased to support the open source community by making ncnn available.
//
// Copyright (C) 2025 THL A29 Limited, a Tencent company. All rights reserved.
//
// Licensed under the BSD 3-Clause License (the "License"); you may not use this file except
// in compliance with the License. You may obtain a copy of the License at
//
// https://opensource.org/licenses/BSD-3-Clause
//
// Unless required by applicable law or agreed to in writing, software distributed
// under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.

package com.tencent.ppocrv5ncnn;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.Toast;

import java.io.InputStream;
import java.util.Locale;

public class MainActivity extends Activity
{
    private static final String TAG = "MainActivity";
    private static final int REQUEST_IMAGE_PICK = 1;

    private PPOCRv5Ncnn ppocrv5ncnn = new PPOCRv5Ncnn();
    private LlmHelper llmHelper = new LlmHelper();
    private ModelDownloader modelDownloader;

    private OcrOverlayView imageView;
    private TextView textOcrTimer;
    private TextView textLlmTimer;
    private TextView textLlmStatus;
    private TextView textDownloadProgress;
    private ProgressBar progressDownload;
    private RadioGroup radioGroupModel;
    private Button buttonRunOCR;
    private Button buttonRunStructuring;
    private Button buttonRunLLM;
    private Button buttonDownloadLLM;
    private TextView textStructuringTimer;

    // Two-column results
    private TextView textRuleResult;
    private TextView textRuleTimer;
    private TextView textLlmResult;
    private TextView textLlmResultTimer;

    // OCR raw text toggle
    private Button buttonToggleOcr;
    private ScrollView scrollOcrRaw;
    private TextView textOcrRaw;
    private boolean ocrRawVisible = false;

    private CheckBox checkBoxGpu;

    private Bitmap currentBitmap;
    private String currentOcrResult;
    private String currentOcrResultWithBoxes;
    private int currentModel = 0; // 0 = mobile, 1 = server
    private int currentCpuGpu = 0; // 0 = CPU, 1 = GPU (Vulkan)

    private Handler timerHandler = new Handler(Looper.getMainLooper());
    private long ocrStartTime;
    private long llmStartTime;
    private boolean isOcrRunning = false;
    private boolean isLlmRunning = false;
    private boolean isDownloading = false;

    private Runnable ocrTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isOcrRunning) {
                long elapsedMillis = System.currentTimeMillis() - ocrStartTime;
                updateTimerDisplay(textOcrTimer, "OCR", elapsedMillis);
                timerHandler.postDelayed(this, 10);
            }
        }
    };

    private Runnable llmTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (isLlmRunning) {
                long elapsedMillis = System.currentTimeMillis() - llmStartTime;
                updateTimerDisplay(textLlmResultTimer, "LLM", elapsedMillis);
                timerHandler.postDelayed(this, 10);
            }
        }
    };

    @Override
    public void onCreate(Bundle savedInstanceState)
    {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        modelDownloader = new ModelDownloader(this);

        imageView = (OcrOverlayView) findViewById(R.id.imageView);
        textOcrTimer = (TextView) findViewById(R.id.textOcrTimer);
        textLlmTimer = (TextView) findViewById(R.id.textLlmTimer);
        textLlmStatus = (TextView) findViewById(R.id.textLlmStatus);
        textDownloadProgress = (TextView) findViewById(R.id.textDownloadProgress);
        progressDownload = (ProgressBar) findViewById(R.id.progressDownload);
        radioGroupModel = (RadioGroup) findViewById(R.id.radioGroupModel);
        checkBoxGpu = (CheckBox) findViewById(R.id.checkBoxGpu);
        buttonRunOCR = (Button) findViewById(R.id.buttonRunOCR);
        buttonRunStructuring = (Button) findViewById(R.id.buttonRunStructuring);
        textStructuringTimer = (TextView) findViewById(R.id.textStructuringTimer);
        buttonRunLLM = (Button) findViewById(R.id.buttonRunLLM);
        buttonDownloadLLM = (Button) findViewById(R.id.buttonDownloadLLM);

        // Two-column result views
        textRuleResult = (TextView) findViewById(R.id.textRuleResult);
        textRuleTimer = (TextView) findViewById(R.id.textRuleTimer);
        textLlmResult = (TextView) findViewById(R.id.textLlmResult);
        textLlmResultTimer = (TextView) findViewById(R.id.textLlmResultTimer);

        // OCR raw text toggle
        buttonToggleOcr = (Button) findViewById(R.id.buttonToggleOcr);
        scrollOcrRaw = (ScrollView) findViewById(R.id.scrollOcrRaw);
        textOcrRaw = (TextView) findViewById(R.id.textOcrRaw);

        Button buttonSelectImage = (Button) findViewById(R.id.buttonSelectImage);
        buttonSelectImage.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent intent = new Intent(Intent.ACTION_PICK);
                intent.setType("image/*");
                startActivityForResult(intent, REQUEST_IMAGE_PICK);
            }
        });

        buttonRunOCR.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runOCRWithTimer();
            }
        });

        buttonRunStructuring.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runStructuringWithTimer();
            }
        });

        buttonRunLLM.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                runLLMWithTimer();
            }
        });

        buttonDownloadLLM.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                startModelDownload();
            }
        });

        buttonToggleOcr.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ocrRawVisible = !ocrRawVisible;
                if (ocrRawVisible) {
                    scrollOcrRaw.setVisibility(View.VISIBLE);
                    buttonToggleOcr.setText("Hide OCR Text \u25B2");
                } else {
                    scrollOcrRaw.setVisibility(View.GONE);
                    buttonToggleOcr.setText("Show OCR Text \u25BC");
                }
            }
        });

        radioGroupModel.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(RadioGroup group, int checkedId) {
                int newModel = (checkedId == R.id.radioMobile) ? 0 : 1;
                if (newModel != currentModel) {
                    currentModel = newModel;
                    loadOcrModel();
                    clearResults();
                }
            }
        });

        checkBoxGpu.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                int newCpuGpu = isChecked ? 1 : 0;
                if (newCpuGpu != currentCpuGpu) {
                    currentCpuGpu = newCpuGpu;
                    loadOcrModel();
                    Toast.makeText(MainActivity.this,
                        isChecked ? "Using GPU (Vulkan)" : "Using CPU",
                        Toast.LENGTH_SHORT).show();
                }
            }
        });

        // Load OCR model
        loadOcrModel();

        // Check if LLM model exists and initialize
        checkAndInitializeLLM();
    }

    private void clearResults() {
        textRuleResult.setText("");
        textRuleTimer.setText("");
        textStructuringTimer.setText("");
        textLlmResult.setText("");
        textLlmResultTimer.setText("");
        textOcrRaw.setText("");
        textOcrTimer.setText("");
        textLlmTimer.setText("");
        currentOcrResult = null;
        currentOcrResultWithBoxes = null;
        buttonRunStructuring.setEnabled(false);
        buttonRunLLM.setEnabled(false);
        imageView.clearResults();
    }

    private void checkAndInitializeLLM() {
        textLlmStatus.setText("LLM: Checking...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean modelExists = modelDownloader.isModelDownloaded();

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (modelExists) {
                            initializeLLM();
                        } else {
                            textLlmStatus.setText("LLM: Model not found (~1.5GB download required)");
                            buttonDownloadLLM.setVisibility(View.VISIBLE);
                        }
                    }
                });
            }
        }).start();
    }

    private void startModelDownload() {
        if (isDownloading) {
            modelDownloader.cancel();
            return;
        }

        isDownloading = true;
        buttonDownloadLLM.setText("Cancel");
        progressDownload.setVisibility(View.VISIBLE);
        progressDownload.setProgress(0);
        textDownloadProgress.setVisibility(View.VISIBLE);
        textLlmStatus.setText("LLM: Downloading...");

        modelDownloader.downloadModel(new ModelDownloader.DownloadCallback() {
            @Override
            public void onProgress(final int percent, final long downloadedBytes, final long totalBytes) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        progressDownload.setProgress(percent);
                        textDownloadProgress.setText(String.format(Locale.US, "%s / %s (%d%%)",
                            ModelDownloader.formatBytes(downloadedBytes),
                            ModelDownloader.formatBytes(totalBytes),
                            percent));
                    }
                });
            }

            @Override
            public void onSuccess(final String modelPath) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        isDownloading = false;
                        progressDownload.setVisibility(View.GONE);
                        textDownloadProgress.setVisibility(View.GONE);
                        buttonDownloadLLM.setVisibility(View.GONE);
                        textLlmStatus.setText("LLM: Download complete, loading...");
                        initializeLLM();
                    }
                });
            }

            @Override
            public void onError(final String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        isDownloading = false;
                        progressDownload.setVisibility(View.GONE);
                        textDownloadProgress.setVisibility(View.GONE);
                        buttonDownloadLLM.setText("Download Model");
                        buttonDownloadLLM.setVisibility(View.VISIBLE);
                        textLlmStatus.setText("LLM: " + error);
                        Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void initializeLLM() {
        textLlmStatus.setText("LLM: Loading model...");

        new Thread(new Runnable() {
            @Override
            public void run() {
                final String modelPath = modelDownloader.getModelPath();
                final boolean success = llmHelper.initialize(MainActivity.this, modelPath);

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (success) {
                            textLlmStatus.setText("LLM: Ready (Qwen 2.5 1.5B)");
                            buttonDownloadLLM.setVisibility(View.GONE);
                            // Enable LLM button if we have OCR results
                            if (currentOcrResult != null && !currentOcrResult.isEmpty()) {
                                buttonRunLLM.setEnabled(true);
                            }
                        } else {
                            textLlmStatus.setText("LLM: Failed to load model");
                            buttonDownloadLLM.setVisibility(View.VISIBLE);
                            buttonDownloadLLM.setText("Retry");
                        }
                    }
                });
            }
        }).start();
    }

    private void loadOcrModel()
    {
        boolean ret = ppocrv5ncnn.loadModel(getAssets(), currentModel, 2, currentCpuGpu);
        if (!ret)
        {
            Log.e(TAG, "ppocrv5ncnn loadModel failed");
        }
    }

    private void updateTimerDisplay(TextView timerView, String prefix, long millis)
    {
        long seconds = millis / 1000;
        long ms = millis % 1000;
        timerView.setText(String.format(Locale.US, "%s: %d.%03ds", prefix, seconds, ms));
    }

    private void runOCRWithTimer()
    {
        if (currentBitmap == null) return;

        buttonRunOCR.setEnabled(false);
        buttonRunStructuring.setEnabled(false);
        buttonRunLLM.setEnabled(false);
        textOcrRaw.setText("Running OCR...");
        textRuleResult.setText("");
        textLlmResult.setText("");
        textRuleTimer.setText("");
        textStructuringTimer.setText("");
        textLlmResultTimer.setText("");

        ocrStartTime = System.currentTimeMillis();
        isOcrRunning = true;
        timerHandler.post(ocrTimerRunnable);

        new Thread(new Runnable() {
            @Override
            public void run() {
                // Get OCR result with bounding boxes for spatial extraction
                final String resultWithBoxes = ppocrv5ncnn.recognizeImageWithBoxes(currentBitmap);
                // Also get plain text for LLM
                final String result = ppocrv5ncnn.recognizeImage(currentBitmap);
                final long ocrEndTime = System.currentTimeMillis();

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        isOcrRunning = false;
                        timerHandler.removeCallbacks(ocrTimerRunnable);

                        long ocrElapsed = ocrEndTime - ocrStartTime;
                        updateTimerDisplay(textOcrTimer, "OCR", ocrElapsed);

                        if (result != null && !result.isEmpty())
                        {
                            textOcrRaw.setText(result);
                            currentOcrResult = result;
                            currentOcrResultWithBoxes = resultWithBoxes;
                            // Show bounding boxes on the image
                            imageView.setOcrResults(resultWithBoxes,
                                currentBitmap.getWidth(), currentBitmap.getHeight());
                            // Enable structuring and LLM buttons
                            buttonRunStructuring.setEnabled(true);
                            buttonRunLLM.setEnabled(llmHelper.isInitialized());
                        }
                        else
                        {
                            textOcrRaw.setText("No text recognized");
                            currentOcrResult = null;
                            currentOcrResultWithBoxes = null;
                            imageView.clearResults();
                            buttonRunStructuring.setEnabled(false);
                            buttonRunLLM.setEnabled(false);
                        }

                        buttonRunOCR.setEnabled(true);
                    }
                });
            }
        }).start();
    }

    private void runStructuringWithTimer()
    {
        if (currentOcrResultWithBoxes == null || currentOcrResultWithBoxes.isEmpty()) {
            Toast.makeText(this, "No OCR result to process", Toast.LENGTH_SHORT).show();
            return;
        }

        buttonRunStructuring.setEnabled(false);
        textRuleResult.setText("Running structuring...");

        final long startTime = System.currentTimeMillis();

        new Thread(new Runnable() {
            @Override
            public void run() {
                // Run spatial rule-based extraction using bounding box coordinates
                final String ruleResult = SpatialExtractor.extract(currentOcrResultWithBoxes);
                final long endTime = System.currentTimeMillis();

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        long elapsed = endTime - startTime;
                        updateTimerDisplay(textStructuringTimer, "Rule", elapsed);
                        textRuleResult.setText(ruleResult);
                        buttonRunStructuring.setEnabled(true);
                    }
                });
            }
        }).start();
    }

    private void runLLMWithTimer()
    {
        if (currentOcrResult == null || currentOcrResult.isEmpty()) {
            Toast.makeText(this, "No OCR result to process", Toast.LENGTH_SHORT).show();
            return;
        }

        if (!llmHelper.isInitialized()) {
            Toast.makeText(this, "LLM not initialized", Toast.LENGTH_SHORT).show();
            return;
        }

        buttonRunOCR.setEnabled(false);
        buttonRunLLM.setEnabled(false);
        textLlmResult.setText("Structuring with LLM...");

        llmStartTime = System.currentTimeMillis();
        isLlmRunning = true;
        timerHandler.post(llmTimerRunnable);

        llmHelper.structureKartuKeluargaAsync(currentOcrResult, new LlmHelper.LlmCallback() {
            @Override
            public void onResult(final String result) {
                final long endTime = System.currentTimeMillis();

                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        isLlmRunning = false;
                        timerHandler.removeCallbacks(llmTimerRunnable);

                        long elapsed = endTime - llmStartTime;
                        updateTimerDisplay(textLlmResultTimer, "LLM", elapsed);
                        updateTimerDisplay(textLlmTimer, "LLM", elapsed);

                        textLlmResult.setText(result);
                        buttonRunOCR.setEnabled(true);
                        buttonRunLLM.setEnabled(true);
                    }
                });
            }

            @Override
            public void onError(final String error) {
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        isLlmRunning = false;
                        timerHandler.removeCallbacks(llmTimerRunnable);

                        textLlmResultTimer.setText("LLM: Error");
                        textLlmResult.setText("Error: " + error);
                        buttonRunOCR.setEnabled(true);
                        buttonRunLLM.setEnabled(true);
                    }
                });
            }

            @Override
            public void onPartialResult(String partialResult) {
                // Not used for now
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data)
    {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_IMAGE_PICK && resultCode == RESULT_OK && data != null)
        {
            Uri imageUri = data.getData();
            if (imageUri != null)
            {
                try
                {
                    InputStream inputStream = getContentResolver().openInputStream(imageUri);
                    currentBitmap = BitmapFactory.decodeStream(inputStream);
                    inputStream.close();

                    if (currentBitmap != null)
                    {
                        if (currentBitmap.getConfig() != Bitmap.Config.ARGB_8888)
                        {
                            currentBitmap = currentBitmap.copy(Bitmap.Config.ARGB_8888, false);
                        }

                        imageView.setImageBitmap(currentBitmap);
                        buttonRunOCR.setEnabled(true);
                        buttonRunLLM.setEnabled(false);
                        clearResults();
                    }
                }
                catch (Exception e)
                {
                    Log.e(TAG, "Error loading image", e);
                    textRuleResult.setText("Error loading image");
                }
            }
        }
    }

    @Override
    protected void onDestroy()
    {
        super.onDestroy();
        isOcrRunning = false;
        isLlmRunning = false;
        timerHandler.removeCallbacks(ocrTimerRunnable);
        timerHandler.removeCallbacks(llmTimerRunnable);
        if (modelDownloader != null) {
            modelDownloader.cancel();
        }
        llmHelper.close();
    }
}
