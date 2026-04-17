#!/bin/bash
# OSS-Fuzz build script for VibeTags

cd vibetags

# Build VibeTags Processor
echo "Building VibeTags Processor..."
cd vibetags
mvn clean package -DskipTests

# Copy the constructed processor jar to OUT
# There will be vibetags-processor-1.X.X-SNAPSHOT.jar or similar.
JAR_FILE=$(find target -name "vibetags-processor-*.jar" | head -n 1)
cp $JAR_FILE $OUT/vibetags-processor.jar

PROJECT_JARS="vibetags-processor.jar"

# Build Fuzzers
echo "Building Fuzzers..."
BUILD_CLASSPATH=$(echo $PROJECT_JARS | xargs printf -- "$OUT/%s:"):$JAZZER_API_PATH
RUNTIME_CLASSPATH=$(echo $PROJECT_JARS | xargs -I {} echo "\$this_dir/{}"):\$this_dir

for fuzzer in $(find $SRC -name '*Fuzzer.java'); do
  fuzzer_basename=$(basename -s .java $fuzzer)
  javac -cp $BUILD_CLASSPATH $fuzzer
  
  # The Fuzzer is in a package or default package. We assume default package for Jazzer ease.
  cp $(dirname $fuzzer)/${fuzzer_basename}.class $OUT/

  echo "#!/bin/bash
# LLVMFuzzerTestOneInput wrapper for Jazzer
this_dir=\$(dirname \"\$0\")
if [[ \"\$@\" =~ (^| )-runs=[0-9]+($| ) ]]; then
  mem_settings='-Xmx1900m:-Xss900k'
else
  mem_settings='-Xmx2048m:-Xss1024k'
fi

LD_LIBRARY_PATH=\"$JVM_LD_LIBRARY_PATH\":\$this_dir \
\$this_dir/jazzer_driver --agent_path=\$this_dir/jazzer_agent_deploy.jar \
--cp=${RUNTIME_CLASSPATH} \
--target_class=$fuzzer_basename \
--jvm_args=\"\$mem_settings:-Djava.awt.headless=true\" \
\$@" > $OUT/$fuzzer_basename

  chmod +x $OUT/$fuzzer_basename
done
