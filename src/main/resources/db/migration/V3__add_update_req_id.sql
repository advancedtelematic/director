ALTER TABLE `ecu_targets`
ADD COLUMN `uri` VARCHAR(254) NOT NULL;

ALTER TABLE `device_targets`
RENAME TO `device_update_targets`;

ALTER TABLE `device_update_targets`
ADD COLUMN `update` CHAR(36) NULL,
CHANGE `latest_scheduled_target` `version` int NOT NULL;

