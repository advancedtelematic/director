CREATE TABLE `multi_target_update_deltas` (
  `id` char(36) NOT NULL,
  `hardware_identifier` char(200) NOT NULL,
  `delta_hash_method` char(20) NOT NULL,
  `delta_hash` char(128) NOT NULL,
  `delta_size` long NOT NULL,

  PRIMARY KEY (`id`, `hardware_identifier`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

ALTER TABLE `ecu_targets`
ADD COLUMN `delta_hash_method` char(20) NULL,
ADD COLUMN `delta_hash` char(128) NULL,
ADD COLUMN `delta_size` long NULL
;
