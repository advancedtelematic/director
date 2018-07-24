ALTER TABLE `file_cache`
ADD COLUMN `expires` DATETIME(3) NULL;

UPDATE `file_cache` fc
SET fc.expires = DATE_ADD(fc.created_at, INTERVAL 30 DAY)
WHERE fc.expires IS NULL;

ALTER TABLE `file_cache`
MODIFY `expires` DATETIME(3) NOT NULL;

ALTER TABLE `file_cache_requests`
ADD COLUMN `update_uuid` char(36) NULL;
