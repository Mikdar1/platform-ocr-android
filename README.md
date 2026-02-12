# Indonesian Kartu Keluarga OCR Android

An Android app for extracting structured data from Indonesian Kartu Keluarga (Family Card) documents using OCR and on-device LLM.

Based on [ncnn-android-ppocrv5](https://github.com/nihui/ncnn-android-ppocrv5) by nihui.

## Features

- **Image-based OCR**: Select images from gallery for OCR processing
- **PPOCRv5 Models**: Support for both Mobile (fast) and Server (accurate) models
- **GPU Acceleration**: Optional Vulkan GPU support for faster inference
- **Visual Bounding Boxes**: Tap detected text regions to see recognized text
- **Zoomable Preview**: Pinch-to-zoom and pan on the image preview
- **Dual Extraction Methods**:
  - **Rule-based**: Spatial extraction using bounding box coordinates
  - **LLM-based**: On-device Qwen 2.5 1.5B model for intelligent structuring

## Workflow

1. **Select Image**: Choose a Kartu Keluarga image from your gallery
2. **Run OCR**: Extract text with bounding boxes (Mobile or Server model, CPU or GPU)
3. **Structuring**: Run rule-based extraction using spatial coordinates
4. **Run LLM**: (Optional) Use on-device LLM for more accurate structuring

## Output Format

Both extraction methods output structured JSON:

```json
{
  "no_kk": "3201234567890001",
  "kepala_keluarga": "NAMA KEPALA KELUARGA",
  "alamat": "JL. CONTOH NO. 123",
  "rt_rw": "001/002",
  "desa_kelurahan": "DESA CONTOH",
  "kecamatan": "KECAMATAN CONTOH",
  "kabupaten_kota": "KABUPATEN CONTOH",
  "provinsi": "JAWA BARAT",
  "anggota_keluarga": [
    {
      "nama": "NAMA ANGGOTA",
      "nik": "3201234567890002",
      "jenis_kelamin": "LAKI-LAKI",
      "tempat_lahir": "BANDUNG",
      "tanggal_lahir": "01-01-1990",
      "agama": "ISLAM",
      "pendidikan": "SLTA/SEDERAJAT",
      "pekerjaan": "KARYAWAN SWASTA",
      "status_perkawinan": "KAWIN",
      "hubungan_keluarga": "KEPALA KELUARGA",
      "kewarganegaraan": "WNI",
      "nama_ayah": "NAMA AYAH",
      "nama_ibu": "NAMA IBU"
    }
  ]
}
```

## Dependencies

- [ncnn](https://github.com/Tencent/ncnn) - High-performance neural network inference framework
- [opencv-mobile](https://github.com/nihui/opencv-mobile) - Minimal OpenCV build for mobile
- [MediaPipe LLM](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference) - On-device LLM inference

## How to Build

### Step 1: Download ncnn
https://github.com/Tencent/ncnn/releases

- Download `ncnn-YYYYMMDD-android-vulkan.zip`
- Extract into `app/src/main/jni`
- Update `ncnn_DIR` path in `app/src/main/jni/CMakeLists.txt`

### Step 2: Download OpenCV
https://github.com/nihui/opencv-mobile

- Download `opencv-mobile-XYZ-android.zip`
- Extract into `app/src/main/jni`
- Update `OpenCV_DIR` path in `app/src/main/jni/CMakeLists.txt`

### Step 3: Build
Open the project with Android Studio and build.

## LLM Model

The app uses Qwen 2.5 1.5B quantized model for on-device inference. On first launch, tap "Download" to fetch the model (~1.5GB). The model is stored locally for offline use.

## Notes

- **GPU Mode**: Enable the GPU checkbox for Vulkan acceleration. Not all devices support this.
- **Server Model**: More accurate but slower than Mobile model. Recommended with GPU enabled.
- **Rule-based vs LLM**: Rule-based extraction is faster but less flexible. LLM handles variations better.

## License

BSD 3-Clause License - see the original [ncnn-android-ppocrv5](https://github.com/nihui/ncnn-android-ppocrv5) repository.
