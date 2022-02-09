#!/bin/bash

CLASSPATH=core/target/*:core/target/dependency/*:soda/target/*:soda/target/dependency/*:coherence/target/*:coherence/target/dependency/*

bin/ycsb $1 $2 -cp $CLASSPATH \
         -P ./workloads/$3 \
         -jvm-args "-Xmx30g -Xms30g -Dsun.net.inetaddr.ttl=0" \
         -s -threads $THREADS \
         -p db.url=jdbc:oracle:thin:@${DB_NAME}?TNS_ADMIN=${TNS_ADMIN} \
         -p db.user=$DB_USER \
         -p db.password=$DB_PASSWORD \
         -p table=$DB_TABLE
 
