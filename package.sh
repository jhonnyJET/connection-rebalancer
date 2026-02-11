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

mvn package -Dquarkus.container-image.build=true -Dquarkus.package.type=jar -Dquarkus.container-image.tag=${version}-${arch}