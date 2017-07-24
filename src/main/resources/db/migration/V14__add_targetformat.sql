ALTER TABLE `multi_target_updates`
ADD COLUMN `target_format` ENUM('BINARY', 'OSTREE') NOT NULL DEFAULT 'OSTREE',
ADD COLUMN `generate_diff` BOOL NOT NULL DEFAULT false
;

ALTER TABLE `multi_target_updates`
MODIFY COLUMN `target_format` ENUM('BINARY', 'OSTREE') NOT NULL,
MODIFY COLUMN `generate_diff` BOOL NOT NULL
;

ALTER TABLE `device_update_targets`
DROP PRIMARY KEY,
ADD PRIMARY KEY (`version`, `device`)
;

ALTER TABLE `ecu_targets`
ADD COLUMN `diff_format` ENUM('OSTREE', 'BINARY') NULL
;

CREATE TABLE `diff_bs_diffs` (
  `namespace` varchar(200) NOT NULL,
  `id` char(36) NOT NULL,
  `from` varchar(254) NOT NULL,
  `to` varchar(254) NOT NULL,
  `status` ENUM('REQUESTED', 'GENERATED', 'FAILED') NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (`id`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

CREATE UNIQUE INDEX `diff_bs_diffs_unique_from_to` ON
`diff_bs_diffs` (`namespace`, `from`, `to`)
;

CREATE TABLE `diff_static_deltas` (
  `namespace` varchar(200) NOT NULL,
  `id` char(36) NOT NULL,
  `from` char(128) NOT NULL,
  `to` char(128) NOT NULL,
  `status` ENUM('REQUESTED', 'GENERATED', 'FAILED') NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (`id`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

CREATE UNIQUE INDEX `diff_static_deltas_unique_from_to` ON
`diff_static_deltas` (`namespace`, `from`, `to`)
;

CREATE TABLE `diff_bs_diff_infos` (
  `id` char(36) NOT NULL,
  `hash_method` char(20) NOT NULL,
  `hash` char(128) NOT NULL,
  `size` long NOT NULL,
  `uri` varchar(254) NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY(`id`),

  CONSTRAINT `diff_bs_diff_infos_id_fk`
  FOREIGN KEY (`id`)
  REFERENCES `diff_bs_diffs` (`id`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

CREATE TABLE `diff_static_delta_infos` (
  `id` char(36) NOT NULL,
  `hash_method` char(20) NOT NULL,
  `hash` char(128) NOT NULL,
  `size` long NOT NULL,
  `uri` varchar(254) NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY(`id`),

  CONSTRAINT `diff_static_delta_infos_id_fk`
  FOREIGN KEY (`id`)
  REFERENCES `diff_static_deltas` (`id`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
