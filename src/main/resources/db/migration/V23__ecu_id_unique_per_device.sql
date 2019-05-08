ALTER TABLE `ecus`
DROP PRIMARY KEY,
ADD PRIMARY KEY (`namespace`, `device`, `ecu_serial`)
;
