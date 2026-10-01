活塞动画场景回归测试：

```powershell
.\gradlew.bat -I tests/piston-smoke.gradle runClient
```

测试会在开发客户端中创建独立的空白世界和 Y=70 的投影，循环执行黏性活塞推动、拉回石头。它检查实际画面提交路径中是否出现带覆盖层的活塞头和被推动方块的中间位置，结束后自动关闭客户端；没有动画提交时构建会失败。

结果写入 `run/piston-scene-result.txt`，过程截图保存在 `run/screenshots/`。测试类使用独立的构建目录，不进入正常 `build` 生成的成品 JAR。可将需要复测的渲染模组放入开发实例的 `run/mods/`。
