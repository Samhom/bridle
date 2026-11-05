param([int]$Operations=10000,[int]$Warmup=2000,[int]$Concurrency=4)
$ErrorActionPreference='Stop'
$benchmarkRoot=Split-Path -Parent $PSScriptRoot
Push-Location $benchmarkRoot
try {
  # Build once, installing only the benchmark's required SDK modules for dependency resolution.
  mvn -B -ntp -s .mvn/settings.xml "-Dmaven.repo.local=$benchmarkRoot/.m2-cache" -Pbenchmarks -pl bridle-benchmarks -am -DskipTests install org.apache.maven.plugins:maven-dependency-plugin:3.8.1:build-classpath -Dmdep.outputFile=target/benchmark-classpath.txt
  if($LASTEXITCODE -ne 0){throw 'Benchmark build or dependency resolution failed'}
  $benchmarkClasspath=(Get-Content bridle-benchmarks/target/benchmark-classpath.txt -Raw).Trim()
  java -cp "bridle-benchmarks/target/classes;$benchmarkClasspath" io.github.samhom.bridle.benchmark.DriverBenchmark $Operations $Warmup $Concurrency
  if($LASTEXITCODE -ne 0){throw 'Benchmark failed'}
} finally {Pop-Location}
