package kr.co.morymaker.api.storage

import kr.co.morymaker.api.application.port.out.StoreFileCommand
import kr.co.morymaker.api.config.MediaProperties
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.listDirectoryEntries
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * [LocalFileStorageAdapter] 단위 테스트 — `@TempDir`로 실 미디어 루트를 격리한다(이 저장소
 * 최초 `@TempDir` 도입, 02-architect.md §13-7). Spring 컨텍스트를 기동하지 않으므로 트랜잭션
 * 동기화가 비활성 상태다 — 롤백 보상(ADR-009) 자체는 이 테스트의 검증 대상이 아니다(등록을
 * 스킵하는 분기만 통과한다).
 *
 * REQ-0030-01 T-011 항목 ②(검증 우회 거부) ④(path traversal 차단) ⑤(임시파일 정리) 커버.
 */
class LocalFileStorageAdapterTest {

    private fun adapter(root: Path) = LocalFileStorageAdapter(MediaProperties(root = root.toString()))

    private val validPng = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(16)
    private val validMp4 = byteArrayOf(0, 0, 0, 0x18) + "ftyp".toByteArray() + "isom".toByteArray() + ByteArray(4)

    private fun command(
        eventId: String = UUID.randomUUID().toString(),
        contentId: String = UUID.randomUUID().toString(),
        declaredKind: String = "이미지",
        size: Long = validPng.size.toLong(),
        bytes: ByteArray = validPng,
    ) = StoreFileCommand(
        eventId = eventId,
        contentId = contentId,
        originalName = "테스트.png",
        declaredKind = declaredKind,
        size = size,
        source = bytes.inputStream(),
    )

    private fun assertTempDirEmpty(root: Path) {
        val tempDir = root.resolve(".tmp")
        if (!Files.exists(tempDir)) return
        assertEquals(emptyList(), tempDir.listDirectoryEntries(), "임시파일이 정리되지 않고 남아 있다")
    }

    // ── 정상 저장 ────────────────────────────────────────────────

    @Test
    fun `유효한 PNG를 저장하면 storageKey와 contentType을 반환하고 임시파일이 남지 않는다`(@TempDir tempDir: Path) {
        val eventId = UUID.randomUUID().toString()
        val contentId = UUID.randomUUID().toString()

        val stored = adapter(tempDir).store(command(eventId = eventId, contentId = contentId))

        assertEquals("$eventId/$contentId", stored.storageKey)
        assertEquals("image/png", stored.contentType)
        assertTrue(Files.exists(tempDir.resolve(eventId).resolve(contentId)))
        assertTempDirEmpty(tempDir)
    }

    @Test
    fun `유효한 MP4를 저장하면 video mp4로 판별된다`(@TempDir tempDir: Path) {
        val stored = adapter(tempDir).store(command(declaredKind = "영상", bytes = validMp4, size = validMp4.size.toLong()))

        assertEquals("video/mp4", stored.contentType)
        assertTempDirEmpty(tempDir)
    }

    // ── ② 검증 우회 거부 ───────────────────────────────────────────

    @Test
    fun `확장자 위장 - 시그니처가 판별되지 않는 바이트는 저장을 거부한다`(@TempDir tempDir: Path) {
        val garbage = ByteArray(16) { 0x00 }

        assertFailsWith<IllegalArgumentException> {
            adapter(tempDir).store(command(bytes = garbage, size = garbage.size.toLong()))
        }
        assertTempDirEmpty(tempDir)
    }

    @Test
    fun `kind 신고값과 실 콘텐츠 시그니처가 다르면 저장을 거부한다`(@TempDir tempDir: Path) {
        assertFailsWith<IllegalArgumentException> {
            adapter(tempDir).store(command(declaredKind = "영상", bytes = validPng, size = validPng.size.toLong()))
        }
        assertTempDirEmpty(tempDir)
    }

    @Test
    fun `이미지 상한(20MB) 초과 신고 크기는 MediaTooLargeException으로 거부한다`(@TempDir tempDir: Path) {
        assertFailsWith<MediaTooLargeException> {
            adapter(tempDir).store(command(size = 21L * 1024 * 1024))
        }
        assertTempDirEmpty(tempDir)
    }

    @Test
    fun `영상 상한(200MB) 초과 신고 크기는 MediaTooLargeException으로 거부한다`(@TempDir tempDir: Path) {
        assertFailsWith<MediaTooLargeException> {
            adapter(tempDir).store(
                command(declaredKind = "영상", bytes = validMp4, size = 201L * 1024 * 1024),
            )
        }
        assertTempDirEmpty(tempDir)
    }

    // ── ④ path traversal 차단 ──────────────────────────────────────

    @Test
    fun `eventId가 UUID 형식이 아니면 저장을 거부한다(traversal 페이로드)`(@TempDir tempDir: Path) {
        assertFailsWith<IllegalArgumentException> {
            adapter(tempDir).store(command(eventId = "../../etc/passwd"))
        }
        assertTempDirEmpty(tempDir)
    }

    @Test
    fun `contentId가 UUID 형식이 아니면 저장을 거부한다(traversal 페이로드)`(@TempDir tempDir: Path) {
        assertFailsWith<IllegalArgumentException> {
            adapter(tempDir).store(command(contentId = "../../../../etc/passwd"))
        }
        assertTempDirEmpty(tempDir)
    }

    @Test
    fun `resolve는 storageKey가 eventId 접두와 불일치하면 null을 반환한다(cross-event 방어심층)`(@TempDir tempDir: Path) {
        val eventId = UUID.randomUUID().toString()
        val contentId = UUID.randomUUID().toString()
        adapter(tempDir).store(command(eventId = eventId, contentId = contentId))

        val result = adapter(tempDir).resolve("다른-행사-id", "$eventId/$contentId")

        assertNull(result, "타 행사 eventId로 조회하면 파일이 실재해도 null이어야 한다")
    }

    @Test
    fun `resolve는 storageKey가 traversal 페이로드를 포함해도 root 밖으로 벗어나지 않는다`(@TempDir tempDir: Path) {
        val eventId = UUID.randomUUID().toString()

        val result = adapter(tempDir).resolve(eventId, "$eventId/../../../../etc/passwd")

        assertNull(result)
    }

    @Test
    fun `resolve는 파일이 존재하지 않으면 null을 반환한다`(@TempDir tempDir: Path) {
        val eventId = UUID.randomUUID().toString()

        val result = adapter(tempDir).resolve(eventId, "$eventId/${UUID.randomUUID()}")

        assertNull(result)
    }

    // ── ⑤ 임시파일 정리(실패 경로) ───────────────────────────────────

    /** [validPng]와 동일한 바이트를 제공하다가, 그 이후 read 호출부터는 예외를 던지는 스트림. */
    private class FlakyInputStream(private val validBytes: ByteArray) : InputStream() {
        private var served = 0
        override fun read(): Int {
            if (served >= validBytes.size) throw IOException("네트워크 중단(테스트 시뮬레이션)")
            return validBytes[served++].toInt() and 0xFF
        }
    }

    @Test
    fun `스트림 읽기 중 예외가 발생해도 임시파일이 남지 않는다`(@TempDir tempDir: Path) {
        val cmd = StoreFileCommand(
            eventId = UUID.randomUUID().toString(),
            contentId = UUID.randomUUID().toString(),
            originalName = "테스트.png",
            declaredKind = "이미지",
            size = validPng.size.toLong(),
            source = FlakyInputStream(validPng),
        )

        assertFailsWith<IOException> { adapter(tempDir).store(cmd) }
        assertTempDirEmpty(tempDir)
    }

    @Test
    fun `연속된 성공 저장 2건도 임시파일을 남기지 않는다`(@TempDir tempDir: Path) {
        val eventId = UUID.randomUUID().toString()
        adapter(tempDir).store(command(eventId = eventId))
        adapter(tempDir).store(command(eventId = eventId))

        assertTempDirEmpty(tempDir)
    }
}
