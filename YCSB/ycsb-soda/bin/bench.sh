#!/bin/bash

cd /app

java $JAVA_OPTS \
      -cp $JAVA_CLASSPATH \
      com.yahoo.ycsb.Client \
      -db $CLIENT \
      -P ./workloads/$WORKLOAD \
      -s \
      -threads $THREADS \
      -p db.url=jdbc:oracle:thin:@$DB_NAME?TNS_ADMIN=/app/wallet/ \
      -p db.user=$DB_USER \
      -p db.password=$DB_PASSWORD \
      -p table=$DB_TABLE \
      -p soda.cpoolsz=$SODA_CPOOLSZ \
      -$COMMAND