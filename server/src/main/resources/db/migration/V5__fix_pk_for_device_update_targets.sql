ALTER TABLE `device_update_targets`
DROP PRIMARY KEY,
ADD PRIMARY KEY (`device`, `version`)
;
