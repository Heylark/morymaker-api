package kr.co.morymaker.api.web

import java.io.IOException

/**
 * 업로드된 바이트를 엑셀로 열지 못함 — [GuestExcelParser]가 워크북 생성 실패를 이 예외로 번역하고
 * [GlobalExceptionHandler]가 400(IMPORT_FILE_UNREADABLE)으로 변환한다.
 *
 * IllegalArgumentException을 상속하지 않는다 — 상속하면 기존 공통 400 핸들러가 먼저 잡아 전용 코드가
 * 사라지고, 화면은 파일 안내 대신 일반 오류만 보여준다(에러 없이 안내 문구만 사라지는 종류의 실패).
 *
 * 원인 예외는 cause로만 보존하고 사용자 메시지에는 싣지 않는다 — 라이브러리가 만든 영문 진단 문구나
 * 내부 경로가 그대로 노출되며, 사용자가 할 수 있는 행동을 바꾸지도 못한다.
 */
class GuestImportFileUnreadableException(
    cause: IOException,
) : RuntimeException(
    "엑셀 파일(.xlsx)이 아니거나 손상되어 열 수 없습니다 — CSV·PDF 등 다른 형식을 확장자만 바꿔 올린 " +
        "경우에도 같은 안내가 나옵니다. 상단 [템플릿 받기]로 양식을 내려받아 그대로 채워 주세요.",
    cause,
)
