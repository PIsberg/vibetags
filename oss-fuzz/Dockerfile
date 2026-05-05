FROM gcr.io/oss-fuzz-base/base-builder-jvm

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
