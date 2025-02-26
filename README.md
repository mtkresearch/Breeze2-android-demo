# Overview

This project aims to create a community-driven platform for running AI capabilities locally on Android devices. Our goal is to provide a privacy-focused solution where all AI features work completely offline (airplane mode supported), ensuring your data never leaves your device.

<p align="center">
  <img src="assets/breeze2_demo_npu.gif" width="300" alt="NPU Backend Demo"/>&nbsp;&nbsp;&nbsp;&nbsp;
  <img src="assets/breeze2_demo_cpu.gif" width="300" alt="CPU Backend Demo"/>
</p>
<p align="center">
  <em>Left: NPU Backend &nbsp;&nbsp;&nbsp;&nbsp; Right: CPU Backend</em>
</p>

> [!NOTE]
> Unreasonable or abnormal responses from <b>CPU</b> backend are known issues (see <a href="https://github.com/mtkresearch/Breeze2-android-demo/issues/5">issue #5</a>). These issues are being investigated and will be fixed in future updates.

## Download & Try the App 🚀
You can download the latest APK in two variants:

1. [BreezeApp (breeze2)](https://huggingface.co/MediaTek-Research/BreezeApp/resolve/main/breeze-app-20250226_breeze2.apk)
2. [BreezeApp (llama3_2)](https://huggingface.co/MediaTek-Research/BreezeApp/resolve/main/breeze2-android-demo-20250219_llama3_2.apk)

Choose the APK based on your preferred default LLM model. Both versions support switching between models after installation.

> [!CAUTION]
> After installing the APK, you'll need to download and set up the required model files:
> 1. The app requires LLM model files that need to be downloaded separately and pushed to your device
> 2. Please follow the model setup instructions in the [Download required model files](#setup) section below (Step 4)
> 3. Without the model files, the app will not be able to function properly


## Project Vision
This app serves as an entry point for everyone, especially those not familiar with coding, to experience AI features directly on their phones. As MediaTek Research continues to develop and provide powerful AI models with various capabilities, this app will act as a carrier to showcase these models and make them accessible to users.

## Community Focus
As a kick-off project, we acknowledge that there might be stability issues and areas for improvement. We welcome developers and enthusiasts to join us in enhancing this project. Feel free to:
- Report issues or bugs
- Suggest new features
- Submit pull requests
- Share your experience and feedback

Together, let's build a privacy-focused AI experience that everyone can use!

## Features

- 💬 Text-based chat interface
- 🗣️ Voice input/output support
- 📸 Image understanding capabilities
- 🔄 Multiple backend support for each component:
  - LLM/VLM: Executorch framework, MediaTek backend (Future)
  - ASR: Sherpa-ONNX, MediaTek backend (Future)
  - TTS: Sherpa-TTS, MediaTek backend (Future)

    | Model Type | Local CPU | MediaTek NPU | Default |
    |:---------:|:---------:|:-------:|:--------:|
    | LLM       |     ✅     |    ✅    |    -    |
    | VLM       |     🚧     |    ❌    |    -    |
    | ASR       |     🚧     |    ❌    |    -    |
    | TTS       |     ✅     |    ❌    |    -    |
🚨 Note: VLM is currently not supported due to the lack of support for image processing in Executorch. 

## Prerequisites

- Android Studio Ladybug (2024.2.1 Patch 3) or newer
- Android SDK 31 or higher
- NDK 26.1.10909125 or higher
- CMake 3.10.0 or higher

## Setup

1. Clone the repository:
    ```bash
    git clone https://github.com/mtkresearch/Breeze2-android-demo.git
    ```

2. Open the project in Android Studio:
    - Launch Android Studio
    - Select "Open" from the welcome screen
    - Navigate to and select the `breeze-app` folder
    - Click "OK" to open the project
    - Wait for the project sync and indexing to complete

3. Connect your Android device:
    - Connect your phone to your computer using a USB cable
    - On your phone, allow file transfer/Android Auto when prompted
    - When prompted "Allow USB debugging?", check "Always allow from this computer" and tap "Allow"
    - In Android Studio, select your device from the device dropdown menu in the toolbar
    - If your device is not listed, make sure your USB cable supports data transfer

4. Download required model files:
    - LLM models: \
        a. BreezeTiny:
        ```bash
        # Download from Hugging Face
        git lfs install
        git clone https://huggingface.co/MediaTek-Research/Breeze-Tiny-Instruct-v0_1-mobile
        
        # Push to Android device
        adb push Breeze-Tiny-Instruct-v0_1-mobile/Breeze-Tiny-Instruct-v0_1.pte /data/local/tmp/llama/
        adb push Breeze-Tiny-Instruct-v0_1-mobile/tokenizer.bin /data/local/tmp/llama/
        ```
        b. Llama3.2-3B-Instruct:
        ```bash
        # Download from Hugging Face
        git lfs install
        git clone https://huggingface.co/MediaTek-Research/Llama3.2-3B-Instruct-mobile
        
        # Push to Android device
        adb push Llama3.2-3B-Instruct-mobile/llama3_2.pte /data/local/tmp/llama/
        adb push Llama3.2-3B-Instruct-mobile/tokenizer.bin /data/local/tmp/llama/
        ```

    - VLM models:\
        Coming soon...
        <!-- a. LLaVA-1.5-7B
        ```bash
        # Download from Hugging Face
        git lfs install
        git clone https://huggingface.co/MediaTek-Research/llava-1.5-7b-hf-mobile
        
        # Push to Android device
        adb push llava-1.5-7b-hf-mobile/llava.pte /data/local/tmp/llava/
        adb push llava-1.5-7b-hf-mobile/tokenizer.bin /data/local/tmp/llava/
        ``` -->
    - ASR models (place in `app/src/main/assets/`):
        ```bash
        wget https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/
        sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2
        
        tar xvf sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2
        rm sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20.tar.bz2
        ```
    - TTS models (place in `app/src/main/assets/`):
        ```bash
        # Download from Hugging Face
        git lfs install
        git clone https://huggingface.co/MediaTek-Research/Breeze2-VITS-onnx
        ```

5. Download aar file
    - Open the "Project tab" in the left panel of Android Studio
    - Click the dropdown and select "Project" instead of Android view
    - Find "download_prebuilt_lib.sh" inside `breeze-app`
    - Open the "Terminal" in the left panel, and run the bash file to retrieve aar file
    ```bash
    sh {YOURPATH}/Breeze2-android-demo/breeze-app/download_prebuilt_lib.sh
    ```

6. Build the project in Android Studio

## Changing Default Backend or LLM Model

To change the default backend (CPU) or LLM model (Breeze), follow these steps:

1. Open the "AppConstants.java" file, located at:
   ```bash
   cd {YOURPATH}/Breeze2-android-demo/breeze-app/app/src/main/java/com/mtkresearch/gai_android/utils/AppConstants.java
   ```
2. Use your preferred programming tools to modify the following constants and set your desired backend and model:
   ```jave
   // Backend Constants
   public static final String BACKEND_CPU = "cpu" ;
   public static final String BACKEND_MTK = "mtk" ;
   public static final String BACKEND_DEFAULT = BACKEND_CPU ; // Change to desired backend
   ...
   // Model Files and Paths
   public static final String LLAMA_MODEL_FILE = "llama3_2.pte" ;
   public static final String BREEZE_MODEL_FILE = "Breeze-Tiny-Instruct-v0_1.pte" ;
   public static final String LLAMA_MODEL_DIR = "/data/local/tmp/llama/" ;
   public static final String MODEL_PATH = LLAMA_MODEL_DIR + BREEZE_MODEL_FILE ; // Change to desired model
   ```
   
   - Changing the Backend:\
      By default, the backend is set to "CPU". If you want to use "MTK" as the application backend, modify the following line:
      ```jave
      // Backend Constants
      ...
      public static final String BACKEND_DEFAULT = BACKEND_MTK ; // Change to desired backend
      ```

   - Changing the LLM Model:\
      By default, the model is set to "Breeze2". If you want to use "Llama3_2", modify the following line:
      ```jave
      // Model Files and Paths
      ...
      public static final String MODEL_PATH = LLAMA_MODEL_DIR + LLAMA_MODEL_FILE ; // Change to desired model
      ```

3. After modifying the backend or LLM model, "rebuild" the project in Android Studio to apply the changes.


## Architecture

The application follows a service-based architecture where each AI capability (LLM, VLM, ASR, TTS) is implemented as an Android service. Each service supports multiple backends with graceful fallback:

1. Primary MediaTek backend (🚧 Still in development...)
2. ⭐️ Open-source alternatives (Executorch/Sherpa)
3. Android system defaults

Key components:
- `ChatActivity`: Main UI for text/voice interaction
- `AudioChatActivity`: Dedicated voice interface (🚧 Still in development...)
- `*EngineService`: Service implementations for each AI capability

## Contributing

Contributions are welcome! Please read our [Contributing Guidelines](CONTRIBUTING.md) before submitting pull requests.

## Known Issues

1. **VLM Support (Executorch)**: VLM features are currently non-functional due to limitations in Executorch's image processing capabilities. See [executorch#6189](https://github.com/pytorch/executorch/issues/6189) for updates.

2. **Audio Chat Interface**: The dedicated voice interface (`AudioChatActivity`) is still under development and may have limited functionality.

3. **MediaTek NPU Backend**: Support for MediaTek NPU acceleration is currently in development. Only CPU inference is fully supported at this time.

Please check our [Issues](https://github.com/mtkresearch/Breeze2-android-demo/issues) page for the most up-to-date status of these and other known issues.

## Acknowledgments

- [Executorch](https://github.com/pytorch/executorch) for LLM/VLM framework
- [k2-fsa/sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) for ASR/TTS capabilities
- MediaTek Research for core AI engines

## Note

This is a research project and some features may require specific hardware support or proprietary components. The open-source version provides alternative implementations where possible.
