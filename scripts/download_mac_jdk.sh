#!/usr/bin/env bash
# Download a copy of mac JDK in jdks/mac

set -e

JDK_VERSION="25.0.2"
JDK_BUILD_HASH="b1e0dfa218384cb9959bdcb897162d4e"
JDK_BUILD_NUMBER="10"

case "$(uname -m)" in
    arm64|aarch64)
        ARCHIVE="openjdk-${JDK_VERSION}_macos-aarch64_bin.tar.gz"
        ;;
    *)
        ARCHIVE="openjdk-${JDK_VERSION}_macos-x64_bin.tar.gz"
        ;;
esac

mkdir -p jdks/mac
cd jdks/mac
curl -L "https://download.java.net/java/GA/jdk${JDK_VERSION}/${JDK_BUILD_HASH}/${JDK_BUILD_NUMBER}/GPL/${ARCHIVE}" > mac.tar.gz
tar xzf mac.tar.gz
rm mac.tar.gz
mv "jdk-${JDK_VERSION}.jdk" jdk-25
cd ../..
