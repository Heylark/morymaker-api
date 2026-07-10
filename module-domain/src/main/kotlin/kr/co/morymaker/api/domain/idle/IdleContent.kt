package kr.co.morymaker.api.domain.idle

/**
 * 대기화면 콘텐츠 — 키오스크 유휴 화면에 순서대로 노출된다(§11-2~4).
 *
 * Event.kt·ParkingZone.kt와 동일 원칙: 일반 class + id 기반 equals/hashCode(data class 아님).
 * `createdAt`이 없다 — V1__init.sql `idle_content` 테이블에 해당 컬럼 자체가 없다(등록순은
 * `sortOrder`로만 표현).
 *
 * @param id 콘텐츠 PK(UUID)
 * @param eventId 소속 행사 PK — 모든 조회·변경의 격리 기준
 * @param name 콘텐츠명(파일명 표기)
 * @param kind `영상`/`이미지`
 * @param mode `branded`(행사명·안내 함께) / `fullbleed`(전체화면) / null(행사 기본값 상속 — event.defaultIdleMode 폴백)
 * @param play 재생 방식 표기(예: "8초 롤링", "자동재생 · 무음 · 반복")
 * @param fileUrl 미디어 URL — 1차 범위는 항상 null(FileStoragePort 스텁, 실 바이트 저장 이연)
 * @param sortOrder 재생 순서(슬라이드 롤링)
 */
class IdleContent(
    val id: String,
    val eventId: String,
    val name: String,
    val kind: String,
    val mode: String?,
    val play: String?,
    val fileUrl: String?,
    val sortOrder: Int,
) {
    // 수정(§11-4) — mode/play/sortOrder만 갱신, name·kind·fileUrl은 불변(ParkingZone.with() 패턴).
    fun with(
        mode: String? = this.mode,
        play: String? = this.play,
        sortOrder: Int = this.sortOrder,
    ): IdleContent = IdleContent(id, eventId, name, kind, mode, play, fileUrl, sortOrder)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IdleContent) return false
        return id == other.id
    }

    override fun hashCode(): Int = id.hashCode()

    override fun toString(): String = "IdleContent(id=$id, name=$name, kind=$kind, sortOrder=$sortOrder)"
}
