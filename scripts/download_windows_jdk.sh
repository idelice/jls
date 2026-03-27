#!/usr/bin/env bash
# Download a copy of windows JDK in jdks/windows

set -e

JDK_VERSION="25.0.2"
JDK_BUILD_HASH="b1e0dfa218384cb9959bdcb897162d4e"
JDK_BUILD_NUMBER="10"

mkdir -p jdks/windows
cd jdks/windows
curl -L "https://download.java.net/java/GA/jdk${JDK_VERSION}/${JDK_BUILD_HASH}/${JDK_BUILD_NUMBER}/GPL/openjdk-${JDK_VERSION}_windows-x64_bin.zip" > windows.zip
unzip windows.zip
rm windows.zip
mv "jdk-${JDK_VERSION}" jdk-25
cd ../..
