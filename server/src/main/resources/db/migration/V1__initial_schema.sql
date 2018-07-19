ALTER DATABASE CHARACTER SET utf8 COLLATE utf8_unicode_ci;

CREATE TABLE `ecus` (
  `ecu_serial` varchar(64) NOT NULL,
  `device` char(36) NOT NULL,
  `namespace` varchar(200) NOT NULL,
  `primary` bool NOT NULL,
  `cryptographic_method` varchar(16) NOT NULL,
  `public_key` varchar(4096) NOT NULL,

  PRIMARY KEY (`ecu_serial`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

CREATE TABLE `current_images` (
  `ecu_serial` varchar(64) NOT NULL REFERENCES Ecu(ecu_serial),
  `filepath` varchar(4096) NOT NULL,
  `length` int NOT NULL,
  `checksum` varchar(254) NOT NULL,
  `attacks_detected` varchar(4096) NOT NULL,

  PRIMARY KEY (`ecu_serial`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

CREATE TABLE `repo_names` (
  `namespace` varchar(200) NOT NULL,
  `repo_id` char(36) NOT NULL,

  PRIMARY KEY (`namespace`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

CREATE TABLE `ecu_targets` (
  `version` int NOT NULL,
  `ecu_serial` varchar(64) NOT NULL REFERENCES ECU(ecu_serial),
  `filepath` varchar(4096) NOT NULL,
  `length` int NOT NULL,
  `checksum` varchar(254) NOT NULL,

  PRIMARY KEY (`version`, `ecu_serial`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

CREATE TABLE `device_targets` (
  `device` char(36) NOT NULL,
  `latest_scheduled_target` int NOT NULL,

  PRIMARY KEY (`device`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

CREATE TABLE `device_current_target` (
`device` char(36) NOT NULL,
`device_current_target` int NOT NULL,

PRIMARY KEY (`device`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

CREATE TABLE `file_cache` (
  `role` ENUM('ROOT', 'SNAPSHOT', 'TARGETS', 'TIMESTAMP') NOT NULL,
  `version` int NOT NULL,
  `device` char(36) NOT NULL,
  `file_entity` longtext NOT NULL,

  PRIMARY KEY (`role`, `version`, `device`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

CREATE TABLE `file_cache_requests` (
  `namespace` varchar(200) NOT NULL,
  `version` int NOT NULL,
  `device` char(36) NOT NULL,
  `status` ENUM('SUCCESS', 'ERROR', 'PENDING') NOT NULL,

  PRIMARY KEY (`version`, `device`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
