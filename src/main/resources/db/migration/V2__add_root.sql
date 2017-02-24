CREATE TABLE `root_files` (
`namespace` varchar(200) NOT NULL,
`root_file` longtext NOT NULL,

PRIMARY KEY (`namespace`)
) DEFAULT CHARSET=utf8 COLLATE=utf8_unicode_ci;
