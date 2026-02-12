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

## How to Use

1. **Select Image**: Tap "Select Image" to choose a Kartu Keluarga image from your gallery
2. **Choose Model**: Select Mobile (fast) or Server (accurate) model
3. **Enable GPU** (optional): Check the GPU box for Vulkan acceleration
4. **Run OCR**: Tap "Run OCR" to extract text with bounding boxes
5. **Structuring**: Tap "Structuring" to run rule-based extraction
6. **Run LLM** (optional): Tap "Run LLM" for LLM-based extraction

## LLM Model

The app uses Qwen 2.5 1.5B quantized model for on-device inference. On first launch, tap "Download" to fetch the model (~1.5GB). The model is stored locally for offline use.

## License

BSD 3-Clause License - see the original [ncnn-android-ppocrv5](https://github.com/nihui/ncnn-android-ppocrv5) repository.
