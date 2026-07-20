-- V4 — 대기화면 미디어 실 저장 도입에 따른 MIME 메타 컬럼 추가.
--
-- idle_content.file_content_type — magic byte(콘텐츠 시그니처) 검증으로 확정된 MIME. 서빙
-- endpoint의 Content-Type 단일 진실 소스(서빙 시점 재검사·확장자 추정 어느 쪽도 채택하지
-- 않음 — 재검사는 매 요청 파일 I/O + 판별 로직 이원화, 확장자 추정은 확장자 없는 저장
-- 파일명이라 불가능할뿐더러 magic byte 도입 취지와 정면 모순).
--
-- NULL 허용 — 구 메타 전용 행(file_url IS NULL)의 하위호환. 신규 행은 file 파트가 필수이므로
-- 항상 non-null이다(file_url과 생멸을 같이한다).
ALTER TABLE idle_content
    ADD COLUMN file_content_type VARCHAR(100) NULL AFTER file_url;
