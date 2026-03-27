#!/usr/bin/env bash
# Download a copy of linux JDK in jdks/linux

set -e

JDK_VERSION="25.0.2"
JDK_BUILD_HASH="b1e0dfa218384cb9959bdcb897162d4e"
JDK_BUILD_NUMBER="10"

mkdir -p jdks/linux
cd jdks/linux
curl -L "https://download.java.net/java/GA/jdk${JDK_VERSION}/${JDK_BUILD_HASH}/${JDK_BUILD_NUMBER}/GPL/openjdk-${JDK_VERSION}_linux-x64_bin.tar.gz" > linux.tar.gz
tar xzf linux.tar.gz
rm linux.tar.gz
mv "jdk-${JDK_VERSION}" jdk-25
cd ../..
