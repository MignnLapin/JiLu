# JiLu

基于 [OpenTracks](https://codeberg.org/OpenTracksApp/OpenTracks) v4.22.0（Apache-2.0）的运动轨迹记录应用。

## 功能

- 记录 GPS 轨迹，支持蓝牙心率 / 踏频 / 功率传感器、语音播报、标记点
- 轨迹详情右上角地图图标可打开高德地图查看轨迹（起终点标记、自动缩放）
  - 首次使用需填写高德地图 Key，弹窗内可一键复制包名与 SHA-1
  - 也可在地图页右下角“地图 Key”按钮随时修改
- 自动检查 GitHub Releases 更新：启动后自动检查，发现新版本后下载并调用系统安装器安装
  - 无“安装未知应用”权限时会跳转授权页，返回后自动继续安装
  - 也可在轨迹列表右上角菜单手动“检查更新”

## 构建

```bash
./gradlew assembleIrreproducibleRelease
```

APK 输出：`build/outputs/apk/irreproducible/release/`

## 版本

- 当前版本：2.0（versionCode 20000）
- 包名：`de.dennisguse.opentracks`
