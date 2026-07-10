package kr.co.morymaker.api.application.seat

/**
 * 좌석 배정 충돌(§12-5, `SEAT-CONCURRENCY-GUARD`) — cross-group 중복 배정 사전검사(친절 에러) 또는
 * `guest_id` UNIQUE 경쟁 후착·DELETE 갭 경합의 최종 방어(409) 양쪽을 대표한다.
 *
 * `ParkingRecordService`의 `SlotOccupiedException` 미러이되, 사전검사 실패와 DB 최종 방어 두
 * 경로가 서로 다른 메시지를 전달해야 해서 메시지를 파라미터화한다(SlotOccupiedException은 고정
 * 메시지 1종).
 */
class SeatConflictException(message: String) : RuntimeException(message)
