# Pinned by digest for reproducible builds (Scorecard Pinned-Dependencies).
# Tag: gcr.io/oss-fuzz-base/base-builder-jvm:latest — bump the digest when upgrading.
FROM gcr.io/oss-fuzz-base/base-builder-jvm@sha256:a3652934a4580fc62e1798b15ae257b7740a2815c4fcf1bd631b5a9743780e62

RUN apt-get update && apt-get install -y maven

COPY . $SRC/vibetags
WORKDIR $SRC/vibetags

COPY oss-fuzz/build.sh $SRC/
# Fuzzer lives in the default Java package — the file sits directly under
# oss-fuzz/, not under any se/deversity/... subdirectory. build.sh discovers
# it via `find $SRC -name '*Fuzzer.java'`.
COPY oss-fuzz/VibeTagsFuzzer.java $SRC/

# OSS-Fuzz execution
CMD ["compile"]
