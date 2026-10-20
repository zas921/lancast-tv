# 参与贡献

欢迎提交错误修复、兼容性反馈和文档改进。贡献按本项目的 [MIT 许可证](LICENSE) 提供。

## 本地验证

- JDK 17：`./test.ps1`（Windows PowerShell 5.1 或 PowerShell 7）。
- 真实局域网设备发现：`./test.ps1 -Multicast`。该检查需要可用局域网，CI 不自动执行。
- Node.js 22+：`npm ci --ignore-scripts`、`npx playwright install chromium`、`npm test`。
- Python 3.12：`python -m pip install -r requirements-test.txt`、`python tests/verify-qr.py`。
- Android SDK：安装 Platform 35 与 Build Tools 34.0.0，运行 `./build.ps1`。

Android 运行时代码保持不依赖第三方库，并兼容 API 22。新增 Android API 需提供版本判断或兼容路径；请勿仅为消除弃用提示而破坏旧电视支持。

修复协议或并发问题时，请增加能复现原问题的测试，并说明哪些场景只通过了模拟测试，哪些经过了真实手机和电视验证。修改版本号时同步更新 AndroidManifest.xml、app/build.gradle、package.json 与网页页脚。

## 提交前

不要提交签名密钥、登录令牌、私有媒体、IDE 配置、构建缓存或本地配置。APK 放在 Releases / Actions 构建产物中；不提交到源码历史。

兼容性 Issue 请提供电视型号与系统版本、手机与 App 版本、连接方式、复现步骤。屏幕镜像和专有协议不在当前媒体投屏范围内；新增相关能力应先说明协议与授权方案。
