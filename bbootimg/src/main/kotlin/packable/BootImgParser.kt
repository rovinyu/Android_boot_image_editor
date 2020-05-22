package cfig.packable

import avb.AVBInfo
import avb.blob.Footer
import cfig.Avb
import cfig.bootimg.v2.ParamConfig
import cfig.bootimg.Signer
import cfig.bootimg.v2.UnifiedConfig
import cfig.bootimg.v2.BootImgInfo
import cfig.bootimg.Common.Companion.probeHeaderVersion
import cfig.bootimg.v2.BootV2
import cfig.bootimg.v3.BootV3
import com.fasterxml.jackson.databind.ObjectMapper
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
                BootV2.parserV2(fileName)
            }
        } catch (e: IllegalArgumentException) {
            log.error(e.message)
            log.error("Parser can not continue")
        }
    }

    override fun pack(fileName: String) {
        if (3 == probeHeaderVersion(fileName)) {
            val bootV3 = ObjectMapper().readValue(File("build/unzip_boot/bootimg.json"), BootV3::class.java)
            BootV3.pack()
            Signer.signAVB(fileName, bootV3.info.imageSize)
            return
        }

        BootV2.pack()
        val info2 = UnifiedConfig.readBack2()
        val cfg = ObjectMapper().readValue(File(ParamConfig().cfg), UnifiedConfig::class.java)
        when (info2.signatureType) {
            BootImgInfo.VerifyType.VERIFY -> {
                Signer.signVB1(cfg.info.output + ".clear", cfg.info.output + ".signed")
            }
            BootImgInfo.VerifyType.AVB -> {
                log.info("Adding hash_footer with verified-boot 2.0 style")
                Signer.signAVB(cfg.info.output, info2.imageSize)
                updateVbmeta(fileName)
            }
        }
    }

    private fun updateVbmeta(fileName: String) {
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
