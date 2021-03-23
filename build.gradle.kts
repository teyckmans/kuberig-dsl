
import org.jetbrains.dokka.gradle.DokkaTask
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

fun isCiBuild(): Boolean {
    return System.getenv().getOrDefault("CI", "false") == "true"
}

fun isReleaseBuild(): Boolean {
    return isCiBuild() && System.getenv().containsKey("CI_COMMIT_TAG")
}

fun isCiJobTokenAvailable(): Boolean {
    return isCiBuild() && System.getenv().containsKey("CI_JOB_TOKEN")
}

fun setProperty(propertyName: String, envVarName: String) {
    val env = System.getenv()

    if (env.containsKey(envVarName)) {
        val envVarValue = env[envVarName]!!
        System.setProperty(propertyName, envVarValue)
        try {
            project.rootProject.setProperty(propertyName, envVarValue)
        }
        catch (e: Exception) {
            logger.error("Failed to set value found for $propertyName using environment variable $envVarName", e)
        }
    } else {
        logger.error("No value found for $propertyName using environment variable $envVarName")
    }
}

fun determineVersion(): String {
    val env = System.getenv()

    return if (isCiBuild()) {
        if (isReleaseBuild()) {
            env["CI_COMMIT_TAG"]!!
        } else {
            env["CI_COMMIT_REF_SLUG"]!! + "-SNAPSHOT"
        }
    } else {
        if (project.version.toString() == "unspecified") {
            println("Defaulting to version 0.0.0")
            "0.0.0-SNAPSHOT"
        } else {
            project.version.toString()
        }
    }
}

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        classpath("com.slack.api:bolt:1.1.+")
        classpath("com.slack.api:bolt-servlet:1.1.+")
        classpath("com.slack.api:bolt-jetty:1.1.+")
    }
}

plugins {
    id("org.jetbrains.kotlin.jvm") apply false
    id("org.jetbrains.dokka") apply false
    id("io.github.gradle-nexus.publish-plugin")
}

check(project.hasProperty("sonatypeUsername")) { "sonatypeUsername property missing."}
check(project.hasProperty("sonatypePassword")) { "sonatypePassword property missing."}
check(project.hasProperty("gradle.publish.key")) { "gradle.publish.key property missing."}
check(project.hasProperty("gradle.publish.secret")) { "gradle.publish.secret property missing."}
check(project.hasProperty("signing.keyId")) { "signing.keyId property missing." }
check(project.hasProperty("signing.password")) { "signing.password property missing." }
check(project.hasProperty("signing.secretKeyRingFile")) { "signing.secretKeyRingFile property missing." }

val projectVersion = determineVersion()

group = "io.kuberig"
version = projectVersion

nexusPublishing {
    repositories {
        sonatype {
            stagingProfileId.set("a75126268d08")
        }
    }
}

subprojects {
    apply {
        plugin("maven-publish")
        plugin("java")
        plugin("idea")
        plugin("org.jetbrains.kotlin.jvm")
        plugin("jacoco")
        plugin("org.jetbrains.dokka")
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

        // Align versions of all Kotlin components
        implementation(platform("org.jetbrains.kotlin:kotlin-bom"))

        // Use the Kotlin JDK 8 standard library.
        implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")

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

        if (isCiJobTokenAvailable()) {
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
    }

    tasks.withType<Jar> {
        manifest {
            attributes(
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version
            )
        }
    }

    if (subProject.hasProperty("signing.keyId")
        && subProject.hasProperty("signing.password")
        && subProject.hasProperty("signing.secretKeyRingFile")) {
        println("Signing configuration available, configuring artifact signing...")

        apply {
            plugin("signing")
        }

        subProject.configure<SigningExtension> {
            subProject.extensions.getByType<PublishingExtension>().publications.all {
                sign(this)
            }
        }
    } else {
        println("Signing configuration not available, skipping artifact signing configuration.")
        println("signing.keyId: " + subProject.hasProperty("signing.keyId"))
        println("signing.password: " + subProject.hasProperty("signing.password"))
        println("signing.secretKeyRingFile: " + subProject.hasProperty("signing.secretKeyRingFile"))
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

tasks.register("deploy") {
    group = "publishing"

    if (isCiBuild()) {
        if (isReleaseBuild()) {
            println("Running RELEASE build, publish to GitLab, Sonatype and Gradle Plugin Portal.")
            // release build
            project.subprojects.forEach {
                if (isCiJobTokenAvailable()) {
                    dependsOn(it.tasks.getByName("publishAllPublicationsToGitLabRepository"))
                }
                dependsOn(it.tasks.getByName("publishAllPublicationsToLocalRepository"))
                dependsOn(it.tasks.getByName("publishToSonatype"))
            }

            dependsOn("closeSonatypeStagingRepository")
            //dependsOn(":kuberig-dsl-generator-gradle-plugin:publishPlugins")
        } else {
            // snapshot build
            println("Running SNAPSHOT build, only publishing to GitLab repository.")


            project.subprojects.forEach {
                if (isCiJobTokenAvailable()) {
                    dependsOn(it.tasks.getByName("publishAllPublicationsToGitLabRepository"))
                }
                it.tasks.getByName("publishAllPublicationsToLocalRepository")
            }
        }
    } else {
        project.subprojects.forEach { dependsOn(it.tasks.getByName("publishAllPublicationsToLocalRepository")) }
    }
}