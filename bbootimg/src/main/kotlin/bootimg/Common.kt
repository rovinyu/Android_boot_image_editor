package cfig.bootimg

import cfig.Helper
import cfig.io.Struct3.InputStreamExt.Companion.getInt
import org.apache.commons.exec.CommandLine
import org.apache.commons.exec.DefaultExecutor
import org.apache.commons.exec.PumpStreamHandler
import org.slf4j.LoggerFactory
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

@OptIn(ExperimentalUnsignedTypes::class)
class Common {
    companion object {
        private val log = LoggerFactory.getLogger(Common::class.java)

        fun getPaddingSize(position: UInt, pageSize: UInt): UInt {
            return (pageSize - (position and pageSize - 1U)) and (pageSize - 1U)
        }

        fun getPaddingSize(position: Int, pageSize: Int): Int {
            return (pageSize - (position and pageSize - 1)) and (pageSize - 1)
        }

        @Throws(CloneNotSupportedException::class)
        fun hashFileAndSize(vararg inFiles: String?): ByteArray {
            val md = MessageDigest.getInstance("SHA1")
            for (item in inFiles) {
                if (null == item) {
                    md.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                            .putInt(0)
                            .array())
                    log.debug("update null $item: " + Helper.toHexString((md.clone() as MessageDigest).digest()))
                } else {
                    val currentFile = File(item)
                    FileInputStream(currentFile).use { iS ->
                        var byteRead: Int
                        val dataRead = ByteArray(1024)
                        while (true) {
                            byteRead = iS.read(dataRead)
                            if (-1 == byteRead) {
                                break
                            }
                            md.update(dataRead, 0, byteRead)
                        }
                        log.debug("update file $item: " + Helper.toHexString((md.clone() as MessageDigest).digest()))
                        md.update(ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN)
                                .putInt(currentFile.length().toInt())
                                .array())
                        log.debug("update SIZE $item: " + Helper.toHexString((md.clone() as MessageDigest).digest()))
                    }
                }
            }

            return md.digest()
        }

        fun assertFileEquals(file1: String, file2: String) {
            val hash1 = hashFileAndSize(file1)
            val hash2 = hashFileAndSize(file2)
            log.info("$file1 hash ${Helper.toHexString(hash1)}, $file2 hash ${Helper.toHexString(hash2)}")
            if (hash1.contentEquals(hash2)) {
                log.info("Hash verification passed: ${Helper.toHexString(hash1)}")
            } else {
                log.error("Hash verification failed")
                throw UnknownError("Do not know why hash verification fails, maybe a bug")
            }
        }

        fun packRootfs(mkbootfs: String,
                       rootDir: String = "build/unzip_boot/root",
                       ramdisk: String = "build/unzip_boot/ramdisk.img.gz") {
            log.info("Packing rootfs $rootDir ...")
            val outputStream = ByteArrayOutputStream()
            DefaultExecutor().let { exec ->
                exec.streamHandler = PumpStreamHandler(outputStream)
                val cmdline = "$mkbootfs $rootDir"
                log.info(cmdline)
                exec.execute(CommandLine.parse(cmdline))
            }
            Helper.gnuZipFile2(ramdisk, ByteArrayInputStream(outputStream.toByteArray()))
            log.info("$ramdisk is ready")
        }

        fun padFile(inBF: ByteBuffer, padding: Int) {
            val pad = padding - (inBF.position() and padding - 1) and padding - 1
            inBF.put(ByteArray(pad))
        }

        fun File.deleleIfExists() {
            if (this.exists()) {
                if (!this.isFile) {
                    throw IllegalStateException("${this.canonicalPath} should be regular file")
                }
                log.info("Deleting ${this.path} ...")
                this.delete()
            }
        }

        fun writePaddedFile(inBF: ByteBuffer, srcFile: String, padding: UInt) {
            assert(padding < Int.MAX_VALUE.toUInt())
            writePaddedFile(inBF, srcFile, padding.toInt())
        }

        fun writePaddedFile(inBF: ByteBuffer, srcFile: String, padding: Int) {
            FileInputStream(srcFile).use { iS ->
                var byteRead: Int
                val dataRead = ByteArray(128)
                while (true) {
                    byteRead = iS.read(dataRead)
                    if (-1 == byteRead) {
                        break
                    }
                    inBF.put(dataRead, 0, byteRead)
                }
                padFile(inBF, padding)
            }
        }

        fun unpackRamdisk(workDir: String, ramdiskGz: String) {
            val exe = DefaultExecutor()
            exe.workingDirectory = File(workDir + "root")
            if (exe.workingDirectory.exists()) exe.workingDirectory.deleteRecursively()
            exe.workingDirectory.mkdirs()
            val ramdiskFile = File(ramdiskGz.removeSuffix(".gz"))
            exe.execute(CommandLine.parse("cpio -i -m -F " + ramdiskFile.canonicalPath))
            log.info(" ramdisk extracted : $ramdiskFile -> ${exe.workingDirectory.path}")
        }

        fun probeHeaderVersion(fileName: String): Int {
            return FileInputStream(fileName).let { fis ->
                fis.skip(40)
                fis.getInt(ByteOrder.LITTLE_ENDIAN)
            }
        }
    }
}
