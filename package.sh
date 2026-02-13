#!/usr/bin/env bash

# 0. capturing arguments...
while getopts "v:a:" flag
do
    case "${flag}" in
        v) version=$OPTARG;;
        a) arch=$OPTARG;;
    esac
done

echo "Version: $version";
echo "Arch: $arch";

mvn package -Dquarkus.container-image.build=true -Dquarkus.package.type=jar -Dquarkus.container-image.name=connection_rebalancer -Dquarkus.container-image.tag=${version}-${arch}
podman push connection_rebalancer:${version}-${arch} docker.io/jhonnyvennom/connection_rebalancer:${version}-${arch}