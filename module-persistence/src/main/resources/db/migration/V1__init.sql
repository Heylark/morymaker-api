-- 참석확인·주차의전·좌석안내 도메인 10개 테이블 1회 생성.
-- 계정(account)과 계정-행사 담당(account_event)은 인증 서버가 별도 Flyway 이력으로 소유하므로
-- 이 마이그레이션에는 포함하지 않는다 — 이 서버는 발급된 토큰의 event_ids 클레임만 신뢰한다.
--
-- 생성 순서는 외래키 의존 순서를 따른다:
--   event -> seat_group -> guest -> seat_assignment -> parking_zone -> parking_slot_title
--   -> parking_record -> sms_template -> sms_log -> idle_content

-- ────────────────────────────────────────────────────────────────
-- 1. event — 행사 (최상위 격리 단위)
-- ────────────────────────────────────────────────────────────────
CREATE TABLE event
(
    id           CHAR(36)     NOT NULL,
    name         VARCHAR(120) NOT NULL,
    event_date   DATETIME     NULL,
    place        VARCHAR(120) NULL,
    type         VARCHAR(20)  NULL,
    status       VARCHAR(10)  NOT NULL DEFAULT '준비',
    active       TINYINT(1)   NOT NULL DEFAULT 0,
    bg_color     CHAR(7)      NULL,
    point_color  CHAR(7)      NULL,
    title_color  CHAR(7)      NULL,
    body_color   CHAR(7)      NULL,
    kv           VARCHAR(120) NULL,
    sms_policy   VARCHAR(60)  NULL DEFAULT '초대 1회만 (기타 미발송)',
    created_at   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_event_active (active)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '행사 — 모든 데이터의 최상위 격리 단위';

-- ────────────────────────────────────────────────────────────────
-- 2. seat_group — 좌석 그룹 (numbering 토글로 지정석/자유석 통합 표현)
-- ────────────────────────────────────────────────────────────────
CREATE TABLE seat_group
(
    id         CHAR(36)    NOT NULL,
    event_id   CHAR(36)    NOT NULL,
    group_no   INT         NOT NULL,
    label      VARCHAR(60) NOT NULL,
    numbering  TINYINT(1)  NOT NULL DEFAULT 0,
    sort_order INT         NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_seatgroup_event_no (event_id, group_no),
    KEY idx_seatgroup_event (event_id),
    CONSTRAINT fk_seatgroup_event FOREIGN KEY (event_id) REFERENCES event (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '좌석 그룹 — numbering ON이면 지정석(번호 좌석), OFF면 자유석/테이블 명단';

-- ────────────────────────────────────────────────────────────────
-- 3. guest — 참석자 (VIP 명단 핵심 엔티티)
-- ────────────────────────────────────────────────────────────────
CREATE TABLE guest
(
    id            CHAR(36)    NOT NULL,
    event_id      CHAR(36)    NOT NULL,
    name          VARCHAR(60) NOT NULL,
    org           VARCHAR(120) NULL,
    title         VARCHAR(60) NULL,
    phone         VARCHAR(20) NULL,
    plate         VARCHAR(20) NULL,
    seat_group_id CHAR(36)    NULL,
    status        VARCHAR(10) NOT NULL DEFAULT '대기',
    src           VARCHAR(10) NOT NULL DEFAULT '사전',
    visit_at      DATETIME    NULL,
    token         VARCHAR(64) NOT NULL,
    created_at    DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_guest_token (token),
    KEY idx_guest_event (event_id),
    KEY idx_guest_event_name (event_id, name),
    KEY idx_guest_event_plate (event_id, plate),
    KEY idx_guest_event_status (event_id, status),
    CONSTRAINT fk_guest_event FOREIGN KEY (event_id) REFERENCES event (id) ON DELETE CASCADE,
    CONSTRAINT fk_guest_seat_group FOREIGN KEY (seat_group_id) REFERENCES seat_group (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '참석자(VIP) — 명단 관리·체크인·좌석/주차 매핑의 중심 엔티티';

-- ────────────────────────────────────────────────────────────────
-- 4. seat_assignment — 좌석 배정 (그룹 1:N, 실좌석 SSOT)
-- ────────────────────────────────────────────────────────────────
CREATE TABLE seat_assignment
(
    id            CHAR(36) NOT NULL,
    event_id      CHAR(36) NOT NULL,
    seat_group_id CHAR(36) NOT NULL,
    ord           INT      NOT NULL,
    guest_id      CHAR(36) NULL,
    PRIMARY KEY (id),
    KEY idx_seat_group (seat_group_id, ord),
    KEY idx_seat_guest (guest_id),
    KEY idx_seat_event (event_id),
    CONSTRAINT fk_seatassign_event FOREIGN KEY (event_id) REFERENCES event (id) ON DELETE CASCADE,
    CONSTRAINT fk_seatassign_group FOREIGN KEY (seat_group_id) REFERENCES seat_group (id) ON DELETE CASCADE,
    CONSTRAINT fk_seatassign_guest FOREIGN KEY (guest_id) REFERENCES guest (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '좌석 배정 — numbering ON 그룹은 guest_id가 비어도 번호를 점유한 실좌석 1석을 의미';

-- ────────────────────────────────────────────────────────────────
-- 5. parking_zone — 주차 구획 (구분1~4 자유필드, 자리는 파생 계산)
-- ────────────────────────────────────────────────────────────────
CREATE TABLE parking_zone
(
    id         CHAR(36)    NOT NULL,
    event_id   CHAR(36)    NOT NULL,
    part1      VARCHAR(40) NULL,
    part2      VARCHAR(40) NULL,
    part3      VARCHAR(40) NULL,
    part4      VARCHAR(40) NULL,
    start_no   INT         NOT NULL DEFAULT 1,
    slot_count INT         NOT NULL,
    created_at DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_pzone_event (event_id),
    CONSTRAINT fk_pzone_event FOREIGN KEY (event_id) REFERENCES event (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '주차 구획 — 개별 자리(slot)는 행으로 저장하지 않고 start_no+slot_count로 파생';

-- ────────────────────────────────────────────────────────────────
-- 6. parking_slot_title — 자리 타이틀 override (개별 수정분만 저장)
-- ────────────────────────────────────────────────────────────────
CREATE TABLE parking_slot_title
(
    zone_id        CHAR(36)    NOT NULL,
    slot_no        INT         NOT NULL,
    title_override VARCHAR(60) NOT NULL,
    PRIMARY KEY (zone_id, slot_no),
    CONSTRAINT fk_pslottitle_zone FOREIGN KEY (zone_id) REFERENCES parking_zone (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '자리 타이틀 커스텀 override — 미지정 자리는 번호를 그대로 타이틀로 사용';

-- ────────────────────────────────────────────────────────────────
-- 7. parking_record — 주차 기록 (자리당 활성 1건을 DB가 최종 강제)
-- ────────────────────────────────────────────────────────────────
CREATE TABLE parking_record
(
    id             CHAR(36)     NOT NULL,
    event_id       CHAR(36)     NOT NULL,
    zone_id        CHAR(36)     NOT NULL,
    slot_sig       VARCHAR(120) NOT NULL,
    plate          VARCHAR(20)  NOT NULL,
    phone          VARCHAR(20)  NULL,
    vip_name       VARCHAR(60)  NULL,
    guest_id       CHAR(36)     NULL,
    registered_by  VARCHAR(10)  NOT NULL,
    registered_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    status         VARCHAR(10)  NOT NULL DEFAULT '주차중',
    review_needed  TINYINT(1)   NOT NULL DEFAULT 0,
    -- 자리(slot_sig)당 '주차중' 레코드는 최대 1건이어야 하는데, MariaDB는 부분(조건부) UNIQUE를
    -- 지원하지 않는다. 상태가 '주차중'일 때만 값을 갖는 생성 컬럼을 만들어 일반 UNIQUE로 우회한다.
    -- 출차 상태는 이 컬럼이 NULL이 되어 UNIQUE 검사에서 자연히 제외되므로 출차 이력은 여러 건 쌓일 수 있다.
    -- TRIM()은 값을 바꾸지 않는다(UUID는 공백을 가질 수 없는 고정 36자) — MariaDB가 고정 길이
    -- CHAR 컬럼을 생성 컬럼 표현식에서 직접 참조하는 것을 막아 우회 목적으로만 추가했다(실측 확인).
    active_key     VARCHAR(120) GENERATED ALWAYS AS (
                       IF(status = '주차중', CONCAT(TRIM(event_id), '|', slot_sig), NULL)
                       ) STORED,
    PRIMARY KEY (id),
    UNIQUE KEY uq_precord_active (active_key),
    KEY idx_precord_event_status (event_id, status),
    KEY idx_precord_plate (event_id, plate),
    KEY idx_precord_guest (guest_id),
    KEY idx_precord_zone (zone_id),
    CONSTRAINT fk_precord_event FOREIGN KEY (event_id) REFERENCES event (id) ON DELETE CASCADE,
    CONSTRAINT fk_precord_zone FOREIGN KEY (zone_id) REFERENCES parking_zone (id) ON DELETE CASCADE,
    CONSTRAINT fk_precord_guest FOREIGN KEY (guest_id) REFERENCES guest (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '주차 기록 — active_key 생성 컬럼 + UNIQUE로 자리당 활성 1건을 DB가 최종 강제';

-- ────────────────────────────────────────────────────────────────
-- 8. sms_template — 초대 문자 템플릿 (행사당 단일 본문)
-- ────────────────────────────────────────────────────────────────
CREATE TABLE sms_template
(
    id         CHAR(36) NOT NULL,
    event_id   CHAR(36) NOT NULL,
    body       TEXT     NOT NULL,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_smstmpl_event (event_id),
    CONSTRAINT fk_smstmpl_event FOREIGN KEY (event_id) REFERENCES event (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '초대 문자 템플릿 — 행사당 단일 본문만 허용(멀티 슬롯 없음)';

-- ────────────────────────────────────────────────────────────────
-- 9. sms_log — 문자 발송 이력 (실제 발송 본문 스냅샷 보존)
-- ────────────────────────────────────────────────────────────────
CREATE TABLE sms_log
(
    id            CHAR(36)    NOT NULL,
    event_id      CHAR(36)    NOT NULL,
    guest_id      CHAR(36)    NULL,
    name_snapshot VARCHAR(90) NULL,
    phone         VARCHAR(20) NULL,
    sent_at       DATETIME    NOT NULL,
    status        VARCHAR(10) NOT NULL,
    body_snapshot TEXT        NULL,
    PRIMARY KEY (id),
    KEY idx_smslog_event (event_id),
    KEY idx_smslog_guest (guest_id),
    CONSTRAINT fk_smslog_event FOREIGN KEY (event_id) REFERENCES event (id) ON DELETE CASCADE,
    CONSTRAINT fk_smslog_guest FOREIGN KEY (guest_id) REFERENCES guest (id) ON DELETE SET NULL
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '문자 발송 이력 — 발송 시점 이름·본문 스냅샷을 보존해 나중에 무엇을 보냈는지 재현';

-- ────────────────────────────────────────────────────────────────
-- 10. idle_content — 대기화면 콘텐츠 (키오스크 유휴 화면)
-- ────────────────────────────────────────────────────────────────
CREATE TABLE idle_content
(
    id         CHAR(36)     NOT NULL,
    event_id   CHAR(36)     NOT NULL,
    name       VARCHAR(200) NOT NULL,
    kind       VARCHAR(10)  NOT NULL,
    mode       VARCHAR(20)  NULL,
    play       VARCHAR(60)  NULL,
    sort_order INT          NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_idle_event (event_id, sort_order),
    CONSTRAINT fk_idle_event FOREIGN KEY (event_id) REFERENCES event (id) ON DELETE CASCADE
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '대기화면 콘텐츠 — 키오스크 유휴 화면에 순서대로 노출';
