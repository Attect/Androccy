package studio.attect

class UnSupportOsException:Exception("不支持的操作系统：${System.getProperty("os.name")}") {
}