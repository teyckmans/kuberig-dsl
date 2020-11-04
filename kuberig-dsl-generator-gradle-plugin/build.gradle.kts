plugins {
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "0.11.0"
}

dependencies {
    val kotlinVersion = project.properties["kotlinVersion"]
    
    implementation(project(":kuberig-dsl-generator"))
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:$kotlinVersion")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.3.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.3.2")
}

val projectVersion = if (project.version.toString() == "unspecified") { "0.0.0" } else { project.version.toString() }

tasks.test {
    systemProperty("projectVersion", projectVersion)

    dependsOn(tasks.jar)
}

gradlePlugin {
    plugins {
        create("kuberig-dsl-generator-gradle-plugin") {
            id = "io.kuberig.dsl.generator"
            displayName = "Kuberig Kotlin DSL generator plugin"
            description = "This plugin is used to generate a Kotlin DSL based on a Kubernetes or Openshift swagger API definition."
            implementationClass = "io.kuberig.dsl.generator.gradle.KubeRigDslGeneratorPlugin"
        }
    }
}

pluginBundle {
    website = project.properties["websiteUrl"]!! as String
    vcsUrl = project.properties["vcsUrl"]!! as String
    tags = listOf("kubernetes", "kotlin", "dsl", "generator", "openshift")
}

(tasks.getByName("processResources") as ProcessResources).apply {
    filesMatching("io.kuberig.dsl.generator.properties") {
        expand(
            Pair("kuberigDslVersion", projectVersion)
        )
    }
}

tasks {
    build {
        dependsOn(":kuberig-dsl-base:publishToMavenLocal")
    }
}