package kr.co.morymaker.api.application.service

import kr.co.morymaker.api.application.port.`in`.IdleContentCreateCommand
import kr.co.morymaker.api.application.port.`in`.IdleContentMedia
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
 * [listForKiosk]·[fetchMediaForKiosk]는 무인증 표면 전용이라 스코프 게이트를 호출하지 않는다 —
 * 격리는 `IdleContentPort`의 `event_id` WHERE 필터로만 제공한다.
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

    // 저장 성공 후 insert 실패 시 파일이 orphan으로 남지 않도록, store()는 이 트랜잭션 내부에서
    // 호출된다 — 어댑터가 트랜잭션 동기화로 자기 부작용을 자기 안에서 보상한다(포트 계약
    // 무변화). contentId를 먼저 생성해 저장·영속 양쪽에 동일 식별자로 사용한다.
    @Transactional
    override fun create(eventId: String, command: IdleContentCreateCommand): IdleContentView {
        eventScopeGuard.assertAccess(eventId)
        val contentId = UUID.randomUUID().toString()
        val stored = fileStoragePort.store(
            StoreFileCommand(
                eventId = eventId,
                contentId = contentId,
                originalName = command.name,
                declaredKind = command.kind,
                size = command.size,
                source = command.source,
            ),
        )
        val content = IdleContent(
            id = contentId,
            eventId = eventId,
            name = command.name,
            kind = command.kind,
            mode = command.mode,
            play = command.play,
            fileUrl = stored.storageKey,
            fileContentType = stored.contentType,
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

    // 키오스크 공개 조회(§11-2, M3) — assertAccess 없음(무인증 설계). 존재하지 않는
    // eventId는 findByEvent가 자연히 빈 리스트를 반환한다(fail-open, 404 아님).
    @Transactional(readOnly = true)
    override fun listForKiosk(eventId: String): List<IdleContentView> =
        idleContentPort.findByEvent(eventId).map { it.toView() }

    // 키오스크 미디어 서빙(§11-2, M3 신설) — assertAccess 없음(무인증 설계). fetchById가 이미
    // event_id 스코프로 조회하므로(cross-event 결함 무인증 표면에서 신규 생산 회피), 소속 불일치는
    // null(→ 컨트롤러가 404)로 자연히 수렴한다. fileUrl·fileContentType 어느 한쪽이라도 없으면
    // (구 메타 전용 행) 서빙 대상이 아니므로 null.
    @Transactional(readOnly = true)
    override fun fetchMediaForKiosk(eventId: String, contentId: String): IdleContentMedia? {
        val content = idleContentPort.fetchById(eventId, contentId) ?: return null
        val storageKey = content.fileUrl ?: return null
        val contentType = content.fileContentType ?: return null
        val path = fileStoragePort.resolve(eventId, storageKey) ?: return null
        return IdleContentMedia(path = path, contentType = contentType, downloadName = content.name)
    }

    private fun IdleContent.toView(): IdleContentView = IdleContentView(
        id = id,
        eventId = eventId,
        name = name,
        kind = kind,
        mode = mode,
        play = play,
        fileUrl = fileUrl,
        fileContentType = fileContentType,
        sortOrder = sortOrder,
    )
}
