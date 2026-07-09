package kr.co.morymaker.api.application.parking

/**
 * 동시 등록 최종 방어(P5) — `parking_record.active_key` UNIQUE 위반을 도메인 예외로 번역한 것.
 *
 * `ParkingRecordService.register`가 신규 삽입(케이스 B·E) 시점의
 * `org.springframework.dao.DuplicateKeyException`을 좁게 catch하여 이 예외로 바꾼다. UNIQUE
 * 제약이 `uq_precord_active` 하나뿐이라 이 예외로 좁혀도 다른 무결성 위반을 오분류하지 않는다
 * (FK 등 타 무결성까지 잡는 `DataIntegrityViolationException` 광역 catch는 과포획이라 회피).
 */
class SlotOccupiedException : RuntimeException("이미 사용 중인 자리입니다")
