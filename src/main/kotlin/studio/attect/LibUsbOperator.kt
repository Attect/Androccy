package studio.attect

import org.usb4java.LibUsb
import org.usb4java.LibUsbException
import java.nio.ByteBuffer

open class LibUsbOperator {
    fun Int.throwOnFailedCode(description: String) {
        if (this < LibUsb.SUCCESS) {
            throw LibUsbException(description, this)
        }
    }

    fun String.toDirectByteBuffer(): ByteBuffer {
        val byteArray = toByteArray()
        val buffer = ByteBuffer.allocateDirect(byteArray.size)
        buffer.put(byteArray)
        return buffer
    }
}