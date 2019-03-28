ALTER TABLE multi_target_updates ADD COLUMN target_uri VARCHAR(255) NULL;

ALTER TABLE multi_target_updates ADD COLUMN from_target_uri VARCHAR(255) NULL;

ALTER TABLE ecu_targets MODIFY uri VARCHAR(255) NULL;
