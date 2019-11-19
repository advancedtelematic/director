-- repo_namespaces

INSERT IGNORE director2.repo_namespaces SELECT * FROM director.repo_names;

-- ecu_targets

insert into director2.ecu_targets (namespace,id,filename,length,checksum,sha256,uri,created_at,updated_at)
select namespace,uuid(),filepath,length,checksum,json_unquote(json_extract(checksum, '$.hash')),NULL,created_at,updated_at
from director.current_images c
where not exists (select * from director2.ecu_targets t where t.namespace = c.namespace and t.filename = c.filepath
                                and t.length = c.length and t.checksum = c.checksum);

-- ecus

insert ignore into director2.ecus (namespace,ecu_serial,device_id,public_key,hardware_identifier,current_target,created_at,updated_at)
select e.namespace,e.ecu_serial,device,public_key,hardware_identifier,t.`id`,e.created_at,e.updated_at
from director.ecus e
join director.current_images c on e.ecu_serial = c.ecu_serial
join director2.ecu_targets t on t.namespace = e.namespace AND t.checksum = c.checksum
                             AND t.filename = c.filepath AND t.created_at = c.created_at;

-- devices <- ecus

insert ignore into director2.devices (namespace,id,primary_ecu_id,created_at,updated_at)
select namespace,device,ecu_serial,created_at,updated_at
from director.ecus e
where e.`primary`=1;
