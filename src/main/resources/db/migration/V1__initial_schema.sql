ALTER DATABASE CHARACTER SET utf8 COLLATE utf8_unicode_ci;

CREATE TABLE `Ecu` (
  `ecu_serial` varchar(64) NOT NULL,
  `device` varchar(36) NOT NULL,
  `namespace` varchar(200) NOT NULL,
  `primary` bool NOT NULL,
  `cryptographic_method` varchar(16) NOT NULL,
  `public_key` varchar(4096) NOT NULL,

  PRIMARY KEY (`ecu_serial`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;

CREATE TABLE `CurrentImage` (
  `ecu_serial` varchar(64) NOT NULL REFERENCES Ecu(ecu_serial),
  `filepath` varchar(4096) NOT NULL,
  `length` int NOT NULL,
  `sha256` varchar(64) NOT NULL,
  `sha512` varchar(128) NOT NULL,

  PRIMARY KEY (`ecu_serial`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
