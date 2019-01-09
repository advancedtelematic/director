create table `ecus_rsa_keys` as
select namespace, ecu_serial, cryptographic_method, public_key from `ecus`
;

alter table `ecus` drop column `cryptographic_method`
;
