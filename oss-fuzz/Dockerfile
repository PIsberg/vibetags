FROM gcr.io/oss-fuzz-base/base-builder-jvm

RUN apt-get update && apt-get install -y maven

COPY . $SRC/vibetags
WORKDIR $SRC/vibetags

COPY oss-fuzz/build.sh $SRC/
COPY oss-fuzz/se/deversity/vibetags/fuzz/VibeTagsFuzzer.java $SRC/

# OSS-Fuzz execution
CMD ["compile"]
