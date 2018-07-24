CREATE TABLE `auto_updates` (
  `namespace` varchar(200) NOT NULL,
  `device` char(36) NOT NULL,
  `ecu_serial` varchar(64) NOT NULL REFERENCES ecus(ecu_serial),
  `target_name` varchar(100) NOT NULL
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
