// The root holds no code. Each module applies the convention plugins of build-logic; this file only
// names the group and the wrapper version so `./gradlew` and CI agree.

group = "com.iaf.console"

tasks.wrapper {
    gradleVersion = "8.14.3"
    distributionType = Wrapper.DistributionType.BIN
}
