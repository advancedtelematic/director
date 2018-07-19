ALTER TABLE `current_images`
MODIFY COLUMN `length` long NOT NULL;

ALTER TABLE `ecu_targets`
MODIFY COLUMN `length` long NOT NULL;

CREATE TABLE `multi_target_updates` (
    `id` char(36) NOT NULL,
    `hardware_identifier` char(200) NOT NULL,
    `target` char(200) NOT NULL,
    `target_hash` char(128) NOT NULL,
    `hash_method` char(20) NOT NULL,
    `target_size` long NOT NULL,

    PRIMARY KEY (`id`, `hardware_identifier`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
