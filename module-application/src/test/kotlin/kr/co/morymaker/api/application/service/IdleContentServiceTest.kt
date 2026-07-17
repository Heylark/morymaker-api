package kr.co.morymaker.api.application.service

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.co.morymaker.api.application.port.`in`.IdleContentCreateCommand
import kr.co.morymaker.api.application.port.`in`.IdleContentUpdateCommand
import kr.co.morymaker.api.application.port.out.FileStoragePort
import kr.co.morymaker.api.application.port.out.IdleContentPort
import kr.co.morymaker.api.application.port.out.StoredFile
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.idle.IdleContent
import org.junit.jupiter.api.Test
import java.nio.file.Path
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * [IdleContentService] 단위 테스트 — [IdleContentPort]/[FileStoragePort]/[EventScopeGuard]를
 * mock으로 대체해 CRUD 오케스트레이션 + 키오스크 공개 조회(listForKiosk·fetchMediaForKiosk)의
 * assertAccess 미호출을 검증한다. cross-tenant·공개 라우트 무인증·대용량 실측 종합 TC는
 * Tester 담당(T-012), 저장·서빙 어댑터 자체 검증은 [kr.co.morymaker.api.storage.LocalFileStorageAdapterTest]
 * 담당(어댑터는 별도 유닛).
 */
class IdleContentServiceTest {

    private val idleContentPort = mockk<IdleContentPort>()
    private val fileStoragePort = mockk<FileStoragePort>()
    private val eventScopeGuard = mockk<EventScopeGuard>()
    private val service = IdleContentService(idleContentPort, fileStoragePort, eventScopeGuard)

    private fun sampleContent(
        id: String = "c1",
        mode: String? = "branded",
        play: String? = "8초 롤링",
        sortOrder: Int = 1,
        fileUrl: String? = null,
        fileContentType: String? = null,
    ) = IdleContent(
        id = id, eventId = "ev1", name = "키비주얼_A.png", kind = "이미지",
        mode = mode, play = play, fileUrl = fileUrl, fileContentType = fileContentType, sortOrder = sortOrder,
    )

    // ── list(§11-2 관리자) ─────────────────────────────────────────

    @Test
    fun `list는 assertAccess를 먼저 호출한 뒤 sortOrder 순 목록을 반환한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { idleContentPort.findByEvent("ev1") } returns listOf(sampleContent(id = "c1", sortOrder = 1), sampleContent(id = "c2", sortOrder = 2))

        val result = service.list("ev1")

        assertEquals(listOf("c1", "c2"), result.map { it.id })
        verify(exactly = 1) { eventScopeGuard.assertAccess("ev1") }
    }

    // ── create(§11-3, M3 — file 파트 필수) ──────────────────────────

    @Test
    fun `create는 FileStoragePort store 결과를 fileUrl·fileContentType으로 저장한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { fileStoragePort.store(any()) } returns StoredFile(storageKey = "ev1/generated-key", contentType = "video/mp4")
        val inserted = slot<IdleContent>()
        every { idleContentPort.insert(capture(inserted)) } returns Unit

        val command = IdleContentCreateCommand(
            name = "홍보영상.mp4", kind = "영상", mode = "fullbleed", play = "자동재생", sortOrder = 0,
            source = "dummy".byteInputStream(), size = 12345L,
        )
        val result = service.create("ev1", command)

        assertEquals("ev1/generated-key", result.fileUrl)
        assertEquals("video/mp4", result.fileContentType)
        assertEquals("홍보영상.mp4", inserted.captured.name)
        assertEquals("ev1", inserted.captured.eventId)
        assertEquals("ev1/generated-key", inserted.captured.fileUrl)
        verify(exactly = 1) { idleContentPort.insert(any()) }
    }

    // ── update(§11-4) ──────────────────────────────────────────────

    @Test
    fun `update는 mode·play·sortOrder만 병합하고 name·kind·fileUrl은 불변으로 유지한다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { idleContentPort.fetchById("ev1", "c1") } returns
            sampleContent(id = "c1", mode = "branded", play = "8초 롤링", sortOrder = 1, fileUrl = "ev1/c1", fileContentType = "image/png")
        val updated = slot<IdleContent>()
        every { idleContentPort.update(capture(updated)) } returns Unit

        val command = IdleContentUpdateCommand(mode = "fullbleed", play = "10초 롤링", sortOrder = 3)
        val result = service.update("ev1", "c1", command)

        assertEquals("fullbleed", result.mode)
        assertEquals("10초 롤링", result.play)
        assertEquals(3, result.sortOrder)
        assertEquals("키비주얼_A.png", result.name)
        assertEquals("이미지", result.kind)
        assertEquals("ev1/c1", result.fileUrl, "update가 fileUrl을 건드리면 안 된다")
        assertEquals("fullbleed", updated.captured.mode)
        assertEquals("ev1/c1", updated.captured.fileUrl, "update가 fileUrl을 건드리면 안 된다")
    }

    @Test
    fun `update는 콘텐츠가 없으면 NoSuchElementException을 던진다`() {
        every { eventScopeGuard.assertAccess("ev1") } returns Unit
        every { idleContentPort.fetchById("ev1", "ghost") } returns null

        val command = IdleContentUpdateCommand(mode = null, play = null, sortOrder = 0)
        assertFailsWith<NoSuchElementException> { service.update("ev1", "ghost", command) }
    }

    // ── listForKiosk(§11-2, M3 — 무인증) ────────────────────────────

    @Test
    fun `listForKiosk는 assertAccess를 호출하지 않고 event_id 필터 결과를 그대로 반환한다`() {
        every { idleContentPort.findByEvent("ev1") } returns listOf(sampleContent(id = "c1"))

        val result = service.listForKiosk("ev1")

        assertEquals(listOf("c1"), result.map { it.id })
        verify(exactly = 0) { eventScopeGuard.assertAccess(any()) }
    }

    @Test
    fun `listForKiosk는 존재하지 않는 eventId도 예외 없이 빈 배열을 반환한다(fail-open)`() {
        every { idleContentPort.findByEvent("ghost") } returns emptyList()

        val result = service.listForKiosk("ghost")

        assertEquals(emptyList(), result)
        verify(exactly = 0) { eventScopeGuard.assertAccess(any()) }
    }

    // ── fetchMediaForKiosk(§11-2, M3 신설 — 무인증 서빙) ──────────────

    @Test
    fun `fetchMediaForKiosk는 assertAccess를 호출하지 않고 파일 보유 행이면 IdleContentMedia를 반환한다`() {
        val path = Path.of("/tmp/dummy-media-path")
        every { idleContentPort.fetchById("ev1", "c1") } returns
            sampleContent(id = "c1", fileUrl = "ev1/c1", fileContentType = "image/png")
        every { fileStoragePort.resolve("ev1", "ev1/c1") } returns path

        val result = service.fetchMediaForKiosk("ev1", "c1")

        assertEquals(path, result?.path)
        assertEquals("image/png", result?.contentType)
        assertEquals("키비주얼_A.png", result?.downloadName)
        verify(exactly = 0) { eventScopeGuard.assertAccess(any()) }
    }

    @Test
    fun `fetchMediaForKiosk는 콘텐츠가 없으면(소속 행사 불일치 포함) null을 반환한다`() {
        every { idleContentPort.fetchById("ev1", "ghost") } returns null

        val result = service.fetchMediaForKiosk("ev1", "ghost")

        assertNull(result)
    }

    @Test
    fun `fetchMediaForKiosk는 fileUrl이 없으면(구 메타 전용 행) null을 반환한다`() {
        every { idleContentPort.fetchById("ev1", "c1") } returns sampleContent(id = "c1", fileUrl = null)

        val result = service.fetchMediaForKiosk("ev1", "c1")

        assertNull(result)
    }

    @Test
    fun `fetchMediaForKiosk는 물리 파일이 유실됐으면(resolve null) null을 반환한다`() {
        every { idleContentPort.fetchById("ev1", "c1") } returns
            sampleContent(id = "c1", fileUrl = "ev1/c1", fileContentType = "image/png")
        every { fileStoragePort.resolve("ev1", "ev1/c1") } returns null

        val result = service.fetchMediaForKiosk("ev1", "c1")

        assertNull(result)
    }
}
