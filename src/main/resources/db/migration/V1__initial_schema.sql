--
-- Table structure for table `auto_updates`
--

CREATE TABLE `auto_updates` (
  `namespace` varchar(200) COLLATE utf8_unicode_ci NOT NULL,
  `device` char(36) COLLATE utf8_unicode_ci NOT NULL,
  `ecu_serial` varchar(64) COLLATE utf8_unicode_ci NOT NULL,
  `target_name` varchar(100) COLLATE utf8_unicode_ci NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;


CREATE TABLE `current_images` (
  `ecu_serial` varchar(64) COLLATE utf8_unicode_ci NOT NULL,
  `filepath` varchar(4096) COLLATE utf8_unicode_ci NOT NULL,
  `length` mediumtext COLLATE utf8_unicode_ci NOT NULL,
  `checksum` varchar(254) COLLATE utf8_unicode_ci NOT NULL,
  `attacks_detected` varchar(4096) COLLATE utf8_unicode_ci NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT current_timestamp(3),
  `updated_at` datetime(3) NOT NULL DEFAULT current_timestamp(3) ON UPDATE current_timestamp(3),
  `namespace` varchar(200) COLLATE utf8_unicode_ci NOT NULL,
  PRIMARY KEY (`namespace`,`ecu_serial`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;


--
-- Table structure for table `device_current_target`
--

CREATE TABLE `device_current_target` (
  `device` char(36) COLLATE utf8_unicode_ci NOT NULL,
  `device_current_target` int(11) NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT current_timestamp(3),
  `updated_at` datetime(3) NOT NULL DEFAULT current_timestamp(3) ON UPDATE current_timestamp(3),
  PRIMARY KEY (`device`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

--
-- Table structure for table `device_update_targets`
--

CREATE TABLE `device_update_targets` (
  `device` char(36) COLLATE utf8_unicode_ci NOT NULL,
  `version` int(11) NOT NULL,
  `update_uuid` char(36) COLLATE utf8_unicode_ci DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT current_timestamp(3),
  `updated_at` datetime(3) NOT NULL DEFAULT current_timestamp(3) ON UPDATE current_timestamp(3),
  `served` tinyint(1) NOT NULL,
  `correlation_id` varchar(256) COLLATE utf8_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`version`,`device`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

CREATE TABLE `ecu_targets` (
  `version` int(11) NOT NULL,
  `ecu_serial` varchar(64) COLLATE utf8_unicode_ci NOT NULL,
  `filepath` varchar(4096) COLLATE utf8_unicode_ci NOT NULL,
  `length` mediumtext COLLATE utf8_unicode_ci NOT NULL,
  `checksum` varchar(254) COLLATE utf8_unicode_ci NOT NULL,
  `uri` varchar(255) COLLATE utf8_unicode_ci DEFAULT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT current_timestamp(3),
  `updated_at` datetime(3) NOT NULL DEFAULT current_timestamp(3) ON UPDATE current_timestamp(3),
  `namespace` varchar(200) COLLATE utf8_unicode_ci NOT NULL,
  `diff_format` enum('OSTREE','BINARY') COLLATE utf8_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`namespace`,`version`,`ecu_serial`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

--
-- Table structure for table `ecus`
--

CREATE TABLE `ecus` (
  `ecu_serial` varchar(64) COLLATE utf8_unicode_ci NOT NULL,
  `device` char(36) COLLATE utf8_unicode_ci NOT NULL,
  `namespace` varchar(200) COLLATE utf8_unicode_ci NOT NULL,
  `primary` tinyint(1) NOT NULL,
  `public_key` varchar(4096) COLLATE utf8_unicode_ci NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT current_timestamp(3),
  `updated_at` datetime(3) NOT NULL DEFAULT current_timestamp(3) ON UPDATE current_timestamp(3),
  `hardware_identifier` varchar(200) COLLATE utf8_unicode_ci NOT NULL,
  PRIMARY KEY (`namespace`,`ecu_serial`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

--
-- Table structure for table `ecus_rsa_keys`
--

CREATE TABLE `ecus_rsa_keys` (
  `namespace` varchar(200) COLLATE utf8_unicode_ci NOT NULL,
  `ecu_serial` varchar(64) COLLATE utf8_unicode_ci NOT NULL,
  `cryptographic_method` varchar(16) COLLATE utf8_unicode_ci NOT NULL,
  `public_key` varchar(4096) COLLATE utf8_unicode_ci NOT NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;


--
-- Table structure for table `file_cache`
--

CREATE TABLE `file_cache` (
  `role` enum('ROOT','SNAPSHOT','TARGETS','TIMESTAMP') COLLATE utf8_unicode_ci NOT NULL,
  `version` int(11) NOT NULL,
  `device` char(36) COLLATE utf8_unicode_ci NOT NULL,
  `file_entity` longtext COLLATE utf8_unicode_ci NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT current_timestamp(3),
  `updated_at` datetime(3) NOT NULL DEFAULT current_timestamp(3) ON UPDATE current_timestamp(3),
  `expires` datetime(3) NOT NULL,
  PRIMARY KEY (`role`,`version`,`device`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

--
-- Table structure for table `file_cache_requests`
--

CREATE TABLE `file_cache_requests` (
  `namespace` varchar(200) COLLATE utf8_unicode_ci NOT NULL,
  `target_version` int(11) NOT NULL,
  `device` char(36) COLLATE utf8_unicode_ci NOT NULL,
  `status` enum('SUCCESS','ERROR','PENDING') COLLATE utf8_unicode_ci NOT NULL,
  `timestamp_version` int(11) NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT current_timestamp(3),
  `updated_at` datetime(3) NOT NULL DEFAULT current_timestamp(3) ON UPDATE current_timestamp(3),
  `_deleted__update_uuid` char(36) COLLATE utf8_unicode_ci DEFAULT NULL,
  `correlation_id` varchar(256) COLLATE utf8_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`timestamp_version`,`device`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;


--
-- Table structure for table `multi_target_updates`
--

CREATE TABLE `multi_target_updates` (
  `id` char(36) COLLATE utf8_unicode_ci NOT NULL,
  `hardware_identifier` char(200) COLLATE utf8_unicode_ci NOT NULL,
  `target` char(200) COLLATE utf8_unicode_ci NOT NULL,
  `target_hash` char(128) COLLATE utf8_unicode_ci NOT NULL,
  `hash_method` char(20) COLLATE utf8_unicode_ci NOT NULL,
  `target_size` mediumtext COLLATE utf8_unicode_ci NOT NULL,
  `namespace` varchar(200) COLLATE utf8_unicode_ci NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT current_timestamp(3),
  `updated_at` datetime(3) NOT NULL DEFAULT current_timestamp(3) ON UPDATE current_timestamp(3),
  `from_target` char(200) COLLATE utf8_unicode_ci DEFAULT NULL,
  `from_target_hash` char(128) COLLATE utf8_unicode_ci DEFAULT NULL,
  `from_hash_method` char(20) COLLATE utf8_unicode_ci DEFAULT NULL,
  `from_target_size` mediumtext COLLATE utf8_unicode_ci DEFAULT NULL,
  `target_format` enum('BINARY','OSTREE') COLLATE utf8_unicode_ci NOT NULL,
  `generate_diff` tinyint(1) NOT NULL,
  `target_uri` varchar(255) COLLATE utf8_unicode_ci DEFAULT NULL,
  `from_target_uri` varchar(255) COLLATE utf8_unicode_ci DEFAULT NULL,
  PRIMARY KEY (`id`,`hardware_identifier`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;


--
-- Table structure for table `repo_names`
--

CREATE TABLE `repo_names` (
  `namespace` varchar(200) COLLATE utf8_unicode_ci NOT NULL,
  `repo_id` char(36) COLLATE utf8_unicode_ci NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT current_timestamp(3),
  `updated_at` datetime(3) NOT NULL DEFAULT current_timestamp(3) ON UPDATE current_timestamp(3),
  PRIMARY KEY (`namespace`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

--
-- Table structure for table `root_files`
--

CREATE TABLE `root_files` (
  `namespace` varchar(200) COLLATE utf8_unicode_ci NOT NULL,
  `root_file` longtext COLLATE utf8_unicode_ci NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT current_timestamp(3),
  `updated_at` datetime(3) NOT NULL DEFAULT current_timestamp(3) ON UPDATE current_timestamp(3),
  PRIMARY KEY (`namespace`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;


--
-- Table structure for table `update_types`
--

CREATE TABLE `update_types` (
  `update_id` char(36) COLLATE utf8_unicode_ci NOT NULL,
  `update_type` enum('OLD_STYLE_CAMPAIGN','MULTI_TARGET_UPDATE') COLLATE utf8_unicode_ci NOT NULL,
  `created_at` datetime(3) NOT NULL DEFAULT current_timestamp(3),
  `updated_at` datetime(3) NOT NULL DEFAULT current_timestamp(3) ON UPDATE current_timestamp(3),
  PRIMARY KEY (`update_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
