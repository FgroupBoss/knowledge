-- P1-8 对账批次与单条处置
CREATE TABLE reconciliation_run (
  id            CHAR(36)     NOT NULL,
  type          VARCHAR(32)  NOT NULL,
  status        VARCHAR(16)  NOT NULL,
  started_at    DATETIME(3)  NOT NULL,
  finished_at   DATETIME(3)  NULL,
  counts_json   JSON         NULL,
  evidence_path VARCHAR(512) NULL,
  created_at    DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_recon_type_time (type, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE reconciliation_item (
  id          CHAR(36)     NOT NULL,
  run_id      CHAR(36)     NOT NULL,
  type        VARCHAR(32)  NOT NULL,
  resource_id VARCHAR(128) NOT NULL,
  action      VARCHAR(64)  NOT NULL,
  status      VARCHAR(16)  NOT NULL,
  detail      VARCHAR(1024) NULL,
  created_at  DATETIME(3)  NOT NULL,
  PRIMARY KEY (id),
  KEY idx_recon_item_run (run_id),
  KEY idx_recon_item_resource (type, resource_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
