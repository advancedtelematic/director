ALTER TABLE `multi_target_updates`
ADD COLUMN `from_target` CHAR(200) NULL,
ADD COLUMN `from_target_hash` CHAR(128) NULL,
ADD COLUMN `from_hash_method` CHAR(20) NULL,
ADD COLUMN `from_target_size` LONG NULL
;
