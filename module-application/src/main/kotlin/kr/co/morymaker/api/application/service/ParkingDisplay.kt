package kr.co.morymaker.api.application.service

/**
 * 주차 자리 표기(slotSig) → 화면 표시 문자열 파생 — `CheckinService`(§5-1)·`LookupService`(§9-1)가
 * 공유하는 유틸이다. slotSig("지하 2층·A구역·3")의 `·` 구분자만 공백으로 정규화한다(완전 포맷팅은
 * §6 이연 — 도메인 canonical 표기와의 통일은 별도 후속 작업 몫이며, 그 위치는 tech-debt에 남긴다).
 */
internal object ParkingDisplay {
    fun derive(slotSig: String): String = slotSig.replace("·", " ")
}
