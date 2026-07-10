package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.port.`in`.IdleContentCreateCommand
import kr.co.morymaker.api.application.port.`in`.IdleContentUpdateCommand
import kr.co.morymaker.api.application.port.`in`.IdleContentUseCase
import kr.co.morymaker.api.application.port.`in`.IdleContentView
import kr.co.morymaker.api.application.port.`in`.PublicIdleContentUseCase
import kr.co.morymaker.api.application.port.out.FileStoragePort
import kr.co.morymaker.api.application.port.out.IdleContentPort
import kr.co.morymaker.api.application.port.out.StoreFileCommand
import kr.co.morymaker.api.application.security.EventScopeGuard
import kr.co.morymaker.api.domain.idle.IdleContent
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * [IdleContentUseCase]·[PublicIdleContentUseCase] 구현체 — 관리자 CRUD(§11-2~4)와 키오스크
 * 공개 조회(§11-2, M3)를 한 클래스가 함께 담당한다.
 *
 * 관리자 메서드([list]/[create]/[update])만 `EventScopeGuard.assertAccess`를 호출한다.
 * [listForKiosk]는 무인증 표면 전용이라 스코프 게이트를 호출하지 않는다(ADR-003) — 격리는
 * `IdleContentPort.findByEvent`의 `event_id` WHERE 필터로만 제공한다.
 *
 * 헥사고날 레이어: application(service). `internal`: api-app은 [IdleContentUseCase]/
 * [PublicIdleContentUseCase] 인터페이스만 의존한다.
 */
@Service
internal class IdleContentService(
    private val idleContentPort: IdleContentPort,
    private val fileStoragePort: FileStoragePort,
    private val eventScopeGuard: EventScopeGuard,
) : IdleContentUseCase, PublicIdleContentUseCase {

    @Transactional(readOnly = true)
    override fun list(eventId: String): List<IdleContentView> {
        eventScopeGuard.assertAccess(eventId)
        return idleContentPort.findByEvent(eventId).map { it.toView() }
    }

    @Transactional
    override fun create(eventId: String, command: IdleContentCreateCommand): IdleContentView {
        eventScopeGuard.assertAccess(eventId)
        val stored = fileStoragePort.store(
            StoreFileCommand(eventId = eventId, name = command.name, kind = command.kind),
        )
        val content = IdleContent(
            id = UUID.randomUUID().toString(),
            eventId = eventId,
            name = command.name,
            kind = command.kind,
            mode = command.mode,
            play = command.play,
            fileUrl = stored.fileUrl,
            sortOrder = command.sortOrder,
        )
        idleContentPort.insert(content)
        return content.toView()
    }

    @Transactional
    override fun update(eventId: String, cid: String, command: IdleContentUpdateCommand): IdleContentView {
        eventScopeGuard.assertAccess(eventId)
        val existing = idleContentPort.fetchById(eventId, cid)
            ?: throw NoSuchElementException("대기화면 콘텐츠를 찾을 수 없습니다")
        val merged = existing.with(mode = command.mode, play = command.play, sortOrder = command.sortOrder)
        idleContentPort.update(merged)
        return merged.toView()
    }

    // 키오스크 공개 조회(§11-2, M3) — assertAccess 없음(무인증 설계, ADR-003). 존재하지 않는
    // eventId는 findByEvent가 자연히 빈 리스트를 반환한다(fail-open, 404 아님).
    @Transactional(readOnly = true)
    override fun listForKiosk(eventId: String): List<IdleContentView> =
        idleContentPort.findByEvent(eventId).map { it.toView() }

    private fun IdleContent.toView(): IdleContentView = IdleContentView(
        id = id,
        eventId = eventId,
        name = name,
        kind = kind,
        mode = mode,
        play = play,
        fileUrl = fileUrl,
        sortOrder = sortOrder,
    )
}
