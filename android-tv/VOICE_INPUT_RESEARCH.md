# JMGO NS1 语音搜索可行性

本次只调查方案，未安装语音引擎、修改系统输入法或录制音频。语音搜索尚未实现。

## 实机检查结果

设备为用户的 JMGO NS1 4K 高亮版，系统版本 1.1.31.23。Android 11，WebView Chrome 83，系统 ABI 为 `armeabi-v7a,armeabi`（32 位）。

- `android.speech.action.RECOGNIZE_SPEECH` 查询没有匹配的 Activity。
- `android.speech.RecognitionService` 查询没有匹配的 Service，默认识别服务配置为空。
- 已安装 `com.jmgo.ai.voice`，但没有发现供第三方应用获取识别文本的标准接口；本次也未找到适用于该固件的公开坚果语音 SDK。不能把厂商定制广播当成稳定的开发接口。
- 默认输入法为 `com.jmgo.inputmethod.pinyin/.PinyinIME`，其声明的权限不包含 `RECORD_AUDIO`。不能假定它自身支持录音识别。
- 系统未声明 `android.hardware.microphone`，**但音频策略中列出了可用的 Built-In Mic / AUDIO_DEVICE_IN_BUILTIN_MIC**。缺少 feature 不等于没有麦克风；普通第三方应用能否读到有效音频仍需实机验证。
- 遥控器语音键能否交给 LibreTV、遥控器麦克风如何路由，也尚未验证，不能由内置麦克风路由推断。

## 可行路径

| 路径 | 实现方式 | 当前判断 |
| --- | --- | --- |
| App 原生录音 + 电脑本地识别 | 用户点击搜索栏麦克风按钮后申请录音权限；Android AudioRecord 采集短音频，发送给局域网服务器，服务器识别后把文字填入搜索栏，再确认搜索 | 最适合当前“电脑服务 + 投影仪客户端”的结构；先验证内置麦克风，避免把识别模型放到投影仪上 |
| 手机语音输入 + 电视搜索 | 手机打开配对的遥控页面，用手机输入法语音转文字后提交；电视接收文字并搜索 | 可作为兼容性优先的备选，不依赖投影仪麦克风或系统语音服务；需要新增手机与电视的配对及消息通道 |
| 投影仪本地离线识别 | App 内集成 sherpa-onnx 等引擎和中文模型 | 官方支持 Android armv7；仍需验证录音、运行速度、模型内存和包大小；本机不应选仅支持高通 QNN 的版本 |
| 调用系统语音助手 / 替换输入法 | 依赖系统标准识别服务或厂商接口 | 当前固件缺少标准识别组件，不能直接接入；替换输入法本身也不能保证遥控器语音或麦克风可用 |

## 建议验证顺序

1. 单独准备可手动启动的录音诊断功能，用户主动点击并授权后，显示输入设备、录音状态及音量变化，确认能否采到有效人声。
2. 若能采音，优先在电脑上部署中文识别（例如 sherpa-onnx + SenseVoice），返回文字让用户确认后搜索。模型需另行下载；实际延迟和影片名识别率需要测试。
3. 第一版使用搜索栏里的麦克风按钮，不依赖遥控器专有语音键；之后再单独研究按键和麦克风路由。
4. 若厂商限制第三方采音，采用手机语音输入。手机可以使用系统输入法转文字，LibreTV 只传输文字，不必在手机网页直接录音。

## 官方与项目资料

- [Android SpeechRecognizer：检查识别服务是否可用及 Android 11 包可见性要求](https://developer.android.com/reference/android/speech/SpeechRecognizer)
- [Android AudioRecord：原生音频采集](https://developer.android.com/reference/android/media/AudioRecord)
- [Android 运行时权限](https://developer.android.com/training/permissions/requesting)
- [sherpa-onnx Android：离线识别及构建方式](https://k2-fsa.github.io/sherpa/onnx/android/index.html)
- [SenseVoice：中文支持、平台和 ABI 列表](https://k2-fsa.github.io/sherpa/onnx/sense-voice/index.html)
- [SenseVoice 上游项目](https://github.com/QwenAudio/SenseVoice)
