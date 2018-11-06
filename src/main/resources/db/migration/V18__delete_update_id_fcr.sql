ALTER TABLE `file_cache_requests`
CHANGE COLUMN `update_uuid` `_deleted__update_uuid` char(36) NULL;
