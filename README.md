# AutoGLM-Android-Native: 纯手机端 AI 自动化助手

<p align="center">
  <b>打破环境限制，让 AI 真正接管你的安卓手机</b><br>
  <i>Powered by AutoGLM & Developed with Windsurf (AI)</i>
</p>

---

## 📖 项目简介

这是一个基于智谱 AI **AutoGLM** 开源项目二次开发的安卓原生应用。

**最大的不同点在于：**
过去运行 AutoGLM 需要连接电脑、配置复杂的 Python 环境和 ADB 调试；而本项目通过 **Shizuku** 权限，实现了在手机端独立运行。哪怕你不会编程，只要安装这个 APK，就能让 AI 直接在手机上执行各种复杂任务。

> 💡 **特别说明：** 本项目大部分代码由 **Windsurf (AI)** 协作生成。

---

## ✨ 核心亮点

* **🚫 零环境搭建**：无需连接电脑，无需 ADB 线，手机端安装即用。
* **🧠 视觉理解**：继承 AutoGLM 强大的屏幕解析能力。
* **🎭 虚拟屏幕控制（核心突破）**：支持**后台静默执行**，主屏幕与虚拟屏幕自由切换，互不干扰。
* **⚡ 极简操作**：只需输入自然语言指令（如：“帮我在美团点一份最近的奶茶”），AI 自动完成。

---

## 📸 视频/截图演示

| 功能描述 | 演示图 |
| :--- | :--- |
| **App 主界面** | ![Main UI](https://github.com/aryneal-boop/autoglm_KY/blob/master/XC/D62B6A4EE631195C197C2290C944EA51.jpg?raw=true) |
| **虚拟屏后台操作** | ![Virtual Screen](https://github.com/aryneal-boop/autoglm_KY/blob/master/XC/A663E962F9919F6543D16330F2323B7D.jpg?raw=true) |

---

## 🚀 快速开始

### 1. 前提条件
* **系统需求**：目前仅在 **小米 (HyperOS/MIUI)** 上通过测试。
* **权限管理**：手机必须安装并激活 [Shizuku](https://shizuku.riceroot.com/)。
* **API Key**：准备好你的 [智谱 AI](https://open.bigmodel.cn/) API Key。

### 2. 安装步骤
1. 前往 [Releases](../../releases) 页面下载最新的 APK 文件。
2. **下载并安装 [ADBKeyboard 输入法](https://github.com/aryneal-boop/autoglm_KY/blob/master/ADBKeyboard/ADBKeyboard.apk)**（用于 AI 自动输入文字）。
3. 在手机上启动 Shizuku。
4. 打开本 App，授予 Shizuku 权限。
5. 填入 API Key，输入指令，开始体验。

---

## 🚧 开发进度 (Roadmap)

- [x] 基于 Shizuku 的免电脑环境适配
- [x] 虚拟屏幕映射与后台控制逻辑
- [ ] 多机型适配
- [ ] 稳定性优化
- [ ] UI 界面美化

---

## ⚠️ 免责声明

本项目仅供学习和研究使用。使用 AI 自动化操作手机时，请遵守相关法律法规及平台服务协议，因使用本项目导致的任何账户风险或损失由用户自行承担。

---

## ⭐ 支持

如果你觉得这个项目有点意思，请给个 **Star** ！有什么不懂的可以 [提交 Issue](https://github.com/aryneal-boop/autoglm_KY/issues) 收到我会进行解答。