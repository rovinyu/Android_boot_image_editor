package cfig.bootimg.v3

import cfig.Avb
import cfig.Helper
import cfig.ParamConfig
import cfig.Parser
import cfig.bootimg.Common.Companion.getPaddingSize
import cfig.bootimg.Common.Companion.unpackRamdisk
import org.apache.commons.exec.CommandLine
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream

@OptIn(ExperimentalUnsignedTypes::class)
data class BootV3(var info: MiscInfo = MiscInfo(output = "boot.img"),
                  var kernel: CommArgs = CommArgs(file = "kernel"),
                  val ramdisk: CommArgs = CommArgs(file = "ramdisk.img.gz")
) {
    companion object {
        private val log = LoggerFactory.getLogger(BootV3::class.java)
        fun parse(fileName: String): BootV3 {
            val ret = BootV3()
            FileInputStream(fileName).use { fis ->
                val header = BootHeaderV3(fis)
                //info
                ret.info.cmdline = header.cmdline
                ret.info.headerSize = header.headerSize
                ret.info.headerVersion = header.headerVersion
                ret.info.osVersion = header.osVersion
                ret.info.osPatchLevel = header.osPatchLevel
                ret.info.pageSize = BootHeaderV3.pageSize
                //kernel
                ret.kernel.size = header.kernelSize.toInt()
                ret.kernel.position = BootHeaderV3.pageSize.toInt()
                //ramdisk
                ret.ramdisk.size = header.ramdiskSize.toInt()
                ret.ramdisk.position = ret.kernel.position + header.kernelSize.toInt() +
                        getPaddingSize(header.kernelSize, BootHeaderV3.pageSize).toInt()
            }
            ret.info.imageSize = File(fileName).length()
            return ret
        }
    }

    data class MiscInfo(
            var output: String = "",
            var headerVersion: UInt = 0U,
            var headerSize: UInt = 0U,
            var pageSize: UInt = 0U,
            var cmdline: String = "",
            var osVersion: String = "",
            var osPatchLevel: String = "",
            var imageSize: Long = 0
    )

    data class CommArgs(
            var file: String = "",
            var position: Int = 0,
            var size: Int = 0)

    fun toHeader(): BootHeaderV3 {
        return BootHeaderV3(
                kernelSize = kernel.size.toUInt(),
                ramdiskSize = ramdisk.size.toUInt(),
                headerVersion = info.headerVersion,
                osVersion = info.osVersion,
                osPatchLevel = info.osPatchLevel,
                headerSize = info.headerSize,
                cmdline = info.cmdline)
    }

    fun extractImages(fileName: String, workDir: String = "build/unzip_boot/"): BootV3 {
        //kernel
        val kernelFile = workDir + kernel.file
        Helper.extractFile(fileName, kernelFile,
                kernel.position.toLong(), kernel.size)
        Parser.parseKernelInfo(kernelFile)

        //ramdisk
        val ramdiskGz = workDir + ramdisk.file
        Helper.extractFile(fileName, ramdiskGz,
                ramdisk.position.toLong(), ramdisk.size)
        Helper.unGnuzipFile(ramdiskGz, ramdisk.file.removeSuffix(".gz"))
        unpackRamdisk(workDir, ramdisk.file.removeSuffix(".gz"))
        return this
    }

    fun extractVBMeta(fileName: String): BootV3 {
        Avb().parseVbMeta(fileName)
        return this
    }

    fun toCommandLine(): CommandLine {
        val workDir = "build/unzip_boot/"
        return CommandLine(ParamConfig().mkbootimg).let { ret ->
            ret.addArgument("--header_version")
            ret.addArgument(info.headerVersion.toString())
            if (kernel.size > 0) {
                ret.addArgument("--kernel")
                ret.addArgument(workDir + this.kernel.file)
            }
            if (ramdisk.size > 0) {
                ret.addArgument("--ramdisk")
                ret.addArgument(workDir + this.ramdisk.file)
            }
            if (info.cmdline.isNotBlank()) {
                ret.addArgument(" --cmdline ")
                ret.addArgument(info.cmdline, false)
            }
            if (info.osVersion.isNotBlank()) {
                ret.addArgument(" --os_version")
                ret.addArgument(info.osVersion)
            }
            if (info.osPatchLevel.isNotBlank()) {
                ret.addArgument(" --os_patch_level")
                ret.addArgument(info.osPatchLevel)
            }
            ret.addArgument(" --id ")
            ret.addArgument(" --output ")
            //ret.addArgument("boot.img" + ".google")

            log.debug("To Commandline: $ret")
            ret
        }
    }
}
