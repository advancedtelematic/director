ALTER TABLE `file_cache_requests`
ADD COLUMN `timestamp_version` INT NULL;

UPDATE `file_cache_requests` fcr
SET fcr.timestamp_version = fcr.version
WHERE fcr.timestamp_version IS NULL;

ALTER TABLE `file_cache_requests`
CHANGE `version` `target_version` INT NOT NULL,
MODIFY `timestamp_version` INT NOT NULL,
DROP PRIMARY KEY,
ADD PRIMARY KEY (`timestamp_version`, `device`)
;
