package kr.co.morymaker.api.domain.parking

/**
 * 주차 자리 파생 규칙 — zone·record·QR 3개 소비처(§6-1/§6-4/§6-5)가 공유하는 단일 진실 소스.
 *
 * proto/data.js(74~82, 129)와 정합하는 순수 함수만 모은다(부수효과 없음, Spring 무의존) —
 * domain 순수성을 지키면서도 여러 서비스가 각자 유사 로직을 중복 구현해 drift(표기 불일치)가
 * 생기는 것을 막는다.
 */
object ParkingSlot {

    /** part1~4 중 빈값 제외 공백 결합(data.js `zoneName`) — 예 "지하 2층 A구역". */
    fun zoneName(p1: String?, p2: String?, p3: String?, p4: String?): String =
        listOf(p1, p2, p3, p4).filterNot { it.isNullOrBlank() }.joinToString(" ")

    /** part1에 "야외"가 포함되면 옥외 구획(data.js `zoneOutdoor`). */
    fun outdoor(p1: String?): Boolean = (p1 ?: "").contains("야외")

    /** 0-based index → 자리 번호(구획 시작 번호 기준). */
    fun slotNo(startNo: Int, index: Int): Int = startNo + index

    /** part1~4 + 자리 번호를 `·`로 결합한 사이니지 시그(data.js `slotSig`) — 예 "지하 2층·A구역·3". */
    fun slotSig(p1: String?, p2: String?, p3: String?, p4: String?, slotNo: Int): String =
        (listOf(p1, p2, p3, p4).filterNot { it.isNullOrBlank() } + slotNo.toString()).joinToString("·")

    /** 자리 타이틀 — override가 있으면 그 값, 없으면 번호 문자열이 기본값. */
    fun slotTitle(override: String?, slotNo: Int): String =
        if (!override.isNullOrBlank()) override else slotNo.toString()

    /** QR 파일명·전체 자리명(data.js `slotFullName`) — 예 "지하 2층 A구역 3". */
    fun slotFullName(zoneName: String, slotTitle: String): String =
        if (zoneName.isNotBlank()) "$zoneName $slotTitle" else slotTitle

    /** 사이니지 시그의 한국어 표기 변환(data.js `slotWords`) — 예 "지하 2층 A구역 3번". */
    fun slotDisplay(slotSig: String): String {
        val parts = slotSig.split("·")
        return (parts.dropLast(1).joinToString(" ") + " " + parts.last() + "번").trim()
    }

    /**
     * 자리코드(signage 식별 규약, ADR-P2) — numeral은 인쇄 절대 번호(slot_no)로 고정한다.
     * slotSig·parking_slot_title.slot_no·QR 파일명과 항상 동일 번호를 가리킨다 — 예 "z1-08".
     */
    fun slotCode(zoneId: String, slotNo: Int): String = "$zoneId-${"%02d".format(slotNo)}"
}
