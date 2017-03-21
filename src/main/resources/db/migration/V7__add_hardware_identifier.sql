ALTER TABLE `ecus`
ADD `hardware_identifier` VARCHAR(64) NULL;

UPDATE `ecus` e
SET e.hardware_identifier = e.ecu_serial
WHERE e.hardware_identifier IS NULL;

ALTER TABLE `ecus`
MODIFY `hardware_identifier` VARCHAR(64) NOT NULL;
