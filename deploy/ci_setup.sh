#!/bin/bash

set -u

docker rm --force service_blueprint-mariadb || true

mkdir entrypoint.d/ || true

echo "
CREATE DATABASE service_blueprint;
GRANT ALL PRIVILEGES ON \`service_blueprint%\`.* TO 'service_blueprint'@'%';
FLUSH PRIVILEGES;
" > entrypoint.d/db_user.sql

docker run -d \
  --name service_blueprint-mariadb \
  -p 3306:3306 \
  -v $(pwd)/entrypoint.d:/docker-entrypoint-initdb.d \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_USER=service_blueprint \
  -e MYSQL_PASSWORD=service_blueprint \
  mariadb:10.1 \
  --character-set-server=utf8 --collation-server=utf8_unicode_ci \
  --max_connections=1000

function mysqladmin_alive {
    docker run \
           --rm \
           --link service_blueprint-mariadb \
           mariadb:10.1 \
           mysqladmin ping --protocol=TCP -h service_blueprint-mariadb -P 3306 -u root -proot
}

TRIES=60
TIMEOUT=1s

for t in `seq $TRIES`; do
    res=$(mysqladmin_alive || true)
    if [[ $res =~ "mysqld is alive" ]]; then
        echo "mysql is ready"
        exit 0
    else
        echo "Waiting for mariadb"
        sleep $TIMEOUT
    fi
done

exit -1

