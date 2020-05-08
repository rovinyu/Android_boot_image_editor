package cfig

import cfig.bootimg.Common.Companion.assertFileEquals
import cfig.bootimg.Common.Companion.deleleIfExists
import cfig.bootimg.Common.Companion.packRootfs
import cfig.bootimg.Common.Companion.writePaddedFile
import cfig.bootimg.v3.BootV3
import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.commons.exec.DefaultExecutor
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalUnsignedTypes::class)
class Packer {
    private val log = LoggerFactory.getLogger("Packer")

    fun packV3(mkbootfsBin: String) {
        val workDir = "build/unzip_boot/"
        val cfgFile = workDir + "bootimg.json"
        log.info("Loading config from $cfgFile")
        val cfg = ObjectMapper().readValue(File(cfgFile), BootV3::class.java)
        if (File(workDir + cfg.ramdisk.file).exists() && !File(workDir + "root").exists()) {
            //do nothing if we have ramdisk.img.gz but no /root
            log.warn("Use prebuilt ramdisk file: ${cfg.ramdisk.file}")
        } else {
            File(workDir + cfg.ramdisk.file).deleleIfExists()
            packRootfs(mkbootfsBin)
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
        writePaddedFile(bf, workDir + cfg.kernel.file, cfg.info.pageSize)
        writePaddedFile(bf, workDir + cfg.ramdisk.file, cfg.info.pageSize)
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

        assertFileEquals(cfg.info.output + ".clear", cfg.info.output + ".google")
    }

    fun packV2(mkbootfsBin: String) {
        val param = ParamConfig()
        log.info("Loading config from ${param.cfg}")
        val cfg = ObjectMapper().readValue(File(param.cfg), UnifiedConfig::class.java)
        val info2 = cfg.toBootImgInfo()

        //clean
        File(cfg.info.output + ".google").deleleIfExists()
        File(cfg.info.output + ".clear").deleleIfExists()
        File(cfg.info.output + ".signed").deleleIfExists()
        File(cfg.info.output + ".signed2").deleleIfExists()
        File("${UnifiedConfig.workDir}ramdisk.img").deleleIfExists()

        if (info2.ramdiskLength > 0U) {
            if (File(param.ramdisk!!).exists() && !File(UnifiedConfig.workDir + "root").exists()) {
                //do nothing if we have ramdisk.img.gz but no /root
                log.warn("Use prebuilt ramdisk file: ${param.ramdisk}")
            } else {
                File(param.ramdisk!!).deleleIfExists()
                packRootfs(mkbootfsBin)
            }
        }

        val encodedHeader = info2.encode()
        //write
        FileOutputStream(cfg.info.output + ".clear", false).use { fos ->
            fos.write(encodedHeader)
            fos.write(ByteArray((Helper.round_to_multiple(encodedHeader.size.toUInt(), info2.pageSize) - encodedHeader.size.toUInt()).toInt()))
        }

        log.info("Writing data ...")
        val bytesV2 = ByteBuffer.allocate(1024 * 1024 * 64)//assume total SIZE small than 64MB
                .let { bf ->
                    bf.order(ByteOrder.LITTLE_ENDIAN)
                    writePaddedFile(bf, param.kernel, info2.pageSize)
                    if (info2.ramdiskLength > 0U) {
                        writePaddedFile(bf, param.ramdisk!!, info2.pageSize)
                    }
                    if (info2.secondBootloaderLength > 0U) {
                        writePaddedFile(bf, param.second!!, info2.pageSize)
                    }
                    if (info2.recoveryDtboLength > 0U) {
                        writePaddedFile(bf, param.dtbo!!, info2.pageSize)
                    }
                    if (info2.dtbLength > 0U) {
                        writePaddedFile(bf, param.dtb!!, info2.pageSize)
                    }
                    bf
                }
        //write
        FileOutputStream("${cfg.info.output}.clear", true).use { fos ->
            fos.write(bytesV2.array(), 0, bytesV2.position())
        }

        info2.toCommandLine().apply {
            addArgument(cfg.info.output + ".google")
            log.info(this.toString())
            DefaultExecutor().execute(this)
        }

        assertFileEquals(cfg.info.output + ".clear", cfg.info.output + ".google")
    }
}
