# HOScrcpy 本地覆盖源码

`utils/HdcUtil.java` 来自上游 HOScrcpy，并保留本项目对设备列表的修改：只返回 `Connected` 的设备，过滤 USB / TCP 切换后残留的离线记录。

上游：<https://gitcode.com/OpenHarmonyToolkitsPlaza/HOScrcpy>

`build.sh` 将这份源码编译到 `build/`，覆盖 SDK JAR 中的同名类。克隆本仓库即可编译，无需获取原先的嵌套 Git 仓库。
