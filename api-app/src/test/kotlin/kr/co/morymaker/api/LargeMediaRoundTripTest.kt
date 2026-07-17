package kr.co.morymaker.api

import kr.co.morymaker.api.application.port.out.FileStoragePort
import kr.co.morymaker.api.application.port.out.StoreFileCommand
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 200MB 근접 실파일 업로드→서빙 왕복 실측. 서빙 시 파일을 통째로 메모리에 올리는 OOM 결함은
 * **소용량 테스트로는 100% GREEN 통과**하므로 이 클래스가 유일한 검출 지점이다 — 다른 테스트는
 * 24바이트 PNG 픽스처만 사용한다.
 *
 * - **저장(업로드측)**: [FileStoragePort]를 실 `FileInputStream`(디스크 소스, 메모리 미보유)으로
 *   직접 호출한다. Boot 멀티파트 스풀이 이미 기본 `file-size-threshold=0B`로 파트를 디스크에
 *   내리므로(00-research.md 발견 2) `MultipartFile.inputStream`도 결국 파일 기반 스트림이라
 *   이 방식이 실 운영 경로의 메모리 특성과 동형이다. `MockMultipartFile`(MockMvc)은 생성자가
 *   내부적으로 전체 바이트를 배열로 적재하므로 200MB 픽스처에는 오히려 부적합하다(테스트
 *   하네스 자체의 버퍼링이 힙 측정을 오염시킨다).
 * - **서빙(다운로드측)**: `@SpringBootTest(webEnvironment=RANDOM_PORT)` 실 임베디드 톰캣에
 *   `HttpURLConnection`으로 직접 접속해 스트리밍 읽기(8KB 버퍼)로 왕복한다 — 무인증
 *   표면(`/public` 하위 전체 무인증)이라 실 JWT 서명 없이도 실 HTTP 종단 검증이 가능하다.
 *
 * ⚠️ **범위 밖으로 남긴 것(의도적 축소 — 은폐 아님)**: 컨테이너 상한(200MB) 초과 시 413
 * 매핑은 실 대용량 인증 멀티파트 POST로 재현하지 못했다 — 등록(POST) endpoint는
 * `HAS_ADMIN_CONSOLE` JWT를 요구하는데 `SecurityConfig.jwtDecoder`가 실 auth 서버
 * JWK(`morymaker.auth.jwk-set-uri`)로 서명을 검증하므로, 이 스위트에 서명 가능한 테스트
 * JWT 인프라가 없다(신규 서명 인프라 구축은 이 REQ 범위를 넘는 별도 설계 결정). 그 예외
 * 매핑 자체(413·`FILE_TOO_LARGE`, 500 아님)는 `GlobalExceptionHandlerTest`가 핸들러
 * 단위로 증명하고, 컨테이너 설정값(`max-file-size: 200MB`)은 application.yml에 정적으로
 * 존재한다(둘 다 실 대용량 트리거 재현보다 약한 증거 — Tester HANDOFF에 명시 disclosure).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class LargeMediaRoundTripTest(
    @Autowired private val fileStoragePort: FileStoragePort,
    @Autowired private val jdbcTemplate: JdbcTemplate,
    @LocalServerPort private val port: Int,
) {

    companion object {
        // JUnit5 @TempDir의 companion object static 필드 인식은 Kotlin @JvmStatic 조합에서
        // 확정적이지 않다(이 저장소 최초 도입이라 선례 검증 불가) — 순수 JDK API로 직접
        // 생성/정리해 프레임워크 통합 불확실성을 원천 회피한다.
        private val mediaRoot: Path = Files.createTempDirectory("large-media-round-trip-")

        // 200MB 상한에 근접(여유 1MB) — Planner 완료 기준 문구 "200MB 근접 실파일"을 그대로 따른다.
        private const val NEAR_LIMIT_BYTES = 199L * 1024 * 1024

        @DynamicPropertySource
        @JvmStatic
        fun overrideMediaRoot(registry: DynamicPropertyRegistry) {
            registry.add("morymaker.media.root") { mediaRoot.toString() }
        }

        @AfterAll
        @JvmStatic
        fun cleanupMediaRoot() {
            mediaRoot.toFile().deleteRecursively()
        }
    }

    private val createdEventIds = mutableListOf<String>()

    @AfterEach
    fun cleanupEvents() {
        // idle_content는 event FK가 ON DELETE CASCADE라 event 삭제만으로 함께 정리된다(V1 §10).
        createdEventIds.forEach { jdbcTemplate.update("DELETE FROM event WHERE id = ?", it) }
        createdEventIds.clear()
    }

    private fun heapUsedMb(): Long {
        val rt = Runtime.getRuntime()
        System.gc()
        Thread.sleep(50)
        return (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024)
    }

    /** 유효 MP4 시그니처(ftyp/isom) + 지정 크기까지 스트리밍 채움 — 전체를 메모리에 올리지 않는다. */
    private fun writeLargeMp4(path: Path, totalBytes: Long): Path {
        val header = byteArrayOf(0, 0, 0, 0x18) + "ftyp".toByteArray() + "isom".toByteArray() + ByteArray(4)
        RandomAccessFile(path.toFile(), "rw").use { raf ->
            raf.write(header)
            val chunk = ByteArray(1024 * 1024)
            var written = header.size.toLong()
            while (written < totalBytes) {
                val toWrite = minOf(chunk.size.toLong(), totalBytes - written).toInt()
                raf.write(chunk, 0, toWrite)
                written += toWrite
            }
        }
        return path
    }

    private fun createEventDirect(): String {
        val eventId = UUID.randomUUID().toString()
        jdbcTemplate.update("INSERT INTO event (id, name) VALUES (?, ?)", eventId, "대용량 실측 행사")
        createdEventIds += eventId
        return eventId
    }

    private data class HttpResult(val status: Int, val bytesRead: Long, val headers: Map<String, String>)

    /** 응답 바디를 8KB 버퍼로 스트리밍 소비한다 — 전체를 배열로 모으지 않아 힙 측정이 오염되지 않는다. */
    private fun streamGet(eid: String, cid: String, range: String?): HttpResult {
        val url = URI("http://localhost:$port/api/public/events/$eid/idle-contents/$cid/file")
        val conn = url.toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "GET"
        range?.let { conn.setRequestProperty("Range", it) }
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.connect()
        val status = conn.responseCode
        val headers = conn.headerFields.entries
            .mapNotNull { (k, v) -> k?.let { it to (v.firstOrNull() ?: "") } }
            .toMap()
        val stream = if (status in 200..299) conn.inputStream else conn.errorStream
        var total = 0L
        stream?.use { s ->
            val buf = ByteArray(8192)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                total += n
            }
        }
        conn.disconnect()
        return HttpResult(status, total, headers)
    }

    @Test
    fun `200MB 근접 실파일 store 중 힙이 파일 크기만큼 증가하지 않는다(스트리밍 확인)`(@TempDir sourceDir: Path) {
        val sourceFile = writeLargeMp4(sourceDir.resolve("source-store.mp4"), NEAR_LIMIT_BYTES)
        val eventId = UUID.randomUUID().toString()
        val contentId = UUID.randomUUID().toString()

        val before = heapUsedMb()
        val stored = Files.newInputStream(sourceFile).use { input ->
            fileStoragePort.store(
                StoreFileCommand(
                    eventId = eventId,
                    contentId = contentId,
                    originalName = "large.mp4",
                    declaredKind = "영상",
                    size = Files.size(sourceFile),
                    source = input,
                ),
            )
        }
        val after = heapUsedMb()

        assertEquals("$eventId/$contentId", stored.storageKey)
        assertEquals("video/mp4", stored.contentType)
        assertEquals(NEAR_LIMIT_BYTES, Files.size(mediaRoot.resolve(eventId).resolve(contentId)))
        val heapDelta = after - before
        assertTrue(heapDelta < 60, "store() 중 힙이 ${heapDelta}MB 증가 — 파일 크기(199MB) 대비 낮아야 스트리밍이 증명된다")
    }

    @Test
    fun `200MB 근접 실파일을 실 HTTP로 서빙하면 힙 폭증 없이 전량 스트리밍되고 Range 왕복도 정상이다`(@TempDir sourceDir: Path) {
        val eventId = createEventDirect()
        val contentId = UUID.randomUUID().toString()
        val sourceFile = writeLargeMp4(sourceDir.resolve("source-serve.mp4"), NEAR_LIMIT_BYTES)
        val stored = Files.newInputStream(sourceFile).use { input ->
            fileStoragePort.store(
                StoreFileCommand(
                    eventId = eventId,
                    contentId = contentId,
                    originalName = "large.mp4",
                    declaredKind = "영상",
                    size = Files.size(sourceFile),
                    source = input,
                ),
            )
        }
        jdbcTemplate.update(
            "INSERT INTO idle_content (id, event_id, name, kind, file_url, file_content_type) VALUES (?, ?, ?, ?, ?, ?)",
            contentId, eventId, "대용량 실측 콘텐츠", "영상", stored.storageKey, stored.contentType,
        )

        // 전체 다운로드 — 실 톰캣 응답을 8KB 버퍼로 스트리밍 소비하며 힙 증가를 측정한다.
        val before = heapUsedMb()
        val full = streamGet(eventId, contentId, range = null)
        val after = heapUsedMb()

        assertEquals(200, full.status)
        assertEquals(NEAR_LIMIT_BYTES, full.bytesRead, "전송된 바이트 수가 저장 파일 크기와 달라야 할 이유가 없다")
        assertEquals("bytes", full.headers["Accept-Ranges"])
        val heapDelta = after - before
        assertTrue(heapDelta < 60, "서빙 중 힙이 ${heapDelta}MB 증가 — 전체 로드 의심(파일 크기 199MB)")

        // Range 왕복(선두 1MB) — 206 + Content-Range + 부분 바이트 수만 전송.
        val partial = streamGet(eventId, contentId, range = "bytes=0-1048575")
        assertEquals(206, partial.status)
        assertEquals(1_048_576L, partial.bytesRead)
        assertEquals("bytes 0-1048575/$NEAR_LIMIT_BYTES", partial.headers["Content-Range"])

        // 파일 크기를 벗어난 Range — 416.
        val outOfRange = streamGet(eventId, contentId, range = "bytes=${NEAR_LIMIT_BYTES + 1000}-${NEAR_LIMIT_BYTES + 2000}")
        assertEquals(416, outOfRange.status)
    }
}
