CREATE TABLE processed_manifests(
  `namespace` varchar(200) NOT NULL,
  `device` char(36) NOT NULL,
  `hash` char(64) NOT NULL,
  primary key (`namespace`, `device`)
);
