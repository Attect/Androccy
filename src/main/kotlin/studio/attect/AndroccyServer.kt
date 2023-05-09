package studio.attect

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import org.usb4java.*
import java.nio.ByteBuffer
import kotlin.coroutines.CoroutineContext
import kotlin.experimental.or

/**
 * Android USB Accessory 通信服务<br>
 * 用于直接操作USB设备并将Android设备切换至配件模式<br>
 * 提供通信能力<br>
 * 仅限运行在PC端
 */
class AndroccyServer(
    val accessoryManufacturerName: String,
    val accessoryModelName: String,
    val accessoryDescription: String? = null,
    val accessoryVersion: String? = null,
    val accessoryURI: String? = null,
    val accessorySerialNumber: String? = null
) : CoroutineScope, LibUsbOperator() {
    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + job + CoroutineName("studio.attect.AndroccyServer")

    private val context = Context()

    private var watchEventJob: Job? = null

    /**
     * 需要判定为Android设备的UsbId<br>
     * 不同厂家不同设备的id都不一样，需要明确指定
     */
    private val androidUsbDeviceIdList = ArrayList<UsbDeviceId>()
    private val androidUsbDeviceIdListLock = Mutex()

    private val manufacturerNameBuffer: ByteBuffer by lazy {
        accessoryManufacturerName.toDirectByteBuffer()
    }

    private val modelNameBuffer: ByteBuffer by lazy {
        accessoryModelName.toDirectByteBuffer()
    }

    private val descriptionBuffer: ByteBuffer? by lazy {
        accessoryDescription?.toDirectByteBuffer()
    }

    private val versionBuffer: ByteBuffer? by lazy {
        accessoryVersion?.toDirectByteBuffer()
    }

    private val uriBuffer: ByteBuffer? by lazy {
        accessoryURI?.toDirectByteBuffer()
    }

    private val serialNumberBuffer: ByteBuffer? by lazy {
        accessorySerialNumber?.toDirectByteBuffer()
    }

    private val activeAccessoryDeviceList = arrayListOf<AccessoryDevice>()

    val accessoryDeviceChannel = Channel<AccessoryDevice>()

    init {
        if (System.getProperty("os.name") != "Linux") {
            throw UnSupportOsException()
        }

        LibUsb.init(context).throwOnFailedCode("无法初始化libusb")
    }

    private suspend fun useAndroidUsbDeviceIdList(block: (idList: ArrayList<UsbDeviceId>) -> Unit) {
        androidUsbDeviceIdListLock.lock()
        block.invoke(androidUsbDeviceIdList)
        androidUsbDeviceIdListLock.unlock()
    }

    /**
     * 添加需要切换为配件模式的USB id信息
     */
    suspend fun addUsbDeviceId(usbDeviceId: UsbDeviceId) {
        useAndroidUsbDeviceIdList {
            it.add(usbDeviceId)
        }
    }

    fun startWatchEvent() {
        if (watchEventJob != null) return
        val callbackHandle = HotplugCallbackHandle()
        val callback = object : HotplugCallback {
            override fun processEvent(context: Context?, device: Device?, event: Int, userData: Any?): Int {
                if (context == null || device == null) return 0
                val deviceDescriptor = DeviceDescriptor()
                try {
                    LibUsb.getDeviceDescriptor(device, deviceDescriptor).throwOnFailedCode("无法获取USB事件对应设备的信息")
                } catch (e: LibUsbException) {
                    e.printStackTrace()
                }
                when (event) {
                    LibUsb.HOTPLUG_EVENT_DEVICE_ARRIVED -> {
                        launch(coroutineContext) {
//                            println("设备接入：VID_%04x,PID_%04x".format(deviceDescriptor.idVendor(), deviceDescriptor.idProduct()))
                            useAndroidUsbDeviceIdList { idList ->
                                androidUsbDeviceIdList.find { it.vendorId == deviceDescriptor.idVendor() && it.productId == deviceDescriptor.idProduct() }?.let {
                                    switchAndroidDeviceToAccessoryMode(device, deviceDescriptor)
                                }
                            }
                            if (deviceDescriptor.idVendor() == GOOGLE_VENDOR &&
                                (deviceDescriptor.idProduct() == PRODUCT_ACCESSORY || deviceDescriptor.idProduct() == PRODUCT_ACCESSORY_ABD)
                            ) {
//                                println("配件设备：VID_%04x,PID_%04x".format(deviceDescriptor.idVendor(), deviceDescriptor.idProduct()))
                                runCatching {
                                    AccessoryDevice(device)
                                }.also { it.exceptionOrNull()?.printStackTrace() }.getOrNull()?.let {
                                    activeAccessoryDeviceList.add(it)
                                    accessoryDeviceChannel.send(it)
//                                    println("配件模式成功：VID_%04x,PID_%04x".format(deviceDescriptor.idVendor(), deviceDescriptor.idProduct()))
                                }
                            }
                        }
                    }

                    LibUsb.HOTPLUG_EVENT_DEVICE_LEFT -> {
//                        println("设备断开：VID_%04x,PID_%04x".format(deviceDescriptor.idVendor(), deviceDescriptor.idProduct()))
                        activeAccessoryDeviceList.removeIf() {accessoryDevice->
                            (accessoryDevice.device == device).also { equal->
                                if(equal){
//                                    println("移除配件")
                                    accessoryDevice.close()
                                }
                            }
                        }
                    }
                }

                return 0
            }
        }
        LibUsb.hotplugRegisterCallback(
            context,
            LibUsb.HOTPLUG_EVENT_DEVICE_ARRIVED or LibUsb.HOTPLUG_EVENT_DEVICE_LEFT,
            LibUsb.HOTPLUG_ENUMERATE,
            LibUsb.HOTPLUG_MATCH_ANY,
            LibUsb.HOTPLUG_MATCH_ANY,
            LibUsb.HOTPLUG_MATCH_ANY,
            callback, null, callbackHandle
        ).throwOnFailedCode("无法监听热拔插事件")

        watchEventJob = launch {
            while (this.isActive) {
                if (LibUsb.handleEventsTimeout(context, 1000000) != LibUsb.SUCCESS) {
                    break
                }
            }
        }
    }

    /**
     * 停止观察设备动态
     */
    suspend fun stopWatchEvent() {
        watchEventJob?.cancelAndJoin()
        watchEventJob = null
    }

    /**
     * 切换设备到配件模式<br>
     * 未发生异常则为成功
     */
    private fun switchAndroidDeviceToAccessoryMode(device: Device, deviceDescriptor: DeviceDescriptor) {
//        println("切换设备到配件模式：VID_%04x,PID_%04x".format(deviceDescriptor.idVendor(), deviceDescriptor.idProduct()))
        val deviceHandle = DeviceHandle()
        try {
            LibUsb.open(device, deviceHandle).throwOnFailedCode("在切换设备到配件模式时，打开设备句柄失败")
            val protocolVersionBuffer = ByteBuffer.allocateDirect(2)
            LibUsb.controlTransfer(deviceHandle, LibUsb.ENDPOINT_DIR_MASK or LibUsb.REQUEST_TYPE_VENDOR, 51.toByte(), 0, 0, protocolVersionBuffer, 0)
                .throwOnFailedCode("获取设备支持协议版本失败：VID_%04x,PID_%04x".format(deviceDescriptor.idVendor(), deviceDescriptor.idProduct()))
            val protocolVersion = protocolVersionBuffer.getShort()
            if (protocolVersion == 0.toShort()) {
                throw NoAccessorySupportException(deviceDescriptor.idVendor(), deviceDescriptor.idProduct())
            }
            LibUsb.controlTransfer(deviceHandle, LibUsb.ENDPOINT_OUT or LibUsb.REQUEST_TYPE_VENDOR, 52.toByte(), 0, 0, manufacturerNameBuffer, 0)
                .throwOnFailedCode("发送manufacturerName时发生错误：VID_%04x,PID_%04x".format(deviceDescriptor.idVendor(), deviceDescriptor.idProduct()))
            LibUsb.controlTransfer(deviceHandle, LibUsb.ENDPOINT_OUT or LibUsb.REQUEST_TYPE_VENDOR, 52.toByte(), 0, 1, modelNameBuffer, 0)
                .throwOnFailedCode("发送modelName时发生错误：VID_%04x,PID_%04x".format(deviceDescriptor.idVendor(), deviceDescriptor.idProduct()))
            descriptionBuffer?.let {
                LibUsb.controlTransfer(deviceHandle, LibUsb.ENDPOINT_OUT or LibUsb.REQUEST_TYPE_VENDOR, 52.toByte(), 0, 2, it, 0)
                    .throwOnFailedCode("发送description时发生错误：VID_%04x,PID_%04x".format(deviceDescriptor.idVendor(), deviceDescriptor.idProduct()))
            }
            versionBuffer?.let {
                LibUsb.controlTransfer(deviceHandle, LibUsb.ENDPOINT_OUT or LibUsb.REQUEST_TYPE_VENDOR, 52.toByte(), 0, 3, it, 0)
                    .throwOnFailedCode("发送version时发生错误：VID_%04x,PID_%04x".format(deviceDescriptor.idVendor(), deviceDescriptor.idProduct()))
            }
            uriBuffer?.let {
                LibUsb.controlTransfer(deviceHandle, LibUsb.ENDPOINT_OUT or LibUsb.REQUEST_TYPE_VENDOR, 52.toByte(), 0, 4, it, 0)
                    .throwOnFailedCode("发送URI时发生错误：VID_%04x,PID_%04x".format(deviceDescriptor.idVendor(), deviceDescriptor.idProduct()))
            }
            serialNumberBuffer?.let {
                LibUsb.controlTransfer(deviceHandle, LibUsb.ENDPOINT_OUT or LibUsb.REQUEST_TYPE_VENDOR, 52.toByte(), 0, 5, it, 0)
                    .throwOnFailedCode("发送serialNumber时发生错误：VID_%04x,PID_%04x".format(deviceDescriptor.idVendor(), deviceDescriptor.idProduct()))
            }
            LibUsb.controlTransfer(deviceHandle, LibUsb.ENDPOINT_OUT or LibUsb.REQUEST_TYPE_VENDOR, 53.toByte(), 0, 0, ByteBuffer.allocateDirect(0), 0)
                .throwOnFailedCode("要求设备以配件模式启动时发生错误：VID_%04x,PID_%04x".format(deviceDescriptor.idVendor(), deviceDescriptor.idProduct()))

        } catch (e: LibUsbException) {
            e.printStackTrace()
            return
        } catch (e: NoAccessorySupportException) {
            e.printStackTrace()
            return
        }
//        println("配件模式切换成功：VID_%04x,PID_%04x".format(deviceDescriptor.idVendor(), deviceDescriptor.idProduct()))
    }

    suspend fun join() {
        job.join()
    }

    companion object {
        const val GOOGLE_VENDOR = 0x18d1.toShort()
        const val PRODUCT_ACCESSORY = 0x2d00.toShort()
        const val PRODUCT_ACCESSORY_ABD = 0x2d01.toShort()
    }

}