import de.marcphilipp.gradle.nexus.NexusPublishExtension
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("io.codearte.nexus-staging")
    id("org.jetbrains.kotlin.jvm") apply false
    id("org.jetbrains.dokka") apply false
    id("de.marcphilipp.nexus-publish") apply false
}

val sonatypeUsername: String? by project
val sonatypePassword: String? by project
val projectVersion: String = if (project.version.toString() == "unspecified") {
    println("Defaulting to version 0.0.0")
    "0.0.0"
} else {
    project.version.toString()
}

nexusStaging {
    username = sonatypeUsername
    password = sonatypePassword
    repositoryDescription = "Release io.kuberig - ${rootProject.name} - $projectVersion"
}

subprojects {
    apply {
        plugin("maven-publish")
        plugin("java")
        plugin("idea")
        plugin("org.jetbrains.kotlin.jvm")
        plugin("jacoco")
        plugin("org.jetbrains.dokka")
        plugin("de.marcphilipp.nexus-publish")
    }

    val subProject = this

    group = "io.kuberig"
    subProject.version = projectVersion

    repositories {
        mavenCentral()
        // dokka is not available on mavenCentral yet.
        jcenter {
            content {
                includeGroup("org.jetbrains.dokka")
                includeGroup("org.jetbrains") // dokka (transitive: jetbrains markdown)
                includeGroup("org.jetbrains.kotlinx") // dokka (transitive: kotlinx-html-jvm)
                includeGroup("com.soywiz.korlibs.korte") // dokka (transitive: korte-jvm)
            }
        }
    }

    dependencies {
        val implementation by configurations
        val testImplementation by configurations

        implementation(kotlin("stdlib-jdk8"))

        // Use the Kotlin test library.
        testImplementation("org.jetbrains.kotlin:kotlin-test")

        // Use the Kotlin JUnit integration.
        testImplementation("org.jetbrains.kotlin:kotlin-test-junit")
    }

    configure<JavaPluginExtension> {
        sourceCompatibility = JavaVersion.VERSION_1_8
    }
    tasks.withType<KotlinCompile> {
        kotlinOptions.jvmTarget = "1.8"
    }

    tasks.named<Test>("test") {
        finalizedBy(tasks.getByName("jacocoTestReport")) // report is always generated after tests run
    }
    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.getByName("test")) // tests are required to run before generating the report
        reports {
            xml.isEnabled = true
            csv.isEnabled = false
        }
    }

    tasks.getByName("check").dependsOn(tasks.getByName("jacocoTestReport"))

    val sourceSets: SourceSetContainer by this
    val sourcesJar by tasks.creating(Jar::class) {
        archiveClassifier.set("sources")
        from(sourceSets["main"].allSource)
    }

    val javadocJar by tasks.creating(Jar::class) {
        archiveClassifier.set("javadoc")
        val dokkaJavadoc = subProject.tasks.getByName<DokkaTask>("dokkaJavadoc")
        from(dokkaJavadoc.outputDirectory)
        dependsOn(dokkaJavadoc)
    }

    configure<PublishingExtension> {

        publications {
            create<MavenPublication>(subProject.name + "-maven") {
                from(components["java"])
                artifact(sourcesJar)
                artifact(javadocJar)
            }
        }

        repositories {
            maven {
                name = "local"
                // change URLs to point to your repos, e.g. http://my.org/repo
                val releasesRepoUrl = uri("$buildDir/repos/releases")
                val snapshotsRepoUrl = uri("$buildDir/repos/snapshots")

                val urlToUse = if (projectVersion.endsWith("SNAPSHOT")) {
                        snapshotsRepoUrl
                } else {
                    releasesRepoUrl
                }

                url = urlToUse
            }
        }

        repositories {
            maven {
                url = uri("https://gitlab.com/api/v4/projects/24703950/packages/maven")
                name = "GitLab"
                credentials(HttpHeaderCredentials::class) {
                    name = "Job-Token"
                    value = System.getenv("CI_JOB_TOKEN")
                }
                authentication {
                    create<HttpHeaderAuthentication>("header")
                }
            }
        }
    }

    subProject.configure<NexusPublishExtension> {
        repositories {
            sonatype {
                username.set(sonatypeUsername)
                password.set(sonatypePassword)
            }
        }

        // these are not strictly required. The default timeouts are set to 1 minute. But Sonatype can be really slow.
        // If you get the error "java.net.SocketTimeoutException: timeout", these lines will help.
        connectTimeout.set(java.time.Duration.ofMinutes(3))
        clientTimeout.set(java.time.Duration.ofMinutes(3))
    }

    tasks.withType<Jar> {
        manifest {
            attributes(
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version
            )
        }
    }

    if (subProject.hasProperty("signing.keyId")) {
        apply {
            plugin("signing")
        }

        subProject.configure<SigningExtension> {
            subProject.extensions.getByType<PublishingExtension>().publications.all {
                sign(this)

            }
        }
    }

    subProject.plugins.withType<MavenPublishPlugin>().all {
        val publishing = subProject.extensions.getByType<PublishingExtension>()
        publishing.publications.withType<MavenPublication>().all {
            groupId = subProject.group as String
            artifactId = subProject.name
            version = subProject.version as String

            val vcsUrl = project.properties["vcsUrl"]!! as String

            pom {
                name.set("${subProject.group}:${subProject.name}")
                description.set("Kuberig DSL generation.")
                url.set(vcsUrl)

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0")
                    }
                }

                developers {
                    developer {
                        id.set("teyckmans")
                        name.set("Tom Eyckmans")
                        email.set("teyckmans@gmail.com")
                    }
                }

                val sshConnection = vcsUrl.replace("https://", "ssh://") + ".git"

                scm {
                    connection.set("scm:git:$vcsUrl")
                    developerConnection.set("scm:git:$sshConnection")
                    url.set(vcsUrl)
                }
            }
        }
    }
}
