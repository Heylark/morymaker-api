package kr.co.morymaker.api.storage

/**
 * 앱 정책 상한(이미지 20MB) 초과 — 컨테이너 상한(영상 200MB, `MaxUploadSizeExceededException`)과
 * 별개 지점이다. `GlobalExceptionHandler`가 둘 다 같은 코드(`FILE_TOO_LARGE`)로 응답한다 —
 * 클라이언트 입장에선 어느 쪽이든 "파일이 큼"이다.
 */
class MediaTooLargeException(message: String) : RuntimeException(message)
