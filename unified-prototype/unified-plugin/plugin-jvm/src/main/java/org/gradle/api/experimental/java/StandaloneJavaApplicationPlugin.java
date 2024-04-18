package org.gradle.api.experimental.java;

import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.experimental.jvm.internal.JvmPluginSupport;
import org.gradle.api.internal.plugins.software.SoftwareType;
import org.gradle.api.plugins.ApplicationPlugin;
import org.gradle.api.plugins.JavaPluginExtension;
import org.gradle.jvm.toolchain.JavaLanguageVersion;

/**
 * Creates a declarative {@link JavaApplication} DSL model, applies the official Java application plugin,
 * and links the declarative model to the official plugin.
 */
abstract public class StandaloneJavaApplicationPlugin implements Plugin<Project> {
    @SoftwareType(name = "javaApplication", modelPublicType = JavaApplication.class)
    abstract public JavaApplication getApplication();

    @Override
    public void apply(Project project) {
        JavaApplication dslModel = getApplication();

        project.getPlugins().apply(ApplicationPlugin.class);

        linkDslModelToPlugin(project, dslModel);
    }

    private void linkDslModelToPlugin(Project project, JavaApplication dslModel) {
        JavaPluginExtension java = project.getExtensions().getByType(JavaPluginExtension.class);
        java.getToolchain().getLanguageVersion().set(dslModel.getJavaVersion().map(JavaLanguageVersion::of));

        JvmPluginSupport.linkApplicationMainClass(project, dslModel);
        JvmPluginSupport.linkSourceSetToDependencies(project, java.getSourceSets().getByName("main"), dslModel.getDependencies());
    }
}
