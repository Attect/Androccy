package studio.attect

import kotlinx.coroutines.*
import org.usb4java.Device
import org.usb4java.DeviceHandle
import org.usb4java.LibUsb
import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.IntBuffer
import kotlin.coroutines.CoroutineContext

class AccessoryDevice(val device: Device) : LibUsbOperator(), Closeable, CoroutineScope {
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default

    private val deviceHandle = DeviceHandle()

    private var isOpen = false

    private val inBuffer by lazy {
        ByteBuffer.allocateDirect(BUFFER_SIZE)
    }

    private val maxOutBuffer by lazy {
        ByteBuffer.allocateDirect(BUFFER_SIZE)
    }

    private val inTransfered = IntBuffer.allocate(1)
    private val outTransfered = IntBuffer.allocate(1)

    private var readJob:Job? = null


    init {
        LibUsb.open(device, deviceHandle).throwOnFailedCode("无法打开设备")
        LibUsb.claimInterface(deviceHandle, 0).throwOnFailedCode("打开设备接口失败")
        isOpen = true
    }

    fun read(reader: (byteArray: ByteArray, length: Int) -> Unit): Job = launch {
        while (isActive) {
            withContext(Dispatchers.IO) {
                LibUsb.bulkTransfer(deviceHandle, 0x81.toByte(), inBuffer, inTransfered, 0).throwOnFailedCode("读取数据时发生错误")
            }
            inTransfered.position(0)
            inBuffer.position(0)
            val length = inTransfered.get()
            if(length > 0){
                val byteArray = ByteArray(length)
                inBuffer.get(byteArray)
                reader.invoke(byteArray, length)
            }
        }
    }.also { readJob = it }

    suspend fun write(byteArray: ByteArray, offset: Int = 0, length: Int = byteArray.size) {
        var currentOffset = offset
        var remaining = length
        while (remaining > 0) {
            val buffer: ByteBuffer
            if (remaining > BUFFER_SIZE) {
                buffer = maxOutBuffer
                buffer.position(0)
                buffer.put(byteArray, currentOffset, BUFFER_SIZE)
                outTransfered.position(0)
                outTransfered.put(BUFFER_SIZE)
                currentOffset += BUFFER_SIZE
                remaining -= BUFFER_SIZE
            } else {
                buffer = ByteBuffer.allocateDirect(remaining)
                buffer.put(byteArray, currentOffset, remaining)
                outTransfered.position(0)
                outTransfered.put(remaining)
                currentOffset += remaining
                remaining = 0
            }
            withContext(Dispatchers.IO) {
                LibUsb.bulkTransfer(deviceHandle, 0x01.toByte(), buffer, outTransfered, 0).throwOnFailedCode("发送数据时发生错误")
            }
        }
    }


    override fun close() {
        runCatching {
            readJob?.cancel()
        }
        runCatching {
            LibUsb.close(deviceHandle)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AccessoryDevice

        return device == other.device
    }

    override fun hashCode(): Int {
        return device.hashCode()
    }


    companion object {
        const val BUFFER_SIZE = 16384
    }

}