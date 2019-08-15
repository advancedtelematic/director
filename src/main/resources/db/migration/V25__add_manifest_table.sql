CREATE TABLE `device_manifests` (
  `device_id` char(36) NOT NULL,
  `success` BOOLEAN NOT NULL,
  `payload` longtext NOT NULL,
  `message` varchar(255) NOT NULL,
  `received_at` DATETIME(3) NOT NULL,
  `created_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at` DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

  PRIMARY KEY (`device_id`, `success`),
  INDEX `device_manifests_device_id` (`device_id`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
