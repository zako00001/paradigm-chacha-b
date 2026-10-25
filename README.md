# 范式查查b

面向《范式：起源 / Paradigm: Reboot》国服的 Android 查分工具。

通过好游快爆授权读取自己的游戏成绩，在手机本地计算旧曲 B35＋新曲 B15，并生成可保存、分享的 B50 成绩图。

## 下载与使用

1. 前往 [Releases](https://github.com/zako00001/paradigm-chacha-b/releases) 下载 APK 并安装。
2. 确保手机已安装好游快爆，并登录绑定游戏账号的快爆账号。
3. 打开范式查查b，点击「通过好游快爆授权」，自行确认账号与授权。
4. 等待成绩同步，点击「生成 B50 图片」，保存到相册或使用系统分享。

系统要求：**Android 12 及以上**。当前仅支持国服好游快爆账号，不支持国际服。

无需 Root、抓包、安装 CA 证书或部署服务器。

## 功能

- 好游快爆客户端授权，无需在本软件输入账号密码。
- 读取个人最佳成绩和玩家资料，浏览成绩列表。
- 本地计算 B35＋B15，使用 Android Canvas 生成 PNG。
- 保存到相册，或通过系统分享发送图片。
- 本机缓存最近同步成绩；账号凭据通过 Android Keystore 加密保存在应用私有目录。

## B50 计算说明

本软件根据个人原始成绩在本地计算 B50，不读取官方付费 B50 页面。

当前随包曲库对应国服 **4.12**；曲绘、谱面定数与 B15 标记来自 PRP 社区数据。单谱 Rating 使用本地公式计算，CHAOTIC 不计入 B50。同 Rating 时按社区谱面 ID 倒序排列。

官方同分排序尚未逐项确认，因此导出标注「候选 B50」。曲库、定数或分季变化后需要更新软件；未知谱面不会进入计算。

头像使用快爆授权返回的账号头像；获取失败时显示昵称首字。

## 数据与隐私

授权及成绩请求由手机直接发送至好游快爆和游戏后台，本项目不部署接收账号凭据或成绩的中转服务器。曲绘优先使用随包素材，缺少随包素材时可能从曲库指定地址下载。

账号凭据与成绩保存在本机，可在应用内退出账号并清除相关缓存。请勿将个人 Token、Session 或账号缓存上传到仓库或 Issues。

## 从源码构建

使用 JDK 17、Android SDK 35，通过 Android Studio 打开仓库根目录，或运行：

```sh
./gradlew :app:assembleDebug
```

Windows：

```bat
gradlew.bat :app:assembleDebug
```

SDK 路径通过本机 `local.properties` 指定，不提交到仓库。默认构建产物是调试 APK；此处源码构建说明不代表仓库代为验证了发布附件的签名或可复现性。

## 许可与致谢

本项目为**源码公开、仅限非商业用途**的软件，具体条款见 [LICENSE](LICENSE)。允许非商业使用、学习、修改及免费分发；未经版权所有者另行书面授权，禁止商业使用。分发时须保留版权、许可及非商业限制。

第三方内容不因本项目许可而重新授权：

- 曲库、定数与分组信息：[PRP 社区](https://api.prp.icel.site/api/v2/songs)。
- Rating 公式参考：[paradigm-reboot-prober-go](https://github.com/IceLocke/paradigm-reboot-prober-go/blob/master/pkg/rating/rating.go)，原 MIT 许可证保留于 [rating-LICENSE.txt](app/src/main/assets/rating-LICENSE.txt)。
- 游戏名称、曲绘、图标及其他第三方素材的权利归各自权利人所有；本项目不授予这些内容的额外使用权。
- 第三方依赖遵循各自许可。

本项目为非官方社区工具，与游戏开发商、发行商及好游快爆无隶属关系。
