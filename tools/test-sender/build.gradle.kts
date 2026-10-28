plugins {
  alias(libs.plugins.kotlin.jvm)
  application
}

kotlin {
  jvmToolchain(21)
}

application {
  mainClass = "io.github.lumpilabs.beamline.testsender.TestSenderKt"
}

dependencies {
  implementation(project(":protocol"))
  implementation(libs.jcodec)
  implementation(libs.jcodec.javase)
}
