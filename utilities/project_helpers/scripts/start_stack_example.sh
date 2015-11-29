#!/bin/bash
#Put start commands here

### nginx ###
echo "Starting nginx"
/etc/init.d/nginx start

### supervisor (shibboleth + fastcgi) ###
echo "Starting all supervised programs"
supervisorctl start all


### Postgres ###
echo "Starting postgres"
/etc/init.d/postgresql-9.4 start
### Tomcat ###
echo "Starting tomcat"
/etc/init.d/tomcat8 start
### Handle server ###
HANDLE_SERVER=/etc/init.d/handle-server
if [[ -r $HANDLE_SERVER ]]; then
    echo "Starting handle server";
    $HANDLE_SERVER start;
else
    echo "Handle server not present - ignoring start command";
fi

service tomcat8 status

now=$(date +"%T")
echo "Current time : $now"

### apache ###
#echo "Starting apache"
#apache2ctl start
### shibboleth ###
#echo "Starting shibboleth"
#/etc/init.d/shibboleth start
