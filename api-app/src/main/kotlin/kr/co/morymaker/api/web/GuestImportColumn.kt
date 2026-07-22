package kr.co.morymaker.api.web

/**
 * 명단 엑셀 업로드 컬럼 계약(§4-5)의 단일 정의 — 파서·헤더 검증·템플릿 생성기가 모두 이 선언 하나만 본다.
 *
 * **선언 순서가 곧 시트 열 순서**(A열부터)이며 열 번호는 선언 위치에서 파생된다. 열 번호를 별도 상수로
 * 적지 않으므로 "라벨만 바꾸고 열 번호는 그대로" 같은 어긋남을 애초에 표현할 수 없다 — 템플릿이 알려준
 * 순서와 파서가 읽는 순서가 달라지면 업로드가 조용히 잘못된 칸에 저장되기 때문에, 두 정의를 물리적으로
 * 하나로 묶는 것이 이 파일의 존재 이유다.
 *
 * 6열 구성 자체는 실 엑셀 양식 미수령 상태의 잠정 계약이다. 양식 수령 시 이 파일만 고치면 파서·검증·
 * 템플릿이 함께 따라온다.
 */
internal enum class GuestImportColumn(val header: String) {
    NAME("이름"),
    ORG("소속"),
    TITLE("직함"),
    PHONE("연락처"),
    PLATE("차량번호"),
    SEAT_GROUP_LABEL("좌석그룹"),
    ;

    /** 시트 열 인덱스(0-based) — 선언 순서에서 파생. */
    val index: Int get() = ordinal

    companion object {
        /** 헤더 행 인덱스. 데이터는 그다음 행부터. */
        const val HEADER_ROW_INDEX = 0

        /** 비교·생성 공통 정규화 — 공백만 제거한다. 사람이 넣는 공백 차이("차량 번호")로 차단하면
         * 오탐이 실제 오염보다 잦아지기 때문이다. */
        fun normalize(raw: String?): String = raw?.filterNot { it.isWhitespace() } ?: ""
    }
}
