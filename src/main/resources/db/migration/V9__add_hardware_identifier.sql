ALTER TABLE `ecus`
ADD `hardware_identifier` VARCHAR(200) NULL;

UPDATE `ecus` e
SET e.hardware_identifier = e.ecu_serial
WHERE e.hardware_identifier IS NULL;

ALTER TABLE `ecus`
MODIFY `hardware_identifier` VARCHAR(200) NOT NULL;

CREATE TABLE `update_types` (
  `update_id` char(36) NOT NULL,
  `update_type` ENUM('OLD_STYLE_CAMPAIGN', 'MULTI_TARGET_UPDATE') NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (`update_id`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

CREATE TABLE `launched_multi_target_updates` (
  `device` char(36) NOT NULL,
  `update_id` char(36) NOT NULL,
  `timestamp_version` INT NOT NULL,
  `status` ENUM('Pending', 'InFlight', 'Canceled', 'Failed', 'Finished') NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (`device`, `update_id`, `timestamp_version`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
