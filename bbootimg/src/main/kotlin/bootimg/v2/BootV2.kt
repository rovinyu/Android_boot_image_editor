package cfig.bootimg.v2

import cfig.*
import cfig.bootimg.Common
import cfig.bootimg.Common.Companion.deleleIfExists
import cfig.bootimg.Common.Companion.parseKernelInfo
import cfig.dtb_util.DTC
import cfig.packable.VBMetaParser
import com.fasterxml.jackson.databind.ObjectMapper
import de.vandermeer.asciitable.AsciiTable
import org.apache.commons.exec.DefaultExecutor
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

@OptIn(ExperimentalUnsignedTypes::class)
class BootV2 {
    companion object {
        private val log = LoggerFactory.getLogger(BootV2::class.java)

        private fun unpackVBMeta(): Boolean {
            return if (File("vbmeta.img").exists()) {
                log.warn("Found vbmeta.img, parsing ...")
                VBMetaParser().unpack("vbmeta.img")
                true
            } else {
                false
            }
        }

        fun extractBootImg(fileName: String, info2: BootImgInfo) {
            val param = ParamConfig()

            InfoTable.instance.addRule()
            if (info2.kernelLength > 0U) {
                Helper.extractFile(fileName,
                        param.kernel,
                        info2.kernelPosition.toLong(),
                        info2.kernelLength.toInt())
                log.info(" kernel  dumped  to: ${param.kernel}, size=${info2.kernelLength.toInt() / 1024.0 / 1024.0}MB")
                InfoTable.instance.addRow("kernel", param.kernel)
                parseKernelInfo(param.kernel)
            } else {
                throw RuntimeException("bad boot image: no kernel found")
            }

            if (info2.ramdiskLength > 0U) {
                Helper.extractFile(fileName,
                        param.ramdisk!!,
                        info2.ramdiskPosition.toLong(),
                        info2.ramdiskLength.toInt())
                log.info("ramdisk  dumped  to: ${param.ramdisk}")
                Helper.unGnuzipFile(param.ramdisk!!, param.ramdisk!!.removeSuffix(".gz"))
                Common.unpackRamdisk(UnifiedConfig.workDir, param.ramdisk!!.removeSuffix(".gz"))
                InfoTable.instance.addRule()
                InfoTable.instance.addRow("ramdisk", param.ramdisk!!.removeSuffix(".gz"))
                InfoTable.instance.addRow("\\-- extracted ramdisk rootfs", "${UnifiedConfig.workDir}root")
            } else {
                InfoTable.missingParts.add("ramdisk")
                log.info("no ramdisk found")
            }

            if (info2.secondBootloaderLength > 0U) {
                Helper.extractFile(fileName,
                        param.second!!,
                        info2.secondBootloaderPosition.toLong(),
                        info2.secondBootloaderLength.toInt())
                log.info("second bootloader dumped to ${param.second}")
                InfoTable.instance.addRule()
                InfoTable.instance.addRow("second bootloader", param.second)
            } else {
                InfoTable.missingParts.add("second bootloader")
                log.info("no second bootloader found")
            }

            if (info2.recoveryDtboLength > 0U) {
                Helper.extractFile(fileName,
                        param.dtbo!!,
                        info2.recoveryDtboPosition.toLong(),
                        info2.recoveryDtboLength.toInt())
                log.info("recovery dtbo dumped to ${param.dtbo}")
                InfoTable.instance.addRule()
                InfoTable.instance.addRow("recovery dtbo", param.dtbo)
            } else {
                InfoTable.missingParts.add("recovery dtbo")
                if (info2.headerVersion > 0U) {
                    log.info("no recovery dtbo found")
                } else {
                    log.debug("no recovery dtbo for header v0")
                }
            }

            if (info2.dtbLength > 0U) {
                Helper.extractFile(fileName,
                        param.dtb!!,
                        info2.dtbPosition.toLong(),
                        info2.dtbLength.toInt())
                log.info("dtb dumped to ${param.dtb}")
                InfoTable.instance.addRule()
                InfoTable.instance.addRow("dtb", param.dtb)
                //extract DTB
                if (EnvironmentVerifier().hasDtc) {
                    if (DTC().decompile(param.dtb!!, param.dtb + ".src")) {
                        InfoTable.instance.addRow("\\-- decompiled dts", param.dtb + ".src")
                    }
                }
                //extract DTB
            } else {
                InfoTable.missingParts.add("dtb")
                if (info2.headerVersion > 1U) {
                    log.info("no dtb found")
                } else {
                    log.debug("no dtb for header v0")
                }
            }
        }

        fun parserV2(fileName: String) {
            val info = parseBootImgHeader(fileName, avbtool = "aosp/avb/avbtool")
            InfoTable.instance.addRule()
            InfoTable.instance.addRow("image info", ParamConfig().cfg)
            if (info.signatureType == BootImgInfo.VerifyType.AVB) {
                log.info("continue to analyze vbmeta info in $fileName")
                Avb().parseVbMeta(fileName)
                InfoTable.instance.addRule()
                InfoTable.instance.addRow("AVB info", Avb.getJsonFileName(fileName))
            }
            extractBootImg(fileName, info2 = info)
            val unpackedVbmeta = unpackVBMeta()

            InfoTable.instance.addRule()
            val tableHeader = AsciiTable().apply {
                addRule()
                addRow("What", "Where")
                addRule()
            }
            log.info("\n\t\t\tUnpack Summary of $fileName\n{}\n{}", tableHeader.render(), InfoTable.instance.render())
            if (unpackedVbmeta) {
                val tableFooter = AsciiTable().apply {
                    addRule()
                    addRow("vbmeta.img", Avb.getJsonFileName("vbmeta.img"))
                    addRule()
                }
                LoggerFactory.getLogger("vbmeta").info("\n" + tableFooter.render())
            }
            log.info("Following components are not present: ${InfoTable.missingParts}")
        }

        fun parseBootImgHeader(fileName: String, avbtool: String): BootImgInfo {
            val info2 = BootImgInfo(FileInputStream(fileName))
            val param = ParamConfig()
            if (Avb.hasAvbFooter(fileName)) {
                info2.signatureType = BootImgInfo.VerifyType.AVB
                Avb.verifyAVBIntegrity(fileName, avbtool)
            } else {
                info2.signatureType = BootImgInfo.VerifyType.VERIFY
            }
            info2.imageSize = File(fileName).length()

            val cfg = UnifiedConfig.fromBootImgInfo(info2).apply {
                info.output = File(fileName).name
            }

            ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(File(param.cfg), cfg)
            log.info("image info written to ${param.cfg}")

            return info2
        }

        fun pack(mkbootfsBin: String = "./aosp/mkbootfs/build/exe/mkbootfs/mkbootfs") {
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
                    Common.packRootfs(mkbootfsBin)
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
                        Common.writePaddedFile(bf, param.kernel, info2.pageSize)
                        if (info2.ramdiskLength > 0U) {
                            Common.writePaddedFile(bf, param.ramdisk!!, info2.pageSize)
                        }
                        if (info2.secondBootloaderLength > 0U) {
                            Common.writePaddedFile(bf, param.second!!, info2.pageSize)
                        }
                        if (info2.recoveryDtboLength > 0U) {
                            Common.writePaddedFile(bf, param.dtbo!!, info2.pageSize)
                        }
                        if (info2.dtbLength > 0U) {
                            Common.writePaddedFile(bf, param.dtb!!, info2.pageSize)
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

            Common.assertFileEquals(cfg.info.output + ".clear", cfg.info.output + ".google")
        }
    }
}