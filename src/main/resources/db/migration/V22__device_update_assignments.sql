CREATE TABLE `device_update_assignments` (
  `namespace` varchar(200) NOT NULL,
  `device_id` char(36) NOT NULL,
  `correlation_id` varchar(256) NULL,
  `update_uuid` CHAR(36) NULL,
  `version` int NOT NULL,
  `served` BOOL NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (`namespace`, `device_id`, `version`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

CREATE TABLE `ecu_update_assignments` (
  `namespace` varchar(200) NOT NULL,
  `device_id` char(36) NOT NULL,
  `ecu_id` varchar(64) NOT NULL,
  `version` int NOT NULL,
  `filepath` varchar(4096) NOT NULL,
  `checksum` varchar(254) NOT NULL,
  `length` long NOT NULL,
  `uri` VARCHAR(254) NULL,
  `diff_format` ENUM('OSTREE', 'BINARY') NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (`namespace`, `device_id`, `ecu_id`, `version`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
