/*
 * Copyright 2010 the original author or authors.
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
package org.gradle.plugins.eclipse

import org.gradle.api.Project
import org.gradle.api.internal.plugins.IdePlugin
import org.gradle.api.plugins.GroovyBasePlugin
import org.gradle.api.plugins.JavaBasePlugin
import org.gradle.api.plugins.JavaPlugin
import org.gradle.api.plugins.WarPlugin
import org.gradle.api.plugins.scala.ScalaBasePlugin
import org.gradle.api.JavaVersion
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.plugins.eclipse.model.BuildCommand
import org.gradle.plugins.eclipse.model.Facet
import org.gradle.plugins.eclipse.model.Library

/**
 * <p>A plugin which generates Eclipse files.</p>
 *
 * @author Hans Dockter
 */
class EclipsePlugin extends IdePlugin {
    static final String ECLIPSE_TASK_NAME = "eclipse"
    static final String CLEAN_ECLIPSE_TASK_NAME = "cleanEclipse"
    static final String ECLIPSE_PROJECT_TASK_NAME = "eclipseProject"
    static final String ECLIPSE_WTP_COMPONENT_TASK_NAME = "eclipseWtpComponent"
    static final String ECLIPSE_WTP_FACET_TASK_NAME = "eclipseWtpFacet"
    static final String ECLIPSE_CP_TASK_NAME = "eclipseClasspath"
    static final String ECLIPSE_JDT_TASK_NAME = "eclipseJdt"

    @Override protected String getLifecycleTaskName() {
        return 'eclipse'
    }

    @Override protected void onApply(Project project) {
        lifecycleTask.description = 'Generates the Eclipse files.'
        cleanTask.description = 'Cleans the generated Eclipse files.'
        configureEclipseProject(project)
        configureEclipseClasspath(project)
        configureEclipseJdt(project)
        configureEclipseWtpComponent(project)
        configureEclipseWtpFacet(project)
    }

    private void configureEclipseProject(Project project) {
        addEclipsePluginTask(project, this, ECLIPSE_PROJECT_TASK_NAME, EclipseProject) {
            projectName = project.name
            description = "Generates the Eclipse .project file."
            inputFile = project.file('.project')
            outputFile = project.file('.project')
            conventionMapping.comment = { project.description }

            project.plugins.withType(JavaBasePlugin) {
                buildCommand "org.eclipse.jdt.core.javabuilder"
                natures "org.eclipse.jdt.core.javanature"
            }

            project.plugins.withType(GroovyBasePlugin) {
                natures.add(natures.indexOf("org.eclipse.jdt.core.javanature"), "org.eclipse.jdt.groovy.core.groovyNature")
            }

            project.plugins.withType(ScalaBasePlugin) {
                buildCommands.set(buildCommands.findIndexOf { it.name == "org.eclipse.jdt.core.javabuilder" },
                        new BuildCommand("ch.epfl.lamp.sdt.core.scalabuilder"))
                natures.add(natures.indexOf("org.eclipse.jdt.core.javanature"), "ch.epfl.lamp.sdt.core.scalanature")
            }

            project.plugins.withType(WarPlugin) {
                buildCommand 'org.eclipse.wst.common.project.facet.core.builder'
                buildCommand 'org.eclipse.wst.validation.validationbuilder'
                natures 'org.eclipse.wst.common.project.facet.core.nature'
                natures 'org.eclipse.wst.common.modulecore.ModuleCoreNature'
                natures 'org.eclipse.jem.workbench.JavaEMFNature'

                eachDependedUponProject(project) { Project otherProject ->
                    configureTask(otherProject, ECLIPSE_PROJECT_TASK_NAME) {
                        buildCommand 'org.eclipse.wst.common.project.facet.core.builder'
                        buildCommand 'org.eclipse.wst.validation.validationbuilder'
                        natures 'org.eclipse.wst.common.project.facet.core.nature'
                        natures 'org.eclipse.wst.common.modulecore.ModuleCoreNature'
                        natures 'org.eclipse.jem.workbench.JavaEMFNature'
                    }
                }
            }
        }
    }

    private void configureEclipseClasspath(Project project) {
        project.plugins.withType(JavaBasePlugin) {
            addEclipsePluginTask(project, this, ECLIPSE_CP_TASK_NAME, EclipseClasspath) {
                description = "Generates the Eclipse .classpath file."
                containers 'org.eclipse.jdt.launching.JRE_CONTAINER'
                sourceSets = project.sourceSets
                inputFile = project.file('.classpath')
                outputFile = project.file('.classpath')
                conventionMapping.defaultOutputDir = { new File(project.projectDir, 'bin') }

                project.plugins.withType(JavaPlugin) {
                    plusConfigurations = [project.configurations.testRuntime]
                }

                project.plugins.withType(WarPlugin) {
                    eachDependedUponProject(project) { Project otherProject ->
                        configureTask(otherProject, ECLIPSE_CP_TASK_NAME) {
                            entryConfigurers << { Library library ->
                                // TODO: what's the correct value here?
                                library.entryAttributes['org.eclipse.jst.component.dependency'] = '../'
                            }
                        }
                    }
                }
            }
        }
    }

    private void configureEclipseJdt(Project project) {
        project.plugins.withType(JavaBasePlugin) {
            addEclipsePluginTask(project, this, ECLIPSE_JDT_TASK_NAME, EclipseJdt) {
                description = "Generates the Eclipse JDT settings file."
                outputFile = project.file('.settings/org.eclipse.jdt.core.prefs')
                inputFile = project.file('.settings/org.eclipse.jdt.core.prefs')
                conventionMapping.sourceCompatibility = { project.sourceCompatibility }
                conventionMapping.targetCompatibility = { project.targetCompatibility }
            }
        }
    }

    private void configureEclipseWtpComponent(Project project) {
        project.plugins.withType(WarPlugin) {
            addEclipsePluginTask(project, this, ECLIPSE_WTP_COMPONENT_TASK_NAME, EclipseWtpComponent) {
                description = 'Generates the org.eclipse.wst.common.component file for Eclipse WTP.'
                deployName = project.name
                sourceSets = project.sourceSets.matching { sourceSet -> sourceSet.name == 'main' }
                plusConfigurations = [project.configurations.runtime]
                inputFile = project.file('.settings/org.eclipse.wst.common.component')
                outputFile = project.file('.settings/org.eclipse.wst.common.component')

                conventionMapping.contextPath = { project.war.baseName }
                minusConfigurations = [project.configurations.providedRuntime]
                resource deployPath: '/', sourcePath: project.convention.plugins.war.webAppDirName
            }

            eachDependedUponProject(project) { otherProject ->
                // require Java plugin because we need source sets and configurations
                otherProject.plugins.withType(JavaPlugin) {
                    addEclipsePluginTask(otherProject, ECLIPSE_WTP_COMPONENT_TASK_NAME, EclipseWtpComponent) {
                        description = 'Generates the org.eclipse.wst.common.component file for Eclipse WTP.'
                        deployName = otherProject.name
                        sourceSets = otherProject.sourceSets.matching { sourceSet -> sourceSet.name == 'main' }
                        plusConfigurations = [otherProject.configurations.runtime]
                        inputFile = otherProject.file('.settings/org.eclipse.wst.common.component')
                        outputFile = otherProject.file('.settings/org.eclipse.wst.common.component')

                        // remove the provided dependencies specified in the War (!) project
                        minusConfigurations = [project.configurations.providedRuntime]
                    }
                }
            }
        }
    }

    private void configureEclipseWtpFacet(Project project) {
        project.plugins.withType(WarPlugin) {
            addEclipsePluginTask(project, this, ECLIPSE_WTP_FACET_TASK_NAME, EclipseWtpFacet) {
                description = 'Generates the org.eclipse.wst.common.project.facet.core.xml settings file for Eclipse WTP.'
                inputFile = project.file('.settings/org.eclipse.wst.common.project.facet.core.xml')
                outputFile = project.file('.settings/org.eclipse.wst.common.project.facet.core.xml')
                conventionMapping.facets = { [new Facet("jst.web", "2.4"), new Facet("jst.java", toJavaFacetVersion(project.sourceCompatibility))] }
            }

            eachDependedUponProject(project) { otherProject ->
                addEclipsePluginTask(otherProject, ECLIPSE_WTP_FACET_TASK_NAME, EclipseWtpFacet) {
                    description = 'Generates the org.eclipse.wst.common.project.facet.core.xml settings file for Eclipse WTP.'
                    inputFile = otherProject.file('.settings/org.eclipse.wst.common.project.facet.core.xml')
                    outputFile = otherProject.file('.settings/org.eclipse.wst.common.project.facet.core.xml')
                    conventionMapping.facets = { [new Facet("jst.utility", "1.0")] }
                    otherProject.plugins.withType(JavaPlugin) {
                        conventionMapping.facets = {
                            [new Facet("jst.utility", "1.0"), new Facet("jst.java",
                                    toJavaFacetVersion(otherProject.sourceCompatibility))]
                        }
                    }
                }
            }
        }
    }

    private void eachDependedUponProject(Project project, Closure action) {
        project.afterEvaluate {
            def runtimeConfig = project.configurations.findByName("runtime")
            if (runtimeConfig) {
                def projectDeps = runtimeConfig.getAllDependencies(ProjectDependency)
                projectDeps*.dependencyProject.each(action)
            }
        }
    }

    private void withTask(Project project, String taskName, Closure action) {
        project.tasks.matching { it.name == taskName }.all(action)
    }

    private void configureTask(Project project, String taskName, Closure action) {
        withTask(project, taskName) { task ->
            project.configure(task, action)
        }
    }

    // note: we only add and configure the task if it doesn't exist yet
    private void addEclipsePluginTask(Project project, EclipsePlugin plugin = null, String taskName, Class taskType, Closure action) {
        if (plugin) {
            doAddEclipsePluginTask(project, plugin, taskName, taskType, action)
        } else {
            project.plugins.withType(EclipsePlugin) { EclipsePlugin otherPlugin ->
                doAddEclipsePluginTask(project, otherPlugin, taskName, taskType, action)
            }
        }
    }

    private void doAddEclipsePluginTask(Project project, EclipsePlugin plugin, String taskName, Class taskType, Closure action) {
        if (project.tasks.findByName(taskName)) { return }

        def task = project.tasks.add(taskName, taskType)
        project.configure(task, action)
        plugin.addWorker(task)
    }

    private String toJavaFacetVersion(JavaVersion version) {
        if (version == JavaVersion.VERSION_1_5) {
            return '5.0'
        }
        if (version == JavaVersion.VERSION_1_6) {
            return '6.0'
        }
        return version.toString()
    }
}
