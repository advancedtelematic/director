DROP TABLE `device_update_assignments`;
ALTER TABLE `device_update_targets` RENAME TO `device_update_assignments`;

ALTER TABLE `device_update_assignments`
DROP PRIMARY KEY,
CHANGE `device` `device_id` char(36) NOT NULL,
ADD COLUMN `namespace` varchar(200) NOT NULL;

UPDATE `device_update_assignments` du
SET du.`namespace` = (select e.`namespace` FROM `ecus` e WHERE e.`device` = du.`device_id` limit 1);

ALTER TABLE `device_update_assignments`
ADD PRIMARY KEY (`namespace`, `device_id`, `version`);


DROP TABLE `ecu_update_assignments`;
ALTER TABLE `ecu_targets` RENAME TO `ecu_update_assignments`;

ALTER TABLE `ecu_update_assignments`
DROP PRIMARY KEY,
CHANGE `ecu_serial` `ecu_id` varchar(64) NOT NULL,
ADD COLUMN `device_id` char(36) NOT NULL;

UPDATE `ecu_update_assignments` eu
SET eu.`device_id` = (select e.`device` FROM `ecus` e WHERE e.`ecu_serial` = eu.`ecu_id` limit 1);

ALTER TABLE `ecu_update_assignments`
ADD PRIMARY KEY (`namespace`, `device_id`, `ecu_id`, `version`);
