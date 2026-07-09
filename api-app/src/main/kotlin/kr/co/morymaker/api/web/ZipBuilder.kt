package kr.co.morymaker.api.web

import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** ZIP 묶음 생성(§6-4a 일괄 다운로드) — JDK 내장 `java.util.zip`만 사용(신규 의존성 불요). */
internal object ZipBuilder {

    fun zip(entries: List<Pair<String, ByteArray>>): ByteArray {
        val output = ByteArrayOutputStream()
        ZipOutputStream(output).use { zos ->
            entries.forEach { (name, bytes) ->
                zos.putNextEntry(ZipEntry(name))
                zos.write(bytes)
                zos.closeEntry()
            }
        }
        return output.toByteArray()
    }
}
