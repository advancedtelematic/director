ALTER TABLE `ecus`
DROP PRIMARY KEY,
ADD PRIMARY KEY (`namespace`, `ecu_serial`)
;

ALTER TABLE `current_images`
ADD COLUMN `namespace` varchar(200) NULL;

UPDATE `current_images` ci
INNER JOIN `ecus` e
ON ci.ecu_serial = e.ecu_serial
SET ci.namespace = e.namespace
WHERE ci.namespace IS NULL;

ALTER TABLE `current_images`
MODIFY `namespace` varchar(200) NOT NULL,
DROP PRIMARY KEY,
ADD PRIMARY KEY (`namespace`, `ecu_serial`)
;

ALTER TABLE `ecu_targets`
ADD COLUMN `namespace` varchar(200) NULL;

UPDATE `ecu_targets` et
INNER JOIN `ecus` e
ON et.ecu_serial = e.ecu_serial
SET et.namespace = e.namespace
WHERE et.namespace IS NULL;

ALTER TABLE `ecu_targets`
MODIFY `namespace` varchar(200) NOT NULL,
DROP PRIMARY KEY,
ADD PRIMARY KEY (`namespace`, `version`, `ecu_serial`)
;
