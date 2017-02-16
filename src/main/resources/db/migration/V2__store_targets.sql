ALTER TABLE `CurrentImage`
  DROP COLUMN `sha256`,
  DROP COLUMN `sha512`,
  ADD COLUMN `checksum` varchar(254) NOT NULL,
  ADD COLUMN `attacks_detected` varchar(4096)
;

CREATE TABLE `RepoNameMapping` (
  `namespace` varchar(200) NOT NULL,
  `repoName` varchar(36) NOT NULL,

  PRIMARY KEY (`namespace`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

CREATE TABLE `EcuTarget` (
  `version` int NOT NULL,
  `ecu_serial` varchar(64) NOT NULL REFERENCES ECU(ecu_serial),
  `filepath` varchar(4096) NOT NULL,
  `length` int NOT NULL,
  `checksum` varchar(254) NOT NULL,

  PRIMARY KEY (`version`, `ecu_serial`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

CREATE TABLE `Snapshot` (
  `device` varchar(36) NOT NULL,
  `device_version` int NOT NULL,
  `target_version` int NOT NULL,

  PRIMARY KEY (`device`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

CREATE TABLE `FileCache` (
  `role` ENUM('ROOT', 'SNAPSHOT', 'TARGETS', 'TIMESTAMP') NOT NULL,
  `version` int NOT NULL,
  `device` varchar(36) NOT NULL,
  `fileEntity` longtext NOT NULL,

  PRIMARY KEY (`role`, `version`, `device`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

CREATE TABLE `FileCacheRequest` (
  `namespace` varchar(200) NOT NULL,
  `version` int NOT NULL,
  `device` varchar(36) NOT NULL,
  `status` ENUM('SUCCESS', 'ERROR', 'PENDING') NOT NULL,

  PRIMARY KEY (`version`, `device`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
