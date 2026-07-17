package kr.co.morymaker.api.application.port.`in`

import java.io.InputStream

/**
 * 대기화면 콘텐츠 관리자 유스케이스 포트-in(§11-2~4) — api-app의 `IdleContentController`가
 * 호출한다. 전 메서드 `EventScopeGuard.assertAccess` 재검증(Layer2b) — 무인증 키오스크 조회는
 * [PublicIdleContentUseCase]가 별도로 담당한다(무인증 표면 분리).
 */
interface IdleContentUseCase {

    /** 목록(§11-2, 관리자) — sortOrder 순. */
    fun list(eventId: String): List<IdleContentView>

    /**
     * 등록(§11-3) — `file` 파트 필수(M3, multipart 도입). 저장은
     * [kr.co.morymaker.api.application.port.out.FileStoragePort]에 위임한다.
     */
    fun create(eventId: String, command: IdleContentCreateCommand): IdleContentView

    /** 수정(§11-4) — mode/play/sortOrder만 갱신, name/kind/fileUrl은 불변. */
    fun update(eventId: String, cid: String, command: IdleContentUpdateCommand): IdleContentView

    /** 삭제(§11-4) — DB 메타 삭제 확정 후 물리 파일을 회수한다(구 메타 전용 행은 파일 삭제 스킵). */
    fun delete(eventId: String, cid: String)
}

/**
 * [IdleContentUseCase.create] 입력(§11-3). `name`·`kind` 필수, `mode`/`play`는 미지정 허용.
 *
 * `source`는 api-app 컨트롤러가 소유한 스트림이다 — 이 커맨드·서비스·포트 어느 구간도 close하지
 * 않는다(호출자가 `use{}`로 감싼다). `size`는 컨테이너가 실측한 바이트 수(클라이언트 신고값 아님).
 */
data class IdleContentCreateCommand(
    val name: String,
    val kind: String,
    val mode: String?,
    val play: String?,
    val sortOrder: Int,
    val source: InputStream,
    val size: Long,
)

/** [IdleContentUseCase.update] 입력(§11-4). */
data class IdleContentUpdateCommand(
    val mode: String?,
    val play: String?,
    val sortOrder: Int,
)

/** [IdleContentUseCase]/[PublicIdleContentUseCase] 공통 결과 read model. */
data class IdleContentView(
    val id: String,
    val eventId: String,
    val name: String,
    val kind: String,
    val mode: String?,
    val play: String?,
    /** 스토리지 키(`{eventId}/{cid}`) — URL이 아니다. 응답 조립은 `IdleContentResponse.toResponse(mediaBaseUrl)`가 담당한다. */
    val fileUrl: String?,
    /** magic byte 검증으로 확정된 MIME — 서빙 시점 Content-Type의 단일 진실 소스. */
    val fileContentType: String?,
    val sortOrder: Int,
)
