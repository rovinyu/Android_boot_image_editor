package cfig.packable

import avb.AVBInfo
import avb.blob.Footer
import cfig.*
import cfig.bootimg.BootImgInfo
import cfig.bootimg.Common.Companion.probeHeaderVersion
import cfig.bootimg.v3.BootV3
import com.fasterxml.jackson.databind.ObjectMapper
import de.vandermeer.asciitable.AsciiTable
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream

@OptIn(ExperimentalUnsignedTypes::class)
class BootImgParser() : IPackable {
    override val loopNo: Int
        get() = 0
    private val log = LoggerFactory.getLogger(BootImgParser::class.java)

    override fun capabilities(): List<String> {
        return listOf("^boot\\.img$", "^recovery\\.img$", "^recovery-two-step\\.img$")
    }

    private fun unpackVBMeta(): Boolean {
        return if (File("vbmeta.img").exists()) {
            log.warn("Found vbmeta.img, parsing ...")
            VBMetaParser().unpack("vbmeta.img")
            true
        } else {
            false
        }
    }

    private fun parserV2(fileName: String) {
        val info = Parser().parseBootImgHeader(fileName, avbtool = "aosp/avb/avbtool")
        InfoTable.instance.addRule()
        InfoTable.instance.addRow("image info", ParamConfig().cfg)
        if (info.signatureType == BootImgInfo.VerifyType.AVB) {
            log.info("continue to analyze vbmeta info in $fileName")
            Avb().parseVbMeta(fileName)
            InfoTable.instance.addRule()
            InfoTable.instance.addRow("AVB info", Avb.getJsonFileName(fileName))
        }
        Parser().extractBootImg(fileName, info2 = info)
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

    override fun unpack(fileName: String) {
        cleanUp()
        try {
            val hv = probeHeaderVersion(fileName)
            log.warn("hv " + hv)
            if (hv == 3) {
                val bv3 = BootV3
                        .parse(fileName)
                        .extractImages(fileName)
                        .extractVBMeta(fileName)
                log.info(bv3.toString())
                ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(File("build/unzip_boot/bootimg.json"), bv3)
                return
            } else {
                parserV2(fileName)
            }
        } catch (e: IllegalArgumentException) {
            log.error(e.message)
            log.error("Parser can not continue")
        }
    }

    override fun pack(fileName: String) {
        if (3 == probeHeaderVersion(fileName)) {
            val bootV3 = ObjectMapper().readValue(File("build/unzip_boot/bootimg.json"), BootV3::class.java)
            Packer().packV3(mkbootfsBin = "./aosp/mkbootfs/build/exe/mkbootfs/mkbootfs")
            Signer.signAVB(fileName, avbtool = "aosp/avb/avbtool", imageSize = bootV3.info.imageSize)
            return
        }

        Packer().packV2(mkbootfsBin = "./aosp/mkbootfs/build/exe/mkbootfs/mkbootfs")
        Signer.sign(avbtool = "aosp/avb/avbtool", bootSigner = "aosp/boot_signer/build/libs/boot_signer.jar")
        if (File("vbmeta.img").exists()) {
            val partitionName = ObjectMapper().readValue(File(Avb.getJsonFileName(fileName)), AVBInfo::class.java).let {
                it.auxBlob!!.hashDescriptors.get(0).partition_name
            }
            val newHashDesc = Avb().parseVbMeta("$fileName.signed", dumpFile = false)
            assert(newHashDesc.auxBlob!!.hashDescriptors.size == 1)
            val mainVBMeta = ObjectMapper().readValue(File(Avb.getJsonFileName("vbmeta.img")), AVBInfo::class.java).apply {
                val itr = this.auxBlob!!.hashDescriptors.iterator()
                var seq = 0
                while (itr.hasNext()) {
                    val itrValue = itr.next()
                    if (itrValue.partition_name == partitionName) {
                        seq = itrValue.sequence
                        itr.remove()
                        break
                    }
                }
                val hd = newHashDesc.auxBlob!!.hashDescriptors.get(0).apply { this.sequence = seq }
                this.auxBlob!!.hashDescriptors.add(hd)
            }
            Avb().packVbMetaWithPadding("vbmeta.img", mainVBMeta)
        }
    }

    override fun flash(fileName: String, deviceName: String) {
        val stem = fileName.substring(0, fileName.indexOf("."))
        super.flash("$fileName.signed", stem)
    }

    // invoked solely by reflection
    fun `@footer`(image_file: String) {
        FileInputStream(image_file).use { fis ->
            fis.skip(File(image_file).length() - Footer.SIZE)
            try {
                val footer = Footer(fis)
                log.info("\n" + ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(footer))
            } catch (e: IllegalArgumentException) {
                log.info("image $image_file has no AVB Footer")
            }
        }
    }
}
