CREATE TABLE `ecu_targets` (
  `namespace` varchar(200) NOT NULL,
  `id` char(36) NOT NULL,
  `filename` varchar(4096) NOT NULL,
  `length` mediumtext NOT NULL,
  `checksum` varchar(254) NOT NULL,
  `sha256` char(64) NOT NULL,
  `uri` varchar(255) NULL,
  `created_at` datetime(3) NOT NULL DEFAULT current_timestamp(3),
  `updated_at` datetime(3) NOT NULL DEFAULT current_timestamp(3) ON UPDATE current_timestamp(3),
  PRIMARY KEY (`id`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;


CREATE TABLE `ecus` (
  `namespace` varchar(200) NOT NULL,
  `ecu_serial` varchar(64) NOT NULL,
  `device_id` char(36) NOT NULL,
  `public_key` varchar(4096) NOT NULL,
  `hardware_identifier` varchar(200) NOT NULL,
  `current_target` CHAR(36) DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT current_timestamp(3),
  `updated_at` datetime(3) NOT NULL DEFAULT current_timestamp(3) ON UPDATE current_timestamp(3),
  PRIMARY KEY (`namespace`,`ecu_serial`),
  CONSTRAINT `ecu_current_target_fk` FOREIGN KEY (`current_target`) REFERENCES ecu_targets(`id`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
;

CREATE TABLE `devices` (
  `namespace` varchar(200) NOT NULL,
  `id` char(36) NOT NULL,
  `primary_ecu_id` varchar(64) NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT current_timestamp(3),
  `updated_at` datetime(3) NOT NULL DEFAULT current_timestamp(3) ON UPDATE current_timestamp(3),
  PRIMARY KEY (`id`),
  CONSTRAINT `devices_unique_id` UNIQUE (`id`),
  CONSTRAINT `primary_ecu_fk` FOREIGN KEY (`namespace`, `primary_ecu_id`) REFERENCES ecus(`namespace`, `ecu_serial`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci
;


CREATE TABLE `signed_roles` (
  `role` enum('ROOT','SNAPSHOT','TARGETS','TIMESTAMP') NOT NULL,
  `version` int(11) NOT NULL,
  `device_id` char(36) NOT NULL,
  `checksum` varchar(254) NOT NULL,
  `length` bigint(20) NOT NULL,
  `content` longtext NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT current_timestamp(3),
  `updated_at` datetime(3) NOT NULL DEFAULT current_timestamp(3) ON UPDATE current_timestamp(3),
  `expires_at` datetime(3) NOT NULL,
  PRIMARY KEY (`role`,`version`,`device_id`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

CREATE TABLE `hardware_updates` (
  `namespace` varchar(200) NOT NULL,
  `id` char(36) NOT NULL,
  `hardware_identifier` char(200) NOT NULL,
  `to_target_id` char(36) NOT NULL REFERENCES ecu_targets(id),
  `from_target_id` char(36) DEFAULT NULL REFERENCES ecu_targets(id),
  `target_format` enum('BINARY','OSTREE') NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT current_timestamp(3),
  `updated_at` datetime(3) NOT NULL DEFAULT current_timestamp(3) ON UPDATE current_timestamp(3),
  PRIMARY KEY (`id`,`hardware_identifier`),
  CONSTRAINT `hardware_updates_to_target_fk` FOREIGN KEY (`to_target_id`) REFERENCES ecu_targets(`id`),
  CONSTRAINT `hardware_updates_from_target_fk` FOREIGN KEY (`from_target_id`) REFERENCES ecu_targets(`id`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;


CREATE TABLE `repo_namespaces` (
  `namespace` varchar(200) NOT NULL,
  `repo_id` char(36) NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT current_timestamp(3),
  `updated_at` datetime(3) NOT NULL DEFAULT current_timestamp(3) ON UPDATE current_timestamp(3),
  PRIMARY KEY (`namespace`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;


CREATE TABLE `assignments` (
  `namespace` varchar(200) NOT NULL,
  `device_id` char(36) NOT NULL,
  `ecu_serial` varchar(64) NOT NULL,
  `ecu_target_id` char(36) NOT NULL,
  `correlation_id` varchar(255) NOT NULL,
  `in_flight` BOOLEAN NOT NULL,

  `created_at` datetime(3) NOT NULL DEFAULT current_timestamp(3),
  `updated_at` datetime(3) NOT NULL DEFAULT current_timestamp(3) ON UPDATE current_timestamp(3),

  CONSTRAINT `assignments_ecu_fk` FOREIGN KEY (`ecu_target_id`) REFERENCES ecu_targets(`id`),
  CONSTRAINT `assignments_ecu_target_fk` FOREIGN KEY (`namespace`, `ecu_serial`) REFERENCES ecus(`namespace`, `ecu_serial`),
  CONSTRAINT `assignments_device_fk` FOREIGN KEY (`device_id`) REFERENCES devices(`id`),

  INDEX `assignments_device_id_idx` (`device_id`),

  PRIMARY KEY (`device_id`, `ecu_serial`)

) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
;

CREATE TABLE `processed_assignments` (
  `namespace` varchar(200) NOT NULL,
  `device_id` char(36) NOT NULL,
  `ecu_serial` varchar(64) NOT NULL,
  `ecu_target_id` char(36) NOT NULL,
  `correlation_id` varchar(255) NOT NULL,
  `in_flight` BOOLEAN NOT NULL,

  `created_at` datetime(3) NOT NULL DEFAULT current_timestamp(3),
  `updated_at` datetime(3) NOT NULL DEFAULT current_timestamp(3) ON UPDATE current_timestamp(3),

  INDEX `processed_assignments_ns_device_id_idx` (`namespace`, `device_id`),

  CONSTRAINT `p_assignments_ecu_fk` FOREIGN KEY (`ecu_target_id`) REFERENCES ecu_targets(`id`),
  CONSTRAINT `p_assignments_ecu_target_fk` FOREIGN KEY (`namespace`, `ecu_serial`) REFERENCES ecus(`namespace`, `ecu_serial`),
  CONSTRAINT `p_assignments_device_fk` FOREIGN KEY (`device_id`) REFERENCES devices(`id`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
;
