-- test migration
CREATE TABLE IF NOT EXISTS t_one (id UInt32) ENGINE = MergeTree ORDER BY id;
