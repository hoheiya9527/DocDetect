PaddleOCR Native Libraries Setup
=================================

请将Paddle-Lite预编译库放置到以下目录：

arm64-v8a/
├── libpaddle_light_api_shared.so
├── libc++_shared.so
└── libpaddle_ocr_jni.so (编译后生成)

armeabi-v7a/
├── libpaddle_light_api_shared.so
├── libc++_shared.so
└── libpaddle_ocr_jni.so (编译后生成)

下载地址：
https://github.com/PaddlePaddle/Paddle-Lite/releases

需要下载：
- inference_lite_lib.android.armv8.clang.c++_shared.with_extra.with_cv.tar.gz (64位)
- inference_lite_lib.android.armv7.clang.c++_shared.with_extra.with_cv.tar.gz (32位)

解压后在 cxx/lib/ 目录下找到 .so 文件
