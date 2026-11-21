plugins {
  alias(libs.plugins.kotlin.jvm)
  application
}

kotlin {
  jvmToolchain(21)
}

application {
  mainClass = "io.github.lumpilabs.beamline.windows.MainKt"
  applicationName = "beamline"
  // JNA loads the capture library and libopus; newer Javas warn about that unless allowed.
  applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

dependencies {
  implementation(project(":protocol"))
  implementation(libs.jna)
  implementation(libs.opus.java)
  implementation(libs.jmdns)
  runtimeOnly(libs.slf4j.nop)
  testImplementation(libs.kotlin.test.junit)
}

/*
 * The native screen and sound capture (src/main/cpp), built with MinGW-w64: its g++ on Windows,
 * the cross compiler on Linux. JNA loads it from the jar. Without a compiler the library is left
 * out with a warning, so the rest of the project still builds; -Pbeamline.native=required (as in
 * CI) makes that an error instead.
 */
val isWindows = System.getProperty("os.name").startsWith("Windows")
val compilerName = providers.gradleProperty("beamline.mingw").getOrElse(if (isWindows) "g++" else "x86_64-w64-mingw32-g++")
val nativeRequired = providers.gradleProperty("beamline.native").orNull == "required"
val nativeOutput = layout.buildDirectory.dir("native")

fun onPath(name: String): Boolean =
  System.getenv("PATH").orEmpty().split(File.pathSeparator).any { dir ->
    listOf(name, "$name.exe").any { File(dir, it).canExecute() }
  }

val compileNative = tasks.register<Exec>("compileNative") {
  val source = file("src/main/cpp/beamline-media.cpp")
  val library = nativeOutput.map { it.file("win32-x86-64/beamline-media.dll") }
  val found = onPath(compilerName)
  if (!found && nativeRequired) throw GradleException("$compilerName isn't on the PATH; install MinGW-w64")
  inputs.file(source)
  inputs.property("compiler", compilerName)
  outputs.file(library)
  onlyIf("MinGW-w64 is on the PATH") {
    if (!found) it.logger.warn("Leaving out the Windows sender's capture library: $compilerName isn't on the PATH")
    found
  }
  doFirst { library.get().asFile.parentFile.mkdirs() }
  executable = compilerName
  args(
    "-std=c++17", "-O2", "-Wall", "-Wextra", "-shared", "-static", "-s",
    "-o", library.get().asFile.absolutePath,
    source.absolutePath,
    "-ld3d11", "-ldxgi", "-lmfplat", "-lmfuuid", "-lole32", "-loleaut32", "-luuid",
  )
}

sourceSets.main {
  resources.srcDir(files(nativeOutput).builtBy(compileNative))
}
