# Androccy
Android配件模式数据传输服务（名字是new bing起的：Android + Accessory）

## 功能用途
将连接电脑的Android设备切换为配件模式，并建立基于USB的直接通信。避开需要用户开启开发者模式并启用USB调试，或通过网络的方式才能于App进行通信。
灵感来自SpaceDesk的Android USB Cable Driver（让我发现了Android原来还能这么通信）

## 配件协议版本
1.0

暂未实现2.0的多HID模拟

## 运行环境
JDK 17

Linux (with libusb)

Windows我没搞定那个驱动，要么没权限操作USB，要么adb没了，如果你能搞定驱动，usb4java将能支持本项目在Windows下运行。

macOS没测，我没mac

## 传输规则
1. 只要USB数据线没有断开，或没有切换设备的USB传输模式，Android端就会一直认为流是有效的，阻塞读写，直到另一端写入或读取。
2. 同上条件，只要Android设备没有离开配件模式，无论有没有App读取数据，AndroccyDevice都是有效的，阻塞读写，直到另一端写入或读取。

## 编写目的
通过USB调试模式传输数据是真的不稳定啊，一不小心就关了调试模式~万恶的手机厂商总是想着各种法子让用户一不小心的就关掉调试模式和文件传输

## 用途举例
USB有线传输是靠谱的，2.0都有480Mbps的速度，大致可以用来：
1. 将电脑画面传输到手机上作为副屏（就像spacedesk那样）
2. 将声音传输到手机上当作临时的音响
3. 电脑和手机互传文件
4. 电脑和手机互相共享互联网
5. ……

## 项目质量
这是一个技术可行性研究的草稿，属于某个东西的副产物，效果完整，使用了极其先进的Kotlin 协程，要是你对协程没啥把握就不要用于正式项目中。

## 引入
### 本体逻辑
你可以直接将源码弄进你的项目里，或者使用gradle assemble后，得到build\libs\AndroccyServer-1.0-SNAPSHOT.jar，将jar引入项目。

### 依赖
将本项目使用在你的项目里的时候，需要同时添加以下依赖：
```kotlin
    implementation("org.usb4java:usb4java:1.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
```

## 例子
src/test/kotlin/MainKtTest.kt中包含了一个通信例子，需要搭配Android端的Demo一起运行即可看到效果：
http://github.com/Attect/Androccy-android-demo

注意：配件模式拉起的App依然没有保活效果，本项目及相关项目也不涉及保活，实现持久服务时自己想法子保持运行。

## 相关资料
[Android 开放配件协议 1.0](https://source.android.google.cn/docs/core/interaction/accessories/aoa?hl=zh-cn)

[Android 使用 Usb Accessory 模式与 linux 下位机进行通信](https://blog.csdn.net/lj402159806/article/details/69940628)

[usb4java](http://usb4java.org/quickstart/javax-usb.html)