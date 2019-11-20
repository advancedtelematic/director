SET @@sql_mode = CONCAT(@@sql_mode, ',', 'ONLY_FULL_GROUP_BY');

create unique index if not exists ecu_targets_file_idx on director2.ecu_targets (namespace, filename(500), checksum);

create index if not exists ecu_serial_idx on director.ecus (`ecu_serial`);

-- repo_namespaces

INSERT director2.repo_namespaces
SELECT * FROM director.repo_names v1
ON DUPLICATE KEY UPDATE repo_id = v1.repo_id, created_at = v1.created_at, updated_at = v1.updated_at;

-- ecu_targets

insert into director2.ecu_targets (namespace,id,filename,length,checksum,sha256,uri,created_at,updated_at)
select namespace,uuid(),filepath,length,checksum,json_unquote(json_extract(checksum, '$.hash')),NULL,max(created_at),max(updated_at) -- does that select created_at/updated_at combos from different rows?
from director.current_images c
where not exists (select * from director2.ecu_targets t where t.namespace = c.namespace and t.filename = c.filepath
                                and t.checksum = c.checksum)
group by namespace,filepath,length,checksum;

-- ecus

insert into director2.ecus (namespace,ecu_serial,device_id,public_key,hardware_identifier,current_target,created_at,updated_at)
select e.namespace,e.ecu_serial,device,public_key,hardware_identifier,t.id,e.created_at,e.updated_at
from director.ecus e
join (select ecu_serial,max(updated_at) max_updated_at from director.ecus group by ecu_serial) e2 on e.ecu_serial = e2.ecu_serial and e.updated_at = max_updated_at
join director.current_images c on e.namespace = c.namespace and e.ecu_serial = c.ecu_serial
join director2.ecu_targets t on t.namespace = e.namespace AND t.filename = c.filepath AND t.checksum = c.checksum
on duplicate key update device_id = e.device, public_key = e.public_key, hardware_identifier = e.hardware_identifier, current_target = t.id, created_at = e.created_at, updated_at = e.updated_at;

-- devices <- ecus

insert into director2.devices (namespace,id,primary_ecu_id,created_at,updated_at)
select namespace,device,ecu_serial,created_at,updated_at
from director.ecus e
where e.primary = 1 and exists (select * from director2.ecus e2 where e.namespace = e2.namespace and e.ecu_serial = e2.ecu_serial)
on duplicate key update primary_ecu_id = e.ecu_serial, created_at = e.created_at, updated_at = e.updated_at;
