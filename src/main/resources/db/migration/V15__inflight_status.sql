ALTER TABLE `device_update_targets`
ADD COLUMN `served` BOOL NOT NULL DEFAULT true
;

ALTER TABLE `device_update_targets`
MODIFY COLUMN `served` BOOL NOT NULL
;
