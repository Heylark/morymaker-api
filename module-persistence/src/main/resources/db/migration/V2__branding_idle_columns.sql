-- V2 — 행사 브랜딩·대기화면 게이트 컬럼 추가(§2-4·§11).
--
-- baseline-version=0 환경이라 V1__init.sql은 그대로 두고 이 파일만 추가한다(application.yml
-- 실측 확인 — baseline=1 환경의 "V1 미실행" 함정과 무관, V1·V2 모두 정상 적용된다).

-- event.default_idle_mode — 행사 기본 대기화면 표시방식(§11-1). idle_content.mode와 동일 값집합
-- (branded/fullbleed)을 공유하므로 동일 타입 채택. 콘텐츠별 mode 미지정 시 이 값으로 폴백한다.
ALTER TABLE event
    ADD COLUMN default_idle_mode VARCHAR(20) NULL AFTER kv;

-- idle_content.file_url — 대기화면 콘텐츠 미디어 URL(§11-3). 1차는 메타만 등록하고 실 바이트
-- 저장은 FileStoragePort 뒤로 이연(사용자 확정) — 신규 생성 콘텐츠는 NULL(스텁 반환값).
ALTER TABLE idle_content
    ADD COLUMN file_url VARCHAR(500) NULL AFTER play;
