/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package eu.rigeldev.kuberig.dsl.generator.input.swagger

import eu.rigeldev.kuberig.dsl.generator.input.DslMetaProducer
import eu.rigeldev.kuberig.dsl.generator.meta.*
import eu.rigeldev.kuberig.dsl.generator.meta.attributes.DslAttributeMeta
import eu.rigeldev.kuberig.dsl.generator.meta.attributes.DslListAttributeMeta
import eu.rigeldev.kuberig.dsl.generator.meta.attributes.DslMapAttributeMeta
import eu.rigeldev.kuberig.dsl.generator.meta.attributes.DslObjectAttributeMeta
import eu.rigeldev.kuberig.dsl.generator.meta.kinds.DslKindMeta
import eu.rigeldev.kuberig.dsl.generator.meta.kinds.Kind
import eu.rigeldev.kuberig.dsl.generator.meta.kinds.KindTypes
import eu.rigeldev.kuberig.dsl.generator.meta.types.DslContainerTypeMeta
import eu.rigeldev.kuberig.dsl.generator.meta.types.DslInterfaceTypeMeta
import eu.rigeldev.kuberig.dsl.generator.meta.types.DslObjectTypeMeta
import eu.rigeldev.kuberig.dsl.generator.meta.types.DslSealedTypeMeta
import io.swagger.models.*
import io.swagger.models.parameters.BodyParameter
import io.swagger.models.parameters.Parameter
import io.swagger.models.parameters.PathParameter
import io.swagger.models.parameters.QueryParameter
import io.swagger.models.properties.*
import io.swagger.parser.SwaggerParser
import java.io.File

class SwaggerDslMetaProducer(private val swaggerFile: File) : DslMetaProducer {

    private lateinit var spec: Swagger
    private lateinit var dslMeta : DslMeta

    var showIgnoredRefModels : Boolean = false

    private val additionalPropertyDefinitions = mutableMapOf<String, DslClassInfo>()

    override fun provide(): DslMeta {
        this.spec = SwaggerParser().read(swaggerFile.absolutePath)

        if (spec.info.title.toLowerCase().contains("openshift")) {
            this.dslMeta = DslMeta(
                DslPlatformSpecifics(
                    listOf("io.k8s", "com.github")
                )
            )
        } else {
            this.dslMeta = DslMeta(
                DslPlatformSpecifics(
                    listOf("io.k8s")
                )
            )
        }

        this.findKindApiInfo()

        this.findKindTypes()

        check(this.dslMeta.kindTypes.isNotEmpty()) { "No writeable kinds found, kubernetes metadata missing" }

        this.findFullMetadataType()
        this.processKindTypes()
        this.processDefinitions()
        this.processAdditionalDefinitions()

        return dslMeta
    }

    private fun findKindApiInfo() {

        val kindApiActions = mutableMapOf<Kind, KindActions>()

        this.spec.paths.forEach { (url, path) ->
            path.operationMap.forEach { httpMethod, operation ->
                val operationKind = this.operationKind(operation)
                if (operationKind != null) {
                    val operationAction = this.operationAction(url, httpMethod.name, operation)
                    if (operationAction != null) {
                        val kindActions = kindApiActions.getOrDefault(operationKind, KindActions(operationKind, emptyList()))
                        val actions = mutableListOf<ResourceApiAction>()
                        actions.addAll(kindActions.actions)
                        actions.add(operationAction)
                        kindApiActions[operationKind] = KindActions(operationKind, actions)
                    }
                }
            }
        }

        this.dslMeta.kindApiActions = kindApiActions.toMap()
    }

    private fun operationAction(url: String, httpMethod: String, operation: Operation): ResourceApiAction? {
        val operationKind = this.operationKind(operation)
        return if (operationKind != null) {
            val requestBodyType = operationRequestBodyType(operation)
            val responseBodyType = operationOkResponseBodyType(operation)

            return ResourceApiAction(
                    url,
                    httpMethod,
                    operation.vendorExtensions["x-kubernetes-action"] as String,
                    operation.description,
                    requestBodyType,
                    responseBodyType,
                    queryParameterMap(operation.parameters),
                    pathParameterMap(operation.parameters)
            )
        } else {
            null
        }
    }

    private fun operationRequestBodyType(operation: Operation): DslTypeName? {
        val bodyParameters = operation.parameters.filter { it.`in` == "body" }
        return if (bodyParameters.size == 1) {
            val bodyParameter = bodyParameters[0]
            if (bodyParameter is BodyParameter) {
                val bodySchema = bodyParameter.schema
                if (bodySchema is RefModel) {
                    DslTypeName(bodySchema.simpleRef)
                } else {
                    null
                }
            } else {
                null
            }
        } else {
            null
        }
    }

    private fun operationOkResponseBodyType(operation: Operation): DslTypeName? {
        val okResponse = operation.responses["200"]
        return if (okResponse != null ) {
            val okResponseSchema = okResponse.responseSchema
            if (okResponseSchema is RefModel) {
                DslTypeName(okResponseSchema.simpleRef)
            } else {
                null
            }
        } else {
            null
        }
    }

    private fun pathParameterMap(parameters: List<Parameter>?): Map<String, ResourceApiActionParameter> {
        val parameterMap = mutableMapOf<String, ResourceApiActionParameter>()

        if (parameters != null) {
            for (parameter in parameters) {
                if (parameter is PathParameter) {
                    val actionParameter = basicParameter(parameter, parameter.type, parameter.uniqueItems)

                    parameterMap[actionParameter.name] = actionParameter
                }
            }
        }

        return parameterMap.toMap()
    }

    private fun queryParameterMap(parameters: List<Parameter>?): Map<String, ResourceApiActionParameter> {
        val parameterMap = mutableMapOf<String, ResourceApiActionParameter>()

        if (parameters != null) {
            for (parameter in parameters) {
                if (parameter is QueryParameter) {

                    val actionParameter = basicParameter(parameter, parameter.type, parameter.uniqueItems)

                    parameterMap[actionParameter.name] = actionParameter
                }
            }
        }

        return parameterMap.toMap()
    }

    private fun basicParameter(parameter: Parameter, type: String, uniqueItems: Boolean) : ResourceApiActionParameter {
        val typeName = if (type == "string") {
            DslTypeName("String")
        } else if (type == "boolean") {
            DslTypeName("Boolean")
        } else if (type == "integer") {
            DslTypeName("Int")
        } else {
            throw IllegalStateException("$type not supported for query parameters")
        }

        return ResourceApiActionParameter(
                parameter.name,
                parameter.description,
                typeName,
                uniqueItems,
                parameter.required
        )
    }

    private fun operationKind(operation: Operation): Kind? {
        return if (operation.vendorExtensions.containsKey("x-kubernetes-group-version-kind")) {
            val rawMap = operation.vendorExtensions["x-kubernetes-group-version-kind"] as Map<*, *>

            kubernetesGroupVersionKindToKind(rawMap)
        } else {
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun findKindTypes() {
        // TODO base on definitions only we now miss the read only kinds, so they do not get the proper parent types.
        this.dslMeta.kindApiActions.keys.forEach { kind ->
            val types = mutableListOf<String>()

            this.spec.definitions.forEach { (rawName, definition) ->

                if (definition.vendorExtensions.containsKey("x-kubernetes-group-version-kind")) {
                    val groupVersionKindList = definition.vendorExtensions["x-kubernetes-group-version-kind"]!! as List<Map<*, *>>

                    groupVersionKindList.forEach { groupVersionKind ->
                        val definitionKind = this.kubernetesGroupVersionKindToKind(groupVersionKind)

                        if (definitionKind == kind) {
                            types.add(rawName)
                        }
                    }
                }
            }

            if (types.isNotEmpty()) {
                this.dslMeta.kindTypes[kind] = KindTypes(kind, types)
            }
        }
    }

    private fun kubernetesGroupVersionKindToKind(rawMap: Map<*, *>): Kind {

        val normalizedGroupVersionKind = rawMap.mapKeys { (it.key as String).toLowerCase() }

        return Kind(
                (normalizedGroupVersionKind["group"] ?: error("No group found group-version-kind")) as String,
                (normalizedGroupVersionKind["kind"] ?: error("No kind found group-version-kind")) as String,
                (normalizedGroupVersionKind["version"] ?: error("No version found group-version-kind")) as String
        )
    }

    /**
     * Determine the actual type of the metadata attribute of a Kind.
     */
    private fun findFullMetadataType() {
        var fullMetadataTypename : DslTypeName? = null

        val kindTypeIterator = this.dslMeta.kindTypes.values.iterator()

        while (fullMetadataTypename == null && kindTypeIterator.hasNext()) {
            val kindType = kindTypeIterator.next()

            var typeIndex = 0
            while (fullMetadataTypename == null && typeIndex < kindType.types.size) {

                val definition = spec.definitions[kindType.types[typeIndex]]!!

                val metadataAttribute = definition.properties["metadata"]

                if (metadataAttribute != null) {
                    if (metadataAttribute is RefProperty) {
                        fullMetadataTypename = DslTypeName(metadataAttribute.simpleRef)
                    }
                }

                typeIndex++
            }
        }

        check(fullMetadataTypename != null) {
            "Implementation type for eu.rigeldev.kuberig.dsl.model.FullMetadata could not be determined. Unsupported api specification."
        }

        this.dslMeta.resourceMetadataType = fullMetadataTypename
    }

    private fun processKindTypes() {

        // create type-meta for kind classes
        this.dslMeta.kindTypes.values.forEach { kindTypes ->

            kindTypes.types.forEach { rawName ->

                dslMeta.registerKind(
                        DslKindMeta(
                                DslTypeName(rawName),
                                kindTypes.kind.group,
                                kindTypes.kind.kind,
                                kindTypes.kind.version
                        )
                )
            }
        }
    }

    private fun processDefinitions() {
        spec.definitions.forEach { rawName, definition ->
            processDefinition(rawName, definition)
        }
    }

    private fun processDefinition(rawName: String, definition: Model) {
        if (definition is ModelImpl) {
            val kindType = this.dslMeta.kindType(rawName) != null

            generateDslClass(rawName, DslClassInfoModelImplAdapter.toDslClassInfo(definition), kindType)
        } else if (definition is RefModel) {
            // the description from the swagger file is not available
            // because the swagger parser does not allow a description on a RefModel

            // nothing to do - definition is deprecated, show message if toggled.
            if (showIgnoredRefModels) {
                println("Ignoring definition $rawName use ${definition.simpleRef} instead.")
            }

        } else {
            println("do not yet know how to handle a " + definition::class.java)
        }
    }

    /**
     * CRD definitions do not always provide dedicated definitions for objects.
     *
     * During generation we detect these 'missing' models and generate them after the main definitions are processed.
     *
     * As this can be a recursive problem, generating the 'missing' models is tried until no new 'missing' definitions
     * are added during a generate DSL class iteration.
     */
    private fun processAdditionalDefinitions() {
        var definitionsCompensatedCount = 0

        while (this.additionalPropertyDefinitions.isNotEmpty()) {

            val toProcess = mutableMapOf<String, DslClassInfo>()
            toProcess.putAll(this.additionalPropertyDefinitions)
            this.additionalPropertyDefinitions.clear()

            toProcess.forEach { (name, dslClassInfo) ->
                val kind = this.dslMeta.kindType(name)

                if (kind == null) {
                    generateDslClass(name, dslClassInfo, false)
                } else {
                    generateDslClass(name, dslClassInfo, true)
                }
            }

            definitionsCompensatedCount += toProcess.size

        }
    }

    private fun generateDslClass(absoluteName: String, dslClassInfo: DslClassInfo, kindType: Boolean) {
        if (dslClassInfo.type == "object" || dslClassInfo.properties.isNotEmpty()) {
            generateDslClassInternal(absoluteName, dslClassInfo, kindType)
        } else if (dslClassInfo.type == "string") {
            generateDslClassInternal(absoluteName, dslClassInfo, kindType)
        } else {
            if (dslClassInfo.description != null && !dslClassInfo.description.startsWith("Deprecated")) {
                generateDslClassInternal(absoluteName, dslClassInfo, kindType)
            } else {
                println("[SKIPPED] $absoluteName is not an object")
            }
        }
    }

    private fun generateDslClassInternal(rawName: String, dslClassInfo: DslClassInfo, kindType: Boolean) {
        val typeName = DslTypeName(rawName)

        val documentation = dslClassInfo.description ?: ""

        if (dslClassInfo.type == "object" || dslClassInfo.properties.isNotEmpty()) {
            val typeDependencies = determineModelTypeDependencies(dslClassInfo, rawName)
            val attributes = determineModelAttributes(dslClassInfo, rawName)

            typeDependencies.forEach { dependentRawName ->
                if (!this.dslMeta.typeMeta.containsKey(DslTypeName(dependentRawName).absoluteName)) {
                    if (!this.additionalPropertyDefinitions.containsKey(dependentRawName)) {
                        val definition = this.spec.definitions[dependentRawName]
                        if (definition != null && definition is ModelImpl) {
                            this.additionalPropertyDefinitions[dependentRawName] = DslClassInfoModelImplAdapter.toDslClassInfo(definition)
                        }
                    }
                }
            }

            this.dslMeta.registerType(
                DslObjectTypeMeta(
                    typeName,
                    documentation,
                    typeDependencies.map(::DslTypeName).toSet(),
                    attributes,
                    kindType
                )
            )
        }
        else if (dslClassInfo.type == "string") {

            when {
                dslClassInfo.format == null -> this.dslMeta.registerType(
                    DslContainerTypeMeta(
                        typeName,
                        documentation,
                        emptySet(),
                        DslTypeName("String")
                    )
                )
                dslClassInfo.format == "date-time" -> {
                    val containedType = DslTypeName("java.time.ZonedDateTime")
                    this.dslMeta.registerType(
                        DslContainerTypeMeta(
                            typeName,
                            documentation,
                            setOf(containedType),
                            containedType
                        )
                    )
                }
                dslClassInfo.format.contains("-or-") -> {
                    val sealedTypes = mutableMapOf<String, DslTypeName>()

                    val splits = dslClassInfo.format.split("-or-")

                    for (split in splits) {
                        var valueType = ""
                        if (split == "int") {
                            valueType = "Int"
                        } else if (split == "string") {
                            valueType = "String"
                        }

                        check(valueType != "") { "Don't know how to handle type split [$split] for $typeName" }

                        sealedTypes[split.toLowerCase()] =
                            DslTypeName(valueType)
                    }

                    this.dslMeta.registerType(
                        DslSealedTypeMeta(
                            typeName,
                            documentation,
                            emptySet(),
                            sealedTypes
                        )
                    )
                }
                else -> println("[SKIPPED] $typeName don't know how to handle format: ${dslClassInfo.format}")
            }

        }
        else {
            this.dslMeta.registerType(
                DslInterfaceTypeMeta(
                    typeName,
                    documentation,
                    emptySet()
                )
            )
        }
    }

    private fun determineModelTypeDependencies(dslClassInfo: DslClassInfo, rawName: String): Set<String> {
        val typeDependencies = mutableSetOf<String>()

        dslClassInfo.properties.forEach { (name, property) ->

            var typeDependency: String? = null
            when {
                isObjectProperty(property) -> typeDependency = this.kotlinTypeAbsolute(property, rawName, name)
                isListProperty(property) -> typeDependency = this.listPropertyItemType(property, rawName, name)
                isMapProperty(property) -> typeDependency = this.mapPropertyValueType(property, rawName, name)
            }

            if (typeDependency != null && DslTypeName(typeDependency).requiresImport()) {
                typeDependencies.add(typeDependency)
            }
        }

        return typeDependencies
    }

    private fun determineModelAttributes(dslClassInfo: DslClassInfo, rawName: String): Map<String, DslAttributeMeta> {
        val attributes = mutableMapOf<String, DslAttributeMeta>()

        var propertyPosition = 0
        dslClassInfo.properties.forEach { (name, property) ->

            val documentation = property.description ?: ""
            val required = this.isPropertyRequired(dslClassInfo, property)

            if (!documentation.toLowerCase().contains("read-only")) {
                propertyPosition++

                if (this.isListProperty(property)) {
                    val itemType = this.listPropertyItemType(property, rawName, name)

                    if (itemType != null) {
                        attributes[name] = DslListAttributeMeta(
                            name,
                            documentation,
                            required,
                            DslTypeName(itemType)
                        )
                    }
                } else if (this.isMapProperty(property)) {
                    val itemType = this.mapPropertyValueType(property, rawName, name)

                    if (itemType != null) {
                        attributes[name] = DslMapAttributeMeta(
                            name,
                            documentation,
                            required,
                            // Swagger file does not provide key type information
                            // https://swagger.io/docs/specification/data-models/dictionaries/
                            // fragment: "...OpenAPI lets you define dictionaries where the keys are strings..."
                            DslTypeName("String"),
                            DslTypeName(itemType)
                        )
                    }
                } else {
                    val type = this.kotlinTypeAbsolute(property, rawName, name)

                    if (type != null) {
                        attributes[name] = DslObjectAttributeMeta(
                                name,
                                documentation,
                                required,
                                DslTypeName(type)
                        )
                    }
                }
            }

        }


        return attributes
    }

    private fun isPropertyRequired(dslClassInfo: DslClassInfo, property: Property): Boolean {
        return dslClassInfo.required.contains(property.name)
    }

    private fun isObjectProperty(property: Property): Boolean {
        return property is RefProperty || property is ObjectProperty
    }

    private fun isListProperty(property: Property): Boolean {
        return property is ArrayProperty
    }

    private fun listPropertyItemType(property: Property, rawOwningTypeName: String, listPropertyName: String): String? {
        if (isListProperty(property)) {
            val listProperty = property as ArrayProperty

            return this.kotlinTypeAbsolute(listProperty.items, rawOwningTypeName, listPropertyName + "Item")
        } else {
            throw IllegalArgumentException("property is not of correct type!" + property::javaClass)
        }
    }

    private fun isMapProperty(property: Property): Boolean {
        return property is MapProperty
    }

    private fun mapPropertyValueType(property: Property, rawOwningTypeName: String, mapPropertyName: String): String? {
        if (isMapProperty(property)) {
            val mapProperty = property as MapProperty

            return if (this.isListProperty(mapProperty.additionalProperties)) {
                this.listPropertyItemType(mapProperty.additionalProperties, rawOwningTypeName, mapPropertyName + "Value")
            } else {
                this.kotlinTypeAbsolute(mapProperty.additionalProperties, rawOwningTypeName, mapPropertyName + "Value")
            }

        } else {
            throw IllegalArgumentException("property is not of correct type!" + property::javaClass)
        }
    }

    private fun kotlinTypeAbsolute(property: Property, rawOwningTypeName: String, owningAttributeName: String): String? {
        var propertyType : String? = null

        when (property) {
            is RefProperty ->
                propertyType = property.simpleRef
            is StringProperty ->
                propertyType = if (property.format == "byte") {
                    // TODO not sure this is the best type - but will do for now (shows we can detect the difference).
                    "ByteArray"
                } else {
                    "String"
                }
            is LongProperty -> propertyType = "Long"
            is IntegerProperty -> propertyType = "Int"
            is BaseIntegerProperty -> propertyType = "Int"
            is BooleanProperty -> propertyType = "Boolean"
            is DoubleProperty -> propertyType = "Double"
            is DateTimeProperty -> propertyType = "java.time.ZonedDateTime"
            is ArrayProperty -> throw IllegalStateException("should not be called for ArrayProperty")
            is MapProperty -> throw IllegalStateException("should not be called for MapProperty")
            // TODO this is a fallback - not sure what type will actually work.
            is UntypedProperty -> propertyType = "String"
            is ObjectProperty -> {
                if (property.name == null) {
                    /*
                    Here we start compensating for missing definitions.

                    This is needed because CRD definitions do not always model every object to a dedicated model
                    in the spec.

                    This basically means they have types where they do not specify a name for.
                    Even if it is used in multiple places and even if the type exists in the standard platform types
                    they do not always reference them (not sure this is even possible).
                     */
                    propertyType = rawOwningTypeName + owningAttributeName.capitalize()
                    if (!this.dslMeta.typeMeta.containsKey(DslTypeName(propertyType).absoluteName)) {
                        if (!this.additionalPropertyDefinitions.containsKey(propertyType)) {
                            val additionalDslClassInfo = DslClassInfoObjectPropertyAdapter.toDslClassInfo(property)
                            this.additionalPropertyDefinitions[propertyType] = additionalDslClassInfo
                        }
                    }
                }
            }
            else -> println("[WARN] unhandled property ${property.name} of type ${property.type}")
        }

        return propertyType
    }
}