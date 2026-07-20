-- 한 참석자는 행사 전체에서 최대 1좌석에만 배정된다(1인 다좌석 방지 — 무결성 §b).
-- guest_id가 NULL인 빈좌석(numbering ON 번호 점유 좌석)은 이 제약 대상에서 자연 제외된다:
-- InnoDB UNIQUE 인덱스는 NULL을 서로 다른 값으로 취급해 다건 NULL을 허용하므로, 조건이 곧
-- "guest_id IS NOT NULL"이 되어 parking active_key 같은 생성 컬럼 우회가 불필요하다(주차는
-- 조건이 status='주차중'이라 non-null 컬럼을 NULL로 바꿔줄 생성 컬럼이 필요했다).
-- guest_id는 전역 유일 UUID라 event_id 결합도 불필요하다.
--
-- 기존 idx_seat_guest(비유니크)는 아래 UNIQUE 인덱스와 선두 컬럼이 완전히 겹쳐 중복이므로 함께
-- 제거한다 — 같은 ALTER 안에서 DROP과 ADD를 동시에 수행해도 fk_seatassign_guest가 요구하는
-- 인덱스가 새 UNIQUE 인덱스로 즉시 대체되어 끊김이 없다(동일 FK 구조의 스크래치 테이블로 실측
-- 검증 완료 — MariaDB 11).
ALTER TABLE seat_assignment
    DROP INDEX idx_seat_guest,
    ADD UNIQUE KEY uq_seatassign_guest (guest_id);
