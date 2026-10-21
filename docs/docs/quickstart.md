# Download
***
- Download binary distro: [https://github.com/zendesk/maxwell/releases/download/v1.46.0/maxwell-1.46.0.tar.gz](https://github.com/zendesk/maxwell/releases/download/v1.46.0/maxwell-1.46.0.tar.gz)
- Sources and bug tracking is available on github: [https://github.com/zendesk/maxwell](https://github.com/zendesk/maxwell)

**curl**:
```
curl -sLo - https://github.com/zendesk/maxwell/releases/download/v1.46.0/maxwell-1.46.0.tar.gz \
       | tar zxvf -
cd maxwell-1.46.0
```

**docker**:

```
docker pull osheroff/maxwell
```

**homebrew**:

```
brew install maxwell
```

# Configure Mysql
***

```

# /etc/my.cnf

[mysqld]
# maxwell needs binlog_format=row
binlog_format=row
server_id=1 
log-bin=master
```


```
mysql> CREATE USER 'maxwell'@'%' IDENTIFIED BY 'XXXXXX';
mysql> CREATE USER 'maxwell'@'localhost' IDENTIFIED BY 'XXXXXX';

mysql> GRANT ALL ON maxwell.* TO 'maxwell'@'%';
mysql> GRANT ALL ON maxwell.* TO 'maxwell'@'localhost';

mysql> GRANT SELECT, REPLICATION CLIENT, REPLICATION SLAVE ON *.* TO 'maxwell'@'%';
mysql> GRANT SELECT, REPLICATION CLIENT, REPLICATION SLAVE ON *.* TO 'maxwell'@'localhost';
```

# Run Maxwell
***

## Command line
```
bin/maxwell --user='maxwell' --password='XXXXXX' --host='127.0.0.1' --producer=stdout
```

## Docker
```
docker run -it --rm -e MYSQL_USERNAME -e MYSQL_PASSWORD -e MYSQL_HOST \
    -e MAXWELL_PRODUCER=stdout osheroff/maxwell
```

The image's default command (`bin/maxwell-docker`) hands `MYSQL_USERNAME`,
`MYSQL_PASSWORD` and `MYSQL_HOST` to Maxwell through the environment (see
[env_config_prefix](config.md#via-environment)) instead of the command line,
so the password is not visible to other users via `ps`. Extra options go in
`MAXWELL_OPTIONS`. Avoid passing `--password` on the command line.

## Kafka

Boot kafka as described here:  [http://kafka.apache.org/documentation.html#quickstart](http://kafka.apache.org/documentation.html#quickstart), then:

```
bin/maxwell --user='maxwell' --password='XXXXXX' --host='127.0.0.1' \
   --producer=kafka --kafka.bootstrap.servers=localhost:9092 --kafka_topic=maxwell
```

(or docker):

```
docker run -it --rm -e MYSQL_USERNAME -e MYSQL_PASSWORD -e MYSQL_HOST \
    -e MAXWELL_PRODUCER=kafka -e KAFKA_BROKERS=$KAFKA_HOST:$KAFKA_PORT \
    -e MAXWELL_OPTIONS="--kafka_topic=maxwell" osheroff/maxwell
```

## Kinesis

```
docker run -it --rm --name maxwell -v `cd && pwd`/.aws:/root/.aws -e MYSQL_USERNAME -e MYSQL_PASSWORD -e MYSQL_HOST -e AWS_DEFAULT_REGION -e MAXWELL_PRODUCER=kinesis -e MAXWELL_OPTIONS="--kinesis_stream=$KINESIS_STREAM" osheroff/maxwell sh -c 'cp /app/kinesis-producer-library.properties.example /app/kinesis-producer-library.properties && echo "Region=$AWS_DEFAULT_REGION" >> /app/kinesis-producer-library.properties && bin/maxwell-docker'
```

## Nats

```
bin/maxwell --user='maxwell' --password='XXXXXX' --host='127.0.0.1' \
    --producer=nats --nats_url=='0.0.0.0:4222'
```

## Google Cloud Pub/Sub

```
bin/maxwell --user='maxwell' --password='XXXXXX' --host='127.0.0.1' \
  --producer=pubsub --pubsub_project_id='$PUBSUB_PROJECT_ID' \
  --pubsub_topic='maxwell'
```

## Google Cloud Bigquery

```
bin/maxwell --user='maxwell' --password='XXXXXX' --host='127.0.0.1' \
  --producer=bigquery --bigquery_project_id='$BIGQUERY_PROJECT_ID' \
  --bigquery_dataset='$BIGQUERY_DATASET' \
  --bigquery_table='$BIGQUERY_TABLE'
```

## RabbitMQ

```
bin/maxwell --user='maxwell' --password='XXXXXX' --host='127.0.0.1' \
    --producer=rabbitmq --rabbitmq_host='rabbitmq.hostname'
```

## Redis

```
bin/maxwell --user='maxwell' --password='XXXXXX' --host='127.0.0.1' \
    --producer=redis --redis_host=redis.hostname
```

## SNS

```
bin/maxwell --user='maxwell' --password='XXXXXX' --host='127.0.0.1' \
    --producer=sns --sns_topic=sns.topic --sns_attrs=database,table
```
