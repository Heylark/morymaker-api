package kr.co.morymaker.api.application.port.`in`

/**
 * 주차 구획 유스케이스 포트-in — api-app의 `ParkingZoneController`가 호출한다(§6 구획).
 *
 * QR 발급(§6-4)은 별도 서비스 메서드 없이 [getSlotsForQr]가 반환한 자리 목록을 컨트롤러가
 * `QrCodeGenerator`·`ZipBuilder`에 넘겨 완성한다 — QR 라이브러리는 api-app 레이어에만 배선한다
 * (02-architect §7).
 */
interface ParkingZoneUseCase {

    /** 목록(§6-1). zoneName·outdoor 파생 + titleOverrides 조인. */
    fun listZones(eventId: String): List<ZoneView>

    /** 등록(§6-2). part1 필수(DTO `@NotBlank` 검증 통과 전제). 물리 slot row 미생성. */
    fun createZone(eventId: String, command: ZoneCreateCommand): ZoneView

    /** 수정(§6-3). titleOverrides가 null이 아니면 zone_id 기준 delete-insert로 전체 교체. */
    fun updateZone(eventId: String, zid: String, command: ZoneUpdateCommand): ZoneView

    /** QR 발급용 파생 자리 목록(§6-4) — zoneName + slotNo/slotCode/slotFullName 목록. */
    fun getSlotsForQr(eventId: String, zid: String): ZoneSlotsView
}

/** [ParkingZoneUseCase.createZone] 입력(§6-2). */
data class ZoneCreateCommand(
    val part1: String,
    val part2: String?,
    val part3: String?,
    val part4: String?,
    val startNo: Int,
    val slotCount: Int,
)

/** [ParkingZoneUseCase.updateZone] 입력(§6-3). `titleOverrides=null`이면 타이틀은 변경하지 않는다. */
data class ZoneUpdateCommand(
    val part1: String,
    val part2: String?,
    val part3: String?,
    val part4: String?,
    val startNo: Int,
    val slotCount: Int,
    val titleOverrides: Map<String, String>?,
)

/** [ParkingZoneUseCase.listZones]/[createZone]/[updateZone] 결과 read model. */
data class ZoneView(
    val id: String,
    val part1: String?,
    val part2: String?,
    val part3: String?,
    val part4: String?,
    val zoneName: String,
    val outdoor: Boolean,
    val startNo: Int,
    val slotCount: Int,
    val titleOverrides: Map<String, String>,
)

/** [ParkingZoneUseCase.getSlotsForQr] 파생 자리 1건 — QR payload·ZIP entry명 계산에 사용. */
data class SlotView(val slotNo: Int, val slotCode: String, val slotFullName: String)

/** [ParkingZoneUseCase.getSlotsForQr] 결과 — ZIP 파일명(zoneName)과 자리 목록을 함께 반환한다. */
data class ZoneSlotsView(val zoneName: String, val slots: List<SlotView>)
