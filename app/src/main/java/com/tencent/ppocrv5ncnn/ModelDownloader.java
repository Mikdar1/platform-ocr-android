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

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class ModelDownloader {
    private static final String TAG = "ModelDownloader";

    // Qwen 2.5 1.5B Instruct Q8 model from HuggingFace (no auth required)
    private static final String MODEL_URL =
        "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task";

    private static final String MODEL_FILENAME = "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv1280.task";
    private static final long EXPECTED_SIZE = 1600000000L; // ~1.6GB

    public interface DownloadCallback {
        void onProgress(int percent, long downloadedBytes, long totalBytes);
        void onSuccess(String modelPath);
        void onError(String error);
    }

    private Context context;
    private volatile boolean isCancelled = false;

    public ModelDownloader(Context context) {
        this.context = context;
    }

    public String getModelPath() {
        File modelDir = context.getExternalFilesDir("models");
        if (modelDir == null) {
            modelDir = new File(context.getFilesDir(), "models");
        }
        return new File(modelDir, MODEL_FILENAME).getAbsolutePath();
    }

    public boolean isModelDownloaded() {
        File modelFile = new File(getModelPath());
        // Check if file exists and has reasonable size (> 100MB)
        return modelFile.exists() && modelFile.length() > 100 * 1024 * 1024;
    }

    public void cancel() {
        isCancelled = true;
    }

    public void downloadModel(final DownloadCallback callback) {
        isCancelled = false;

        new Thread(new Runnable() {
            @Override
            public void run() {
                HttpURLConnection connection = null;
                InputStream input = null;
                FileOutputStream output = null;

                try {
                    // Create model directory
                    File modelDir = context.getExternalFilesDir("models");
                    if (modelDir == null) {
                        modelDir = new File(context.getFilesDir(), "models");
                    }
                    if (!modelDir.exists()) {
                        modelDir.mkdirs();
                    }

                    File modelFile = new File(modelDir, MODEL_FILENAME);
                    File tempFile = new File(modelDir, MODEL_FILENAME + ".tmp");

                    // Check if already downloaded
                    if (modelFile.exists() && modelFile.length() > 100 * 1024 * 1024) {
                        callback.onSuccess(modelFile.getAbsolutePath());
                        return;
                    }

                    Log.d(TAG, "Starting download from: " + MODEL_URL);

                    URL url = new URL(MODEL_URL);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setConnectTimeout(30000);
                    connection.setReadTimeout(30000);
                    connection.setInstanceFollowRedirects(true);
                    connection.connect();

                    int responseCode = connection.getResponseCode();
                    if (responseCode != HttpURLConnection.HTTP_OK) {
                        callback.onError("Server returned HTTP " + responseCode);
                        return;
                    }

                    long totalBytes = connection.getContentLength();
                    if (totalBytes <= 0) {
                        totalBytes = EXPECTED_SIZE;
                    }

                    input = connection.getInputStream();
                    output = new FileOutputStream(tempFile);

                    byte[] buffer = new byte[8192];
                    long downloadedBytes = 0;
                    int bytesRead;
                    int lastPercent = -1;

                    while ((bytesRead = input.read(buffer)) != -1) {
                        if (isCancelled) {
                            callback.onError("Download cancelled");
                            tempFile.delete();
                            return;
                        }

                        output.write(buffer, 0, bytesRead);
                        downloadedBytes += bytesRead;

                        int percent = (int) ((downloadedBytes * 100) / totalBytes);
                        if (percent != lastPercent) {
                            lastPercent = percent;
                            callback.onProgress(percent, downloadedBytes, totalBytes);
                        }
                    }

                    output.close();
                    output = null;

                    // Rename temp file to final file
                    if (tempFile.renameTo(modelFile)) {
                        Log.d(TAG, "Download complete: " + modelFile.getAbsolutePath());
                        callback.onSuccess(modelFile.getAbsolutePath());
                    } else {
                        callback.onError("Failed to save model file");
                        tempFile.delete();
                    }

                } catch (Exception e) {
                    Log.e(TAG, "Download failed", e);
                    callback.onError("Download failed: " + e.getMessage());
                } finally {
                    try {
                        if (output != null) output.close();
                        if (input != null) input.close();
                        if (connection != null) connection.disconnect();
                    } catch (Exception ignored) {}
                }
            }
        }).start();
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
