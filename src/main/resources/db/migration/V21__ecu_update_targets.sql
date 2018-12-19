CREATE TABLE `ecu_update_targets` (
  `namespace` varchar(200) NOT NULL,
  `device_id` char(36) NOT NULL,
  `ecu_id` varchar(64) NOT NULL,
  `version` int NOT NULL,
  `filepath` varchar(4096) NOT NULL,
  `length` int NOT NULL,
  `checksum` varchar(254) NOT NULL,
  `uri` VARCHAR(254) NOT NULL,
  `diff_format` ENUM('OSTREE', 'BINARY') NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (`namespace`, `device_id`, `ecu_id`, `version`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
