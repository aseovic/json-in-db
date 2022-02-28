#!/bin/bash

CLASSPATH=core/target/*:core/target/dependency/*:soda/target/*:soda/target/dependency/*:coherence/target/*:coherence/target/dependency/*

bin/ycsb $1 $2 -cp $CLASSPATH \
         -P ./workloads/$3 \
         -jvm-args "-Xmx12g -Xms12g -Dsun.net.inetaddr.ttl=0 -Dtangosol.coherence.distributed.localstorage=false -Dtangosol.pof.enabled=true -Dtangosol.coherence.ttl=0 -Dtangosol.coherence.localhost=127.0.0.1 -Djava.net.preferIPv4Stack=true" \
         -s -threads $THREADS \
         -p db.url=jdbc:oracle:thin:@${DB_NAME}?TNS_ADMIN=${TNS_ADMIN} \
         -p db.user=$DB_USER \
         -p db.password=$DB_PASSWORD \
         -p table=$DB_TABLE
