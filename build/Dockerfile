# SPDX-FileCopyrightText: 2026 Andrew Yong
# SPDX-License-Identifier: MIT
#
# Build environment for build-*.sh. Android SDK pieces, the NDK and the tool
# jars are not baked in: fetch.sh downloads them into ./cache (see inputs.lock).
FROM --platform=linux/amd64 debian:trixie-slim@sha256:a99cfc517144bc59b1978475ec53b46ecabec7e43635402ee5b77cc54cd1b20a

ARG DEBIAN_FRONTEND=noninteractive
RUN apt-get update \
 && apt-get install -y --no-install-recommends \
      android-sdk-libsparse-utils \
      binutils \
      ca-certificates \
      curl \
      device-tree-compiler \
      diffutils \
      e2fsprogs \
      file \
      git \
      openjdk-21-jdk-headless \
      openssl \
      patch \
      patchelf \
      python3 \
      secilc \
      unzip \
      xz-utils \
      zip \
      zstd \
 && rm -rf /var/lib/apt/lists/*

ENV LANG=C.UTF-8
WORKDIR /src
