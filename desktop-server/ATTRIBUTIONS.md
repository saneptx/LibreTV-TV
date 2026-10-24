# 第三方处理工具与模型

处理工具、模型及下载包存放在忽略目录 `.desktop-server/tools`，不作为项目源码提交。下载脚本固定来源并校验 SHA-256。

| 工具 / 模型 | 来源与版本 | 说明 |
| --- | --- | --- |
| FSRCNNX_x2_8-0-4-1.glsl | [igv/FSRCNN-TensorFlow 1.1](https://github.com/igv/FSRCNN-TensorFlow/releases/tag/1.1) | 原始 shader 保留作者和 LGPL-3.0-or-later 声明，未修改模型 |
| FFmpeg / libplacebo | Ubuntu 24 FFmpeg 6.1 软件包；[FFmpeg](https://ffmpeg.org/)、[libplacebo](https://libplacebo.org/) | 保留软件包的许可文件；本项目以独立子进程调用 |
| Real-ESRGAN ncnn Vulkan | [官方 20220424 Ubuntu release](https://github.com/xinntao/Real-ESRGAN/releases/tag/v0.2.5.0) | 可选运行时，保留 release 中的许可文件 |
| realesr-general-x4v3 | [官方 Real-ESRGAN v0.2.5.0](https://github.com/xinntao/Real-ESRGAN/releases/tag/v0.2.5.0) | 可选通用场景模型；[SRVGGNetCompact 架构](https://github.com/xinntao/Real-ESRGAN/blob/master/realesrgan/archs/srvgg_arch.py) 来源于 BSD-3-Clause 项目 |

`convert-srvgg.py` 将官方 SRVGG 权重转为 NCNN 参数和权重，保留网络拓扑及数值，不需要安装 PyTorch。其源文件注明架构来源；受限反序列化仅用于脚本校验过的官方模型。

SHA-256：

- FSRCNNX shader：`e800dbc5c1c95185cc82216c597724533ff5f2880179f256eef600f03e8dc2ae`
- NCNN release ZIP：`e5aa6eb131234b87c0c51f82b89390f5e3e642b7b70f2b9bbe95b6a285a40c96`
- general-x4v3 原始权重：`8dc7edb9ac80ccdc30c3a5dca6616509367f05fbc184ad95b731f05bece96292`
