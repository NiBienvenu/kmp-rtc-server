-- =============================================================================
-- kmp-rtc-server: schema partage entre Asterisk et Ktor
-- Execute par l'entrypoint (idempotent). La base et les utilisateurs sont crees avant.
-- Les autres tables (users, sip_accounts, device_tokens, webhook_events) sont creees par Ktor.
-- =============================================================================

-- CDR: ecrite par Asterisk (cdr_adaptive_odbc), lue par Ktor (GET /calls/history, /admin/stats)
CREATE TABLE IF NOT EXISTS cdr (
    id           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT PRIMARY KEY,
    calldate     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    clid         VARCHAR(80)  NOT NULL DEFAULT '',
    src          VARCHAR(80)  NOT NULL DEFAULT '',
    dst          VARCHAR(80)  NOT NULL DEFAULT '',
    dcontext     VARCHAR(80)  NOT NULL DEFAULT '',
    channel      VARCHAR(80)  NOT NULL DEFAULT '',
    dstchannel   VARCHAR(80)  NOT NULL DEFAULT '',
    lastapp      VARCHAR(80)  NOT NULL DEFAULT '',
    lastdata     VARCHAR(255) NOT NULL DEFAULT '',
    duration     INT          NOT NULL DEFAULT 0,
    billsec      INT          NOT NULL DEFAULT 0,
    disposition  VARCHAR(45)  NOT NULL DEFAULT '',
    amaflags     INT          NOT NULL DEFAULT 0,
    accountcode  VARCHAR(80)  NOT NULL DEFAULT '',
    uniqueid     VARCHAR(150) NOT NULL DEFAULT '',
    userfield    VARCHAR(255) NOT NULL DEFAULT '',
    peeraccount  VARCHAR(80)  NOT NULL DEFAULT '',
    linkedid     VARCHAR(150) NOT NULL DEFAULT '',
    sequence     INT          NOT NULL DEFAULT 0,
    recordingfile VARCHAR(255) NOT NULL DEFAULT '',
    KEY idx_cdr_calldate (calldate),
    KEY idx_cdr_src (src),
    KEY idx_cdr_dst (dst),
    KEY idx_cdr_uniqueid (uniqueid),
    KEY idx_cdr_linkedid (linkedid)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Salles de conference: gerees par Ktor (POST /rooms), lues par Asterisk (func_odbc ODBC_ROOM)
-- Doit rester identique a ConferenceRoomTable (server/.../models/Rtc.kt)
CREATE TABLE IF NOT EXISTS conference_rooms (
    id               VARCHAR(36)  NOT NULL PRIMARY KEY,
    room_number      VARCHAR(16)  NOT NULL,
    name             VARCHAR(100) NOT NULL,
    pin              VARCHAR(16)  NULL,
    max_participants INT          NOT NULL DEFAULT 20,
    video            BOOLEAN      NOT NULL DEFAULT TRUE,
    owner_id         VARCHAR(36)  NOT NULL,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       VARCHAR(50)  NOT NULL DEFAULT '',
    CONSTRAINT conference_rooms_room_number_unique UNIQUE (room_number)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Mise a jour d'une base existante (ajout de la colonne des enregistrements)
ALTER TABLE cdr ADD COLUMN IF NOT EXISTS recordingfile VARCHAR(255) NOT NULL DEFAULT '';
