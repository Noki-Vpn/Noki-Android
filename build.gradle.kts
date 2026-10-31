buildscript {
    configurations.getByName("classpath").resolutionStrategy.force(
        "com.google.guava:guava:33.7.2-jre",
        "io.netty:netty-common:4.1.137.Final",
        "io.netty:netty-handler:4.1.137.Final",
        "io.netty:netty-handler-proxy:4.1.137.Final",
        "io.netty:netty-codec:4.1.137.Final",
        "io.netty:netty-codec-http:4.1.137.Final",
        "io.netty:netty-codec-http2:4.1.137.Final",
        "org.bouncycastle:bcprov-jdk18on:1.85",
        "org.bouncycastle:bcpkix-jdk18on:1.85",
        "org.bouncycastle:bcutil-jdk18on:1.85",
        "org.bitbucket.b_c:jose4j:0.9.6",
        "org.jdom:jdom2:2.0.6.1",
        "org.apache.commons:commons-lang3:3.18.0",
        "org.apache.httpcomponents:httpclient:4.5.14",
    )

    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20-Beta1")
    }
}

plugins {
    id("com.android.application") version "9.3.2" apply false
    id("com.google.gms.google-services") version "4.4.4" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20-Beta1" apply false
}

allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "com.google.guava" && requested.name == "guava") {
                val edition = if (requested.version?.endsWith("-android") == true) "android" else "jre"
                useVersion("33.7.2-$edition")
                because("CVE-2026-102554")
            }
        }
        resolutionStrategy.force(
            "io.netty:netty-common:4.1.137.Final",
            "io.netty:netty-handler:4.1.137.Final",
            "io.netty:netty-handler-proxy:4.1.137.Final",
            "io.netty:netty-codec:4.1.137.Final",
            "io.netty:netty-codec-http:4.1.137.Final",
            "io.netty:netty-codec-http2:4.1.137.Final",
            "org.bouncycastle:bcprov-jdk18on:1.85",
            "org.bouncycastle:bcpkix-jdk18on:1.85",
            "org.bouncycastle:bcutil-jdk18on:1.85",
            "org.bitbucket.b_c:jose4j:0.9.6",
            "org.jdom:jdom2:2.0.6.1",
            "org.apache.commons:commons-lang3:3.18.0",
            "org.apache.httpcomponents:httpclient:4.5.14",
        )
    }
}
