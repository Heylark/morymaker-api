package kr.co.morymaker.api.application.port.`in`

/**
 * 대기화면 콘텐츠 관리자 유스케이스 포트-in(§11-2~4) — api-app의 `IdleContentController`가
 * 호출한다. 전 메서드 `EventScopeGuard.assertAccess` 재검증(Layer2b) — 무인증 키오스크 조회는
 * [PublicIdleContentUseCase]가 별도로 담당한다(ADR-003, 무인증 표면 분리).
 */
interface IdleContentUseCase {

    /** 목록(§11-2, 관리자) — sortOrder 순. */
    fun list(eventId: String): List<IdleContentView>

    /** 등록(§11-3) — 메타 전용(M2, multipart 미도입). [kr.co.morymaker.api.application.port.out.FileStoragePort]에 위임한 fileUrl을 그대로 저장한다(스텁은 null). */
    fun create(eventId: String, command: IdleContentCreateCommand): IdleContentView

    /** 수정(§11-4) — mode/play/sortOrder만 갱신, name/kind/fileUrl은 불변. */
    fun update(eventId: String, cid: String, command: IdleContentUpdateCommand): IdleContentView
}

/** [IdleContentUseCase.create] 입력(§11-3). `name`·`kind` 필수, `mode`/`play`는 미지정 허용. */
data class IdleContentCreateCommand(
    val name: String,
    val kind: String,
    val mode: String?,
    val play: String?,
    val sortOrder: Int,
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
    val fileUrl: String?,
    val sortOrder: Int,
)
