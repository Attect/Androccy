import kotlinx.coroutines.*
import studio.attect.AndroccyServer
import studio.attect.UsbDeviceId
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test

class MainKtTest:CoroutineScope{

    @Test
    fun test(){
        runBlocking {
            val server= AndroccyServer("AttectStudio","Demo","AccessoryDemo","1.0","https://attect.studio")
            initAndroidUsbDeviceId(server)
            server.startWatchEvent()
//    server.refreshDevice()
            launch {
                while (isActive){
                    println("wait device for read")
                    val accessoryDevice = server.accessoryDeviceChannel.receive()
                    println("Accessory IO:${accessoryDevice.device.pointer}")
                    launch {
                        var i = 0
                        val lengthBuffer = ByteBuffer.allocate(2)
                        while (isActive){
                            delay(100L)
                            val content = "server->device:$i"
                            i++
                            lengthBuffer.position(0)
                            lengthBuffer.putShort(content.length.toShort())
                            val byteBuffer = ByteBuffer.allocate(2+content.length)
                            byteBuffer.put(lengthBuffer.array())
                            byteBuffer.put(content.encodeToByteArray())
                            println("[${content.length}]$content")
                            accessoryDevice.write(byteBuffer.array())
                        }
                    }
                    launch {
                        val byteArrayOutputStream = ByteArrayOutputStream()
                        var textLength = -1
                        accessoryDevice.read { byteArray, readCount ->
//                        println("device->server:${String(byteArray,2,readCount-2)}")
                            if(textLength < 0){
                                if(readCount < 2){
                                    println("数据不足，无法读取足够的长度，请检查发送端逻辑和数据")
                                    return@read
                                }
                                val byteBuffer = ByteBuffer.allocate(2)
                                byteBuffer.put(byteArray,0,2)
                                byteBuffer.position(0)
                                textLength = byteBuffer.short.toInt()
                                byteArrayOutputStream.write(byteArray,2,readCount-2)
                            }else{
                                byteArrayOutputStream.write(byteArray,0,readCount)
                            }
                            if(byteArrayOutputStream.size() == textLength){
                                println("[$textLength]device->server:$byteArrayOutputStream")
                                byteArrayOutputStream.reset()
                                textLength = -1
                            }
                        }.join()
                    }
                }
            }
            server.join()
        }
    }

    private suspend fun initAndroidUsbDeviceId(server: AndroccyServer){
        server.addUsbDeviceId(UsbDeviceId((0x2717).toShort(),(0xff48).toShort()))
        server.addUsbDeviceId(UsbDeviceId((0x2a70).toShort(),(0x4ee7).toShort())) // OnePlus Technology (Shenzhen) Co., Ltd. ONEPLUS A3010 [OnePlus 3T] / A5010 [OnePlus 5T] / A6003 [OnePlus 6] (Charging + USB debugging modes)
        server.addUsbDeviceId(UsbDeviceId((0x18d1).toShort(),(0x4ee7).toShort())) // Google Inc. Nexus/Pixel Device (charging + debug)
    }

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default
}