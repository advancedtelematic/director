CREATE TABLE `multi_target_update_diffs` (
  `id` char(36) NOT NULL,
  `hardware_identifier` char(200) NOT NULL,
  `diff_hash_method` char(20) NOT NULL,
  `diff_hash` char(128) NOT NULL,
  `diff_size` long NOT NULL,

  PRIMARY KEY (`id`, `hardware_identifier`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

ALTER TABLE `ecu_targets`
ADD COLUMN `diff_hash_method` char(20) NULL,
ADD COLUMN `diff_hash` char(128) NULL,
ADD COLUMN `diff_size` long NULL
;
