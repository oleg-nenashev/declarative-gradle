package org.gradle.api.experimental.kotlin;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.experimental.jvm.internal.JvmPluginSupport;
import org.gradle.api.internal.plugins.software.SoftwareType;
import org.gradle.api.plugins.ApplicationPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.jvm.toolchain.JavaLanguageVersion;
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension;

/**
 * Creates a declarative {@link KotlinJvmApplication} DSL model, applies the official Kotlin and application plugin,
 * and links the declarative model to the official plugin.
 */
abstract public class StandaloneKotlinJvmApplicationPlugin implements Plugin<Project> {
    @SoftwareType(name = "kotlinJvmApplication", modelPublicType = KotlinJvmApplication.class)
    abstract public KotlinJvmApplication getApplication();

    @Override
    public void apply(Project project) {
        KotlinJvmApplication dslModel = getApplication();

        project.getPlugins().apply(ApplicationPlugin.class);
        project.getPlugins().apply("org.jetbrains.kotlin.jvm");

        linkDslModelToPlugin(project, dslModel);
    }

    private void linkDslModelToPlugin(Project project, KotlinJvmApplication dslModel) {
        KotlinJvmProjectExtension kotlin = project.getExtensions().getByType(KotlinJvmProjectExtension.class);
        kotlin.jvmToolchain(spec -> spec.getLanguageVersion().set(dslModel.getJavaVersion().map(JavaLanguageVersion::of)));

        JvmPluginSupport.linkApplicationMainClass(project, dslModel);

        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        JvmPluginSupport.linkSourceSetToDependencies(project, java.getSourceSets().getByName("main"), dslModel.getDependencies());
    }
}
