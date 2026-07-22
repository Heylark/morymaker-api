package kr.co.morymaker.api.web

import org.apache.poi.EncryptedDocumentException

/**
 * 업로드된 엑셀에 열기 암호가 걸려 있음 — [GuestExcelParser]가 워크북 생성 실패를 이 예외로 번역하고
 * [GlobalExceptionHandler]가 400(IMPORT_FILE_PASSWORD_PROTECTED)으로 변환한다.
 *
 * 안내 문구를 파일 손상 쪽과 공유하지 않는 것이 이 클래스가 따로 존재하는 이유다. 암호 파일은 손상된
 * 것이 아니고, 양식을 다시 내려받아도 암호는 풀리지 않는다 — 손상 안내를 그대로 쓰면 사용자에게
 * 아무 소용 없는 행동을 지시하게 된다.
 *
 * 원인 예외는 IllegalStateException 계열이지만 그 계열을 광역으로 잡으면 서버 불변식 위반이 의도적으로
 * 500으로 나가는 경로까지 400이 되므로, 파서의 열기 단계에서 해당 타입만 좁게 잡아 이 예외로 바꾼다.
 */
class GuestImportFilePasswordProtectedException(
    cause: EncryptedDocumentException,
) : RuntimeException(
    "암호가 설정된 엑셀 파일은 열 수 없습니다 — 엑셀에서 암호를 해제한 뒤 다시 올려 주세요.",
    cause,
)
