package studio.attect

class NoAccessorySupportException(vendorId:Short, productId:Short):Exception("设备VID_%04x,PID_%04x不支持配件模式".format(vendorId,productId)) {
}