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

import com.google.mediapipe.tasks.genai.llminference.LlmInference;

import java.io.File;

public class LlmHelper {
    private static final String TAG = "LlmHelper";

    private LlmInference llmInference;
    private boolean isInitialized = false;

    private static final String KARTU_KELUARGA_PROMPT =
        "Indonesian Kartu Keluarga (Family Card) OCR. Text is arranged spatially with | separating columns.\n" +
        "Row numbers (1,2,3...) link the same person across Table 1 and Table 2.\n" +
        "Table 1 has: No, Nama Lengkap, NIK, Jenis Kelamin, Tempat Lahir, Tanggal Lahir, Agama, Pendidikan, Pekerjaan\n" +
        "Table 2 has: No, Status Perkawinan, Status Hubungan, Kewarganegaraan, Nama Ayah, Nama Ibu\n" +
        "Return ONLY valid JSON:\n" +
        "{\"no_kk\":\"\",\"kepala_keluarga\":\"\",\"alamat\":\"\",\"rt_rw\":\"\",\"desa_kelurahan\":\"\",\"kecamatan\":\"\",\"kabupaten_kota\":\"\",\"provinsi\":\"\",\"anggota_keluarga\":[{\"nama\":\"\",\"nik\":\"\",\"jenis_kelamin\":\"\",\"tempat_lahir\":\"\",\"tanggal_lahir\":\"\",\"agama\":\"\",\"pendidikan\":\"\",\"pekerjaan\":\"\",\"status_perkawinan\":\"\",\"hubungan_keluarga\":\"\",\"kewarganegaraan\":\"\",\"nama_ayah\":\"\",\"nama_ibu\":\"\"}]}\n\nOCR Text:\n";

    public interface LlmCallback {
        void onResult(String result);
        void onError(String error);
        void onPartialResult(String partialResult);
    }

    public LlmHelper() {
    }

    public boolean initialize(Context context, String modelPath) {
        try {
            File modelFile = new File(modelPath);
            if (!modelFile.exists()) {
                Log.e(TAG, "Model file not found: " + modelPath);
                return false;
            }

            LlmInference.LlmInferenceOptions options = LlmInference.LlmInferenceOptions.builder()
                .setModelPath(modelPath)
                .setMaxTokens(4096)
                .build();

            llmInference = LlmInference.createFromOptions(context, options);
            isInitialized = true;
            Log.d(TAG, "LLM initialized successfully");
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to initialize LLM: " + e.getMessage());
            isInitialized = false;
            return false;
        }
    }

    public boolean isInitialized() {
        return isInitialized;
    }

    public void structureKartuKeluarga(String ocrText, LlmCallback callback) {
        if (!isInitialized || llmInference == null) {
            callback.onError("LLM not initialized");
            return;
        }

        String fullPrompt = KARTU_KELUARGA_PROMPT + ocrText + "\n\nJSON Output:";

        try {
            String result = llmInference.generateResponse(fullPrompt);
            callback.onResult(result);
        } catch (Exception e) {
            callback.onError("LLM inference failed: " + e.getMessage());
        }
    }

    public void structureKartuKeluargaAsync(final String ocrText, final LlmCallback callback) {
        if (!isInitialized || llmInference == null) {
            callback.onError("LLM not initialized");
            return;
        }

        // Debug: Log the OCR text being passed
        Log.d(TAG, "OCR Text received (length=" + (ocrText != null ? ocrText.length() : "null") + "):");
        Log.d(TAG, "OCR Text: " + ocrText);

        new Thread(new Runnable() {
            @Override
            public void run() {
                String fullPrompt = KARTU_KELUARGA_PROMPT + ocrText + "\n\nJSON Output:";

                // Debug: Log the full prompt
                Log.d(TAG, "Full prompt (length=" + fullPrompt.length() + "):");
                Log.d(TAG, fullPrompt);

                try {
                    String result = llmInference.generateResponse(fullPrompt);
                    Log.d(TAG, "LLM Result: " + result);
                    callback.onResult(result);
                } catch (Exception e) {
                    Log.e(TAG, "LLM inference error", e);
                    callback.onError("LLM inference failed: " + e.getMessage());
                }
            }
        }).start();
    }

    public void close() {
        if (llmInference != null) {
            llmInference.close();
            llmInference = null;
        }
        isInitialized = false;
    }
}
