#!/bin/bash

set -u

docker rm --force director-mariadb || true

mkdir director_entrypoint.d/ || true

echo "
CREATE DATABASE director;
GRANT ALL PRIVILEGES ON \`director%\`.* TO 'director'@'%';
FLUSH PRIVILEGES;
" > director_entrypoint.d/db_user.sql

MYSQL_PORT=${MYSQL_PORT-3306}

docker run -d \
  --name director-mariadb \
  -p $MYSQL_PORT:3306 \
  -v $(pwd)/director_entrypoint.d:/docker-entrypoint-initdb.d \
  -e MYSQL_ROOT_PASSWORD=root \
  -e MYSQL_USER=director \
  -e MYSQL_PASSWORD=director \
  mariadb:10.2 \
  --character-set-server=utf8 --collation-server=utf8_unicode_ci \
  --max_connections=1000

function mysqladmin_alive {
    docker run \
           --rm \
           --link director-mariadb \
           mariadb:10.2 \
           mysqladmin ping --protocol=TCP -h director-mariadb -P 3306 -u root -proot
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

