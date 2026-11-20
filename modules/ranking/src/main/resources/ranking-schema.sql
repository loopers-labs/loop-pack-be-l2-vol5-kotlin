CREATE TABLE IF NOT EXISTS ranking_snapshots (
    snapshot_id VARCHAR(64) NOT NULL PRIMARY KEY,
    period_start DATE NOT NULL,
    period_end DATE NOT NULL,
    revision BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL,
    frozen_at DATETIME(6) NOT NULL,
    input_count BIGINT NOT NULL,
    product_count BIGINT NOT NULL,
    input_checksum CHAR(64) NOT NULL,
    result_checksum CHAR(64) NULL,
    UNIQUE KEY uk_ranking_revision (period_start, revision),
    UNIQUE KEY uk_ranking_period_snapshot (period_start, snapshot_id),
    CONSTRAINT ck_ranking_manifest CHECK (
        WEEKDAY(period_start) = 0 AND period_end = DATE_ADD(period_start, INTERVAL 7 DAY)
        AND revision > 0 AND input_count >= 0 AND product_count >= 0 AND product_count <= input_count
        AND ((status = 'FROZEN' AND result_checksum IS NULL) OR (status = 'COMPLETE' AND result_checksum IS NOT NULL))
    )
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ranking_snapshot_inputs (
    snapshot_id VARCHAR(64) NOT NULL,
    order_id BIGINT NOT NULL,
    product_id BIGINT NOT NULL,
    quantity BIGINT NOT NULL,
    confirmed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (snapshot_id, order_id, product_id),
    KEY idx_ranking_input_product (snapshot_id, product_id),
    CONSTRAINT fk_ranking_input_snapshot FOREIGN KEY (snapshot_id) REFERENCES ranking_snapshots(snapshot_id),
    CONSTRAINT ck_ranking_input CHECK (order_id > 0 AND product_id > 0 AND quantity > 0)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ranking_score_stages (
    snapshot_id VARCHAR(64) NOT NULL,
    product_id BIGINT NOT NULL,
    score BIGINT NOT NULL,
    PRIMARY KEY (snapshot_id, product_id),
    CONSTRAINT fk_ranking_stage_snapshot FOREIGN KEY (snapshot_id) REFERENCES ranking_snapshots(snapshot_id),
    CONSTRAINT ck_ranking_stage CHECK (product_id > 0 AND score > 0)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ranking_results (
    snapshot_id VARCHAR(64) NOT NULL,
    product_id BIGINT NOT NULL,
    score BIGINT NOT NULL,
    calculated_rank BIGINT NOT NULL,
    PRIMARY KEY (snapshot_id, product_id),
    UNIQUE KEY uk_ranking_result_rank (snapshot_id, calculated_rank),
    CONSTRAINT fk_ranking_result_snapshot FOREIGN KEY (snapshot_id) REFERENCES ranking_snapshots(snapshot_id),
    CONSTRAINT ck_ranking_result CHECK (product_id > 0 AND score > 0 AND calculated_rank > 0)
) ENGINE=InnoDB;

CREATE TABLE IF NOT EXISTS ranking_publications (
    period_start DATE NOT NULL PRIMARY KEY,
    snapshot_id VARCHAR(64) NULL,
    CONSTRAINT ck_ranking_publication CHECK (WEEKDAY(period_start) = 0),
    CONSTRAINT fk_ranking_publication_snapshot FOREIGN KEY (period_start, snapshot_id)
        REFERENCES ranking_snapshots(period_start, snapshot_id)
) ENGINE=InnoDB;
