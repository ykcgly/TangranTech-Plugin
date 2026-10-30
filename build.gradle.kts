// 糖然科技 TangranTech —— 独立 Slimefun 附属插件构建脚本（对齐 WorldTaste-Plugin 结构）
// 仅依赖 Paper API 与本地 Slimefun jar，不联网下载 Slimefun。
import org.gradle.api.attributes.java.TargetJvmVersion

plugins {
    java
}

group = "com.tangran"
version = "2.1.3-standalone"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.compileJava {
    options.encoding = "UTF-8"
    // 目标服务端为 1.21.x（Paper 要求 Java 21），编译基线直接取 21，
    // 与 Komutech-Plugin 保持一致。
    options.release.set(21)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>().configureEach {
    options.encoding = "UTF-8"
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // @Nullable/@NotNull 仅用于标注可空契约，编译期可见即可，不打入产物
    compileOnly("org.jetbrains:annotations:24.1.0")
    // 本地 Slimefun 依赖（compileOnly，运行期由服务器提供）
    compileOnly(files("libs/Slimefun4-347d926-Beta.jar"))
    // LuckPerms 官方 API：只编译用，运行期由服务端插件提供（plugin.yml 里 softdepend）
    compileOnly("net.luckperms:api:5.4")
    // SQLite JDBC：服务端不提供驱动，必须自带 → 打进产物（运行期用 DriverManager 连库）。
    // slf4j 由服务端提供（Paper 自带 2.x），不打进产物以免与平台日志实现冲突。
    implementation("org.xerial:sqlite-jdbc:3.47.1.0") {
        exclude(group = "org.slf4j")
    }
}

// paper-api 1.21.x 只发布 JVM 21 variant，显式指定以正常选中依赖。
configurations.compileClasspath {
    attributes {
        attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 21)
    }
}

// 把 content/ 下的内容 YAML 与数据文件一并打入 jar（插件运行期从自身资源读取）。
val contentFiles = listOf(
    "groups.yml", "recipe_types.yml", "items.yml", "machines.yml", "mob_drops.yml",
    "recipe_machines.yml", "mb_machines.yml", "template_machines.yml", "menus.yml",
    "generators.yml", "mat_generators.yml", "super_multi_block_machines.yml"
)

tasks.processResources {
    filteringCharset = "UTF-8"
    from(rootProject.projectDir.resolve("content")) {
        include(contentFiles)
        include("data/**")
        into("") // 置于 jar 根目录
    }
}

tasks.jar {
    archiveBaseName.set("TangranTech")
    archiveVersion.set(project.version.toString())
    archiveClassifier.set("")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // 把 sqlite-jdbc 的类与原生库打进产物（服务端不会提供 SQLite 驱动）。
    // 原生库每个平台约 0.6 MB，这里只保留常见服务端/开发平台，冷门平台靠
    // Store 的降级路径（驱动起不来就回落文件存储）兜底。
    from({ configurations.runtimeClasspath.get().map { zipTree(it) } })
    exclude(
        "org/sqlite/native/Linux-Android/**",
        "org/sqlite/native/FreeBSD/**",
        "org/sqlite/native/Linux/arm/**",
        "org/sqlite/native/Linux/armv7/**",
        "org/sqlite/native/Linux/ppc64/**",
        "org/sqlite/native/Linux/riscv64/**",
        "org/sqlite/native/Linux/x86/**",
        "org/sqlite/native/Linux-Musl/x86/**",
        "org/sqlite/native/Windows/aarch64/**",
        "META-INF/maven/**",
        "META-INF/LICENSE*",
        "META-INF/NOTICE*"
    )
}

tasks.build {
    dependsOn(tasks.jar)
}
