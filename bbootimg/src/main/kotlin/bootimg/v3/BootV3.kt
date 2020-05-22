package cfig.bootimg.v3

import cfig.Avb
import cfig.Helper
import cfig.bootimg.v2.ParamConfig
import cfig.bootimg.Common
import cfig.bootimg.Common.Companion.deleleIfExists
import cfig.bootimg.Common.Companion.getPaddingSize
import cfig.bootimg.Common.Companion.parseKernelInfo
import cfig.bootimg.Common.Companion.unpackRamdisk
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.commons.exec.CommandLine
import org.apache.commons.exec.DefaultExecutor
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

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

        fun pack(mkbootfsBin: String = "./aosp/mkbootfs/build/exe/mkbootfs/mkbootfs") {
            val workDir = "build/unzip_boot/"
            val cfgFile = workDir + "bootimg.json"
            log.info("Loading config from $cfgFile")
            val cfg = ObjectMapper().readValue(File(cfgFile), BootV3::class.java)
            if (File(workDir + cfg.ramdisk.file).exists() && !File(workDir + "root").exists()) {
                //do nothing if we have ramdisk.img.gz but no /root
                log.warn("Use prebuilt ramdisk file: ${cfg.ramdisk.file}")
            } else {
                File(workDir + cfg.ramdisk.file).deleleIfExists()
                Common.packRootfs(mkbootfsBin)
            }
            cfg.kernel.size = File(workDir + cfg.kernel.file).length().toInt()
            cfg.ramdisk.size = File(workDir + cfg.ramdisk.file).length().toInt()

            //header
            FileOutputStream(cfg.info.output + ".clear", false).use { fos ->
                val encodedHeader = cfg.toHeader().encode()
                fos.write(encodedHeader)
                fos.write(ByteArray((
                        Helper.round_to_multiple(encodedHeader.size.toUInt(),
                                cfg.info.pageSize) - encodedHeader.size.toUInt()).toInt()
                ))
            }

            //data
            log.info("Writing data ...")
            val bf = ByteBuffer.allocate(1024 * 1024 * 64)//assume total SIZE small than 64MB
            bf.order(ByteOrder.LITTLE_ENDIAN)
            Common.writePaddedFile(bf, workDir + cfg.kernel.file, cfg.info.pageSize)
            Common.writePaddedFile(bf, workDir + cfg.ramdisk.file, cfg.info.pageSize)
            //write
            FileOutputStream("${cfg.info.output}.clear", true).use { fos ->
                fos.write(bf.array(), 0, bf.position())
            }

            //google way
            cfg.toCommandLine().apply {
                addArgument(cfg.info.output + ".google")
                log.info(this.toString())
                DefaultExecutor().execute(this)
            }

            Common.assertFileEquals(cfg.info.output + ".clear", cfg.info.output + ".google")
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
        parseKernelInfo(kernelFile)

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
