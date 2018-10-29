ALTER TABLE `device_update_targets`
ADD COLUMN `correlation_id` varchar(256) NULL;

UPDATE `device_update_targets`
SET correlation_id = CONCAT('urn:here-ota:mtu:', update_uuid)
WHERE update_uuid IS NOT NULL;
