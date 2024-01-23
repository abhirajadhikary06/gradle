/*
 * Copyright 2023 the original author or authors.
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

package org.gradle.internal.restricteddsl.project

import com.h0tk3y.kotlin.staticObjectNotation.Restricted
import com.h0tk3y.kotlin.staticObjectNotation.analysis.AnalysisStatementFilter
import com.h0tk3y.kotlin.staticObjectNotation.analysis.DataProperty
import com.h0tk3y.kotlin.staticObjectNotation.analysis.DataTypeRef
import com.h0tk3y.kotlin.staticObjectNotation.analysis.FqName
import com.h0tk3y.kotlin.staticObjectNotation.analysis.ObjectOrigin
import com.h0tk3y.kotlin.staticObjectNotation.language.DataStatement
import com.h0tk3y.kotlin.staticObjectNotation.language.FunctionArgument
import com.h0tk3y.kotlin.staticObjectNotation.language.FunctionCall
import com.h0tk3y.kotlin.staticObjectNotation.mappingToJvm.RestrictedRuntimeProperty
import com.h0tk3y.kotlin.staticObjectNotation.mappingToJvm.RuntimePropertyResolver
import com.h0tk3y.kotlin.staticObjectNotation.schemaBuilder.CollectedPropertyInformation
import com.h0tk3y.kotlin.staticObjectNotation.schemaBuilder.DefaultFunctionExtractor
import com.h0tk3y.kotlin.staticObjectNotation.schemaBuilder.DefaultPropertyExtractor
import com.h0tk3y.kotlin.staticObjectNotation.schemaBuilder.PropertyExtractor
import com.h0tk3y.kotlin.staticObjectNotation.schemaBuilder.isPublicAndRestricted
import com.h0tk3y.kotlin.staticObjectNotation.schemaBuilder.kotlinFunctionAsConfigureLambda
import com.h0tk3y.kotlin.staticObjectNotation.schemaBuilder.plus
import com.h0tk3y.kotlin.staticObjectNotation.schemaBuilder.schemaFromTypes
import com.h0tk3y.kotlin.staticObjectNotation.schemaBuilder.treatInterfaceAsConfigureLambda
import org.gradle.api.Action
import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.internal.initialization.ClassLoaderScope
import org.gradle.api.internal.initialization.ScriptHandlerFactory
import org.gradle.api.internal.project.ProjectInternal
import org.gradle.groovy.scripts.ScriptSource
import org.gradle.internal.restricteddsl.evaluationSchema.EvaluationSchema
import org.gradle.internal.restricteddsl.evaluationSchema.InterpretationSequence
import org.gradle.internal.restricteddsl.evaluationSchema.InterpretationSequenceStep
import org.gradle.internal.restricteddsl.plugins.PluginsTopLevelReceiver
import org.gradle.internal.restricteddsl.plugins.schemaForPluginsBlock
import org.gradle.plugin.management.internal.DefaultPluginRequest
import org.gradle.plugin.management.internal.PluginRequestInternal
import org.gradle.plugin.management.internal.PluginRequests
import org.gradle.plugin.use.internal.DefaultPluginId
import org.gradle.plugin.use.internal.PluginRequestApplicator
import kotlin.reflect.KClass
import kotlin.reflect.full.createType
import kotlin.reflect.full.isSubclassOf


internal
fun projectInterpretationSequence(
    target: ProjectInternal,
    targetScope: ClassLoaderScope,
    scriptSource: ScriptSource
) = InterpretationSequence(
    listOf(
        step1Plugins(target, targetScope, scriptSource),
        step2Project(target, targetScope)
    )
)


private
fun step1Plugins(target: ProjectInternal, targetScope: ClassLoaderScope, scriptSource: ScriptSource) =
    object : InterpretationSequenceStep<PluginsTopLevelReceiver> {
        override val stepIdentifier: String = "plugins"

        override fun evaluationSchemaForStep(): EvaluationSchema =
            EvaluationSchema(
                schemaForPluginsBlock,
                analysisStatementFilter = analyzeTopLevelPluginsBlockOnly
            )

        override fun topLevelReceiver() = PluginsTopLevelReceiver()

        override fun whenEvaluated(resultReceiver: PluginsTopLevelReceiver) {
            val pluginRequests = resultReceiver.plugins.specs.map {
                DefaultPluginRequest(DefaultPluginId.unvalidated(it.id), it.apply, PluginRequestInternal.Origin.OTHER, scriptSource.displayName, null, it.version, null, null, null) }
            val scriptHandler = target.services.get(ScriptHandlerFactory::class.java).create(scriptSource, targetScope)
            target.services.get(PluginRequestApplicator::class.java)
                .applyPlugins(PluginRequests.of(pluginRequests), PluginRequests.of(emptyList()), scriptHandler, target.pluginManager, targetScope)

            targetScope.lock()
        }
    }


private
fun step2Project(target: ProjectInternal, targetScope: ClassLoaderScope) = object : InterpretationSequenceStep<ProjectInternal> {
    override val stepIdentifier: String = "project"

    override fun evaluationSchemaForStep(): EvaluationSchema {
        val annotatedTypesChecker = CachedHierarchyAnnotationChecker(Restricted::class)

        val projectAccessors = projectAccessorsSupport(targetScope)
        val configureLambdas = treatInterfaceAsConfigureLambda(Action::class).plus(kotlinFunctionAsConfigureLambda)
        val projectExtensionComponents = projectExtensionComponents(target) { annotatedTypesChecker.isAnnotatedMaybeInSupertypes(it) }
        val dependencyConfigurationComponents = dependencyConfigurationSchemaComponents(target)
        val propertiesComponents = schemaFromPropertiesComponents()

        return EvaluationSchema(
            schemaFromTypes(
                ProjectTopLevelReceiver::class,
                listOf(
                    ProjectTopLevelReceiver::class,

                    // Dependencies:
                    RestrictedDependenciesHandler::class,
                    ProjectDependency::class
                ) + projectAccessors.typesFromExtensions,
                configureLambdas = configureLambdas,
                propertyExtractor = propertiesComponents.propertyExtractor +
                    DefaultPropertyExtractor()
                    + projectAccessors.propertyExtractor,
                functionExtractor = DefaultFunctionExtractor({ isPublicAndRestricted.shouldIncludeMember(it) && !isGradlePropertyType(it.returnType) }, configureLambdas = configureLambdas)
                    + projectExtensionComponents.functionExtractor
                    + dependencyConfigurationComponents.functionExtractor,
                typeDiscovery = DependencyDslTypeDiscovery()
                    + projectExtensionComponents.typeDiscovery
                    + ThirdPartyTypesDiscovery(isPublicAndRestricted, configureLambdas)
                    + propertiesComponents.typeDiscovery,
            ),
            analysisStatementFilter = analyzeEverythingExceptPluginsBlock,
            runtimePropertyResolvers = listOf(hardcodeProjectExtensionsProperties),
            runtimeFunctionResolvers = listOf(dependencyConfigurationComponents.runtimeFunctionResolver),
            runtimeCustomAccessors = listOf(projectExtensionComponents.runtimeCustomAccessors)
        )
    }

    override fun topLevelReceiver(): ProjectInternal = target

    override fun whenEvaluated(resultReceiver: ProjectInternal) = Unit
}


private
val analyzeTopLevelPluginsBlockOnly = AnalysisStatementFilter { statement, scopes ->
    if (scopes.last().receiver is ObjectOrigin.TopLevelReceiver) {
        isPluginsCall(statement)
    } else true
}


private
val analyzeEverythingExceptPluginsBlock = AnalysisStatementFilter { statement, scopes ->
    if (scopes.last().receiver is ObjectOrigin.TopLevelReceiver) {
        !isPluginsCall(statement)
    } else true
}


private
fun isPluginsCall(statement: DataStatement) =
    statement is FunctionCall && statement.name == "plugins" && statement.args.size == 1 && statement.args.single() is FunctionArgument.Lambda


private
class ProjectAccessorsSupport(val typesFromExtensions: List<KClass<*>>, val propertyExtractor: PropertyExtractor)


private
fun projectAccessorsSupport(targetScope: ClassLoaderScope): ProjectAccessorsSupport {
    val projectAccessorsClass = try {
        targetScope.localClassLoader.loadClass("org.gradle.accessors.dm.RootProjectAccessor").kotlin
    } catch (e: ClassNotFoundException) {
        return ProjectAccessorsSupport(emptyList(), PropertyExtractor.none)
    }

    val projectAccessorsExtension = CollectedPropertyInformation(
        "projects",
        projectAccessorsClass.createType(),
        returnType = DataTypeRef.Name(FqName.parse(projectAccessorsClass.qualifiedName!!)),
        propertyMode = DataProperty.PropertyMode.READ_ONLY,
        hasDefaultValue = true,
        isHiddenInRestrictedDsl = false,
        isDirectAccessOnly = false
    )

    val propertyExtractor =
        DependencyDslAccessorsProducer() +
            ExtensionProperties(mapOf(ProjectTopLevelReceiver::class to listOf(projectAccessorsExtension)))

    return ProjectAccessorsSupport(
        typesFromExtensions = listOf(projectAccessorsClass),
        propertyExtractor = propertyExtractor
    )
}


private
val hardcodeProjectExtensionsProperties = object : RuntimePropertyResolver {
    override fun resolvePropertyRead(receiverClass: KClass<*>, name: String): RuntimePropertyResolver.Resolution {
        if (receiverClass.isSubclassOf(Project::class) && name == "projects") {
            return RuntimePropertyResolver.Resolution.Resolved(object : RestrictedRuntimeProperty {
                override fun getValue(receiver: Any) = (receiver as Project).extensions.getByName("projects")
                override fun setValue(receiver: Any, value: Any?): Unit = throw UnsupportedOperationException()
            })
        }
        return RuntimePropertyResolver.Resolution.Unresolved
    }

    override fun resolvePropertyWrite(receiverClass: KClass<*>, name: String): RuntimePropertyResolver.Resolution = RuntimePropertyResolver.Resolution.Unresolved
}
