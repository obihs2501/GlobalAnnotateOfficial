# GlobalAnnotateOfficial

全局屏幕批注浮层。悬浮球/胶囊的运动逻辑按小米创作（`com.miui.creation` 4.1.8）
的官方实现移植成 Java，不再使用自研的 Kotlin 悬浮窗口状态机。

## 这一版做了什么

- **视觉宿主 + 触摸代理**：全屏 `TYPE_APPLICATION_OVERLAY` 只负责绘制
  （`FLAG_NOT_TOUCHABLE`），一个透明的小窗口跟随可见球/胶囊矩形，把
  `MotionEvent` 原样转发给宿主里的 `CapsuleView`。视觉运动全部是 View
  translation / outline progress，没有任何逐帧 `updateViewLayout`，所以不会
  出现窗口坐标系跳变。
- **官方 morph 不变量**：`wrapperTranslation + toolSlot == ballCentre`。
  展开时球沿弹簧飞到胶囊锚点，35% 处切成胶囊；收起时胶囊连同球一起飞到
  目标锚点，而不是原地缩成一个球。
- **8 锚点**：按实际落点最近锚点裁决，**不做速度预测**。落角留球，落边展开。
- **六色槽**：红/黄/蓝/黑/白 + 色轮，色轮永远最后一格。

## 官方代码来源

反编译自平板上的 `com.miui.creation` 4.1.8（`base.apk`，jadx 1.5.5）：

| 官方类 | 本仓库对应 | 内容 |
|---|---|---|
| `p452lilii.II11I` | `official/MinimizedBallView.java` | 96dp 最小化球，圆 outline、ImageView FIT_CENTER、8dp top |
| `p167IlI1iIi.IiII1IlI` + `p132Il11i1.l1IIii` | `official/SnapSolver.java` | 8 锚点位置求解，16dp inset，8 种 position |
| `p067IIiiIiiI.II11lli` | `official/FloatingMorph.java` | `m3494lIlilI1iii` 展开、`m3468Ii1IiiIiiIi` 收起、`m3489Il1IIl` 单向飞出、`m3484IIii1I`/`m3467IIllII` 拖动 |
| `p452lilii.I1Iili` + `p452lilii.l1li1ilI1` | `official/CapsuleView.java` | 胶囊内容、outline progress、六槽/工具/按钮、wrapper |
| `p452lilii.II11I` + wrapper | `official/FloatingHost.java` | wrapper（胶囊）+ 独立 ball 的宿主 |

官方注释里保留的 35% morph 门、`0.8/0.4` 弹簧、`0.78/0.36` 吸附弹簧、
30dp 死区、96dp 球、16dp 边距均按原值。

## 已知边界

- 官方笔迹引擎是 native `libmicurve.so`，本仓库不打包、不授权，画布只做
  平台 `Paint` 近似。
- root/Xposed 笔事件拦截与手指穿透是全局浮层特有的传输层，官方没有对应
  实现；后续单独接回。
- 颜色面板、笔刷面板、弧形盘尚未移植，当前胶囊只带固定六色和基础工具。

## 构建

不在本地编译。推送后由 GitHub Actions 构建：

```bash
./gradlew :app:assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`。
