#!/usr/bin/env sh

: ${HOST:=$(ipconfig getifaddr en0)}
: ${HOST:=$(ipconfig getifaddr en1)}
: ${HOST:=$(ipconfig getifaddr en2)}
: ${HOST:=$(ipconfig getifaddr en3)}
: ${HOST:=$(ipconfig getifaddr en4)}
if [ -z $HOST ]; then
  echo HOST neither defined nor detectable!
  exit 1
fi

docker run \
  --detach \
  --name etcd \
  --publish 2379:2379 \
  quay.io/coreos/etcd:v2.3.7 \
  --advertise-client-urls http://${HOST}:2379 \
  --listen-client-urls http://0.0.0.0:2379
