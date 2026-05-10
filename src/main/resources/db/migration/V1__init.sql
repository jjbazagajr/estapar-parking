CREATE TABLE sectors (
    id                      BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name                    VARCHAR(32)    NOT NULL UNIQUE,
    base_price              DECIMAL(10, 2) NOT NULL,
    max_capacity            INT            NOT NULL,
    open_hour               TIME           NULL,
    close_hour              TIME           NULL,
    duration_limit_minutes  INT            NULL
) ENGINE = InnoDB;

CREATE TABLE spots (
    id        BIGINT      NOT NULL PRIMARY KEY,
    sector    VARCHAR(32) NOT NULL,
    lat       DOUBLE      NOT NULL,
    lng       DOUBLE      NOT NULL,
    occupied  BOOLEAN     NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_spots_sector FOREIGN KEY (sector) REFERENCES sectors (name)
) ENGINE = InnoDB;

CREATE INDEX idx_spots_sector ON spots (sector);
CREATE INDEX idx_spots_latlng ON spots (lat, lng);

CREATE TABLE parking_sessions (
    id                  BIGINT         NOT NULL AUTO_INCREMENT PRIMARY KEY,
    license_plate       VARCHAR(16)    NOT NULL,
    sector              VARCHAR(32)    NULL,
    spot_id             BIGINT         NULL,
    entry_time          TIMESTAMP(3)   NOT NULL,
    parked_time         TIMESTAMP(3)   NULL,
    exit_time           TIMESTAMP(3)   NULL,
    price_multiplier    DECIMAL(4, 3)  NOT NULL,
    amount_charged      DECIMAL(10, 2) NULL,
    CONSTRAINT fk_sessions_sector FOREIGN KEY (sector) REFERENCES sectors (name),
    CONSTRAINT fk_sessions_spot   FOREIGN KEY (spot_id) REFERENCES spots (id)
) ENGINE = InnoDB;

CREATE INDEX idx_sessions_plate_open  ON parking_sessions (license_plate, exit_time);
CREATE INDEX idx_sessions_revenue     ON parking_sessions (sector, exit_time);
