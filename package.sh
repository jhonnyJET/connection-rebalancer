#!/bin/bash
mvn package -Dquarkus.container-image.build=true -Dquarkus.package.type=jar -Dquarkus.container-image.tag=jvm.test