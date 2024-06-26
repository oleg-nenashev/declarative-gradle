package org.gradle.api.experimental.android.library;

import com.android.build.api.dsl.*;
import org.gradle.api.JavaVersion;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Plugin;
import org.gradle.api.Project;
import org.gradle.api.artifacts.ConfigurationContainer;
import org.gradle.api.artifacts.dsl.DependencyHandler;
import org.gradle.api.experimental.android.DEFAULT_SDKS;
import org.gradle.api.experimental.android.extensions.testing.AndroidTestDependencies;
import org.gradle.api.experimental.android.extensions.testing.TestOptions;
import org.gradle.api.experimental.android.extensions.testing.Testing;
import org.gradle.api.internal.plugins.software.SoftwareType;
import org.gradle.api.provider.Provider;
import org.jetbrains.kotlin.gradle.dsl.KotlinAndroidProjectExtension;
import org.gradle.api.experimental.android.nia.NiaSupport;
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmOptions;
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile;

import java.io.File;
import java.nio.file.Path;
import java.util.*;

import static org.gradle.api.experimental.android.AndroidDSLSupport.ifPresent;

/**
 * Creates a declarative {@link AndroidLibrary} DSL model, applies the official Android plugin,
 * and links the declarative model to the official plugin.
 */
@SuppressWarnings("UnstableApiUsage")
public abstract class StandaloneAndroidLibraryPlugin implements Plugin<Project> {
    @SoftwareType(name = "androidLibrary", modelPublicType=AndroidLibrary.class)
    abstract public AndroidLibrary getAndroidLibrary();

    @Override
    public void apply(Project project) {
        AndroidLibrary dslModel = getAndroidLibrary();

        // Setup Android Library conventions
        dslModel.getJdkVersion().convention(DEFAULT_SDKS.JDK);
        dslModel.getCompileSdk().convention(DEFAULT_SDKS.TARGET_ANDROID_SDK);
        dslModel.getMinSdk().convention(DEFAULT_SDKS.MIN_ANDROID_SDK); // https://developer.android.com/build/multidex#mdex-gradle

        dslModel.getBuildTypes().getDebug().getMinify().getEnabled().convention(false);
        dslModel.getBuildTypes().getRelease().getMinify().getEnabled().convention(false);

        // Setup desugaring conventions and desugar automatically when JDK > 8 is targeted
        dslModel.getCoreLibraryDesugaring().getEnabled().convention(project.provider(() -> dslModel.getJdkVersion().get() > 8));
        dslModel.getCoreLibraryDesugaring().getLibVersion().convention("2.0.4");

        dslModel.getKotlinSerialization().getEnabled().convention(false);
        dslModel.getKotlinSerialization().getVersion().convention("1.6.3");
        dslModel.getKotlinSerialization().getJsonEnabled().convention(false);

        dslModel.getFeature().getEnabled().convention(false);
        dslModel.getCompose().getEnabled().convention(false);
        dslModel.getHilt().getEnabled().convention(false);

        // Setup Test Options conventions
        dslModel.getTesting().getTestOptions().getIncludeAndroidResources().convention(false);
        dslModel.getTesting().getTestOptions().getReturnDefaultValues().convention(false);
        dslModel.getTesting().getJacoco().getEnabled().convention(false);
        dslModel.getTesting().getJacoco().getVersion().convention("0.8.7");

        // Register an afterEvaluate listener before we apply the Android plugin to ensure we can
        // run actions before Android does.
        project.afterEvaluate(p -> linkDslModelToPlugin(p, dslModel));

        // Apply the official Android plugin and support for Kotlin
        project.getPlugins().apply("com.android.library");
        project.getPlugins().apply("org.jetbrains.kotlin.android");

        // After AGP creates configurations, link deps to the collectors
        linkCommonDependencies(dslModel.getDependencies(), project.getConfigurations());
    }

    /**
     * Performs linking actions that must occur within an afterEvaluate block.
     */
    public void linkDslModelToPlugin(Project project, AndroidLibrary dslModel) {
        LibraryExtension android = project.getExtensions().getByType(LibraryExtension.class);
        KotlinAndroidProjectExtension kotlin = project.getExtensions().getByType(KotlinAndroidProjectExtension.class);
        ConfigurationContainer configurations = project.getConfigurations();

        // Link common properties
        ifPresent(dslModel.getNamespace(), android::setNamespace);
        ifPresent(dslModel.getCompileSdk(), android::setCompileSdk);
        android.defaultConfig(defaultConfig -> {
            ifPresent(dslModel.getMinSdk(), defaultConfig::setMinSdk);
            return null;
        });
        android.compileOptions(compileOptions -> {
            // Up to Java 11 APIs are available through desugaring
            // https://developer.android.com/studio/write/java11-minimal-support-table
            compileOptions.setSourceCompatibility(JavaVersion.toVersion(dslModel.getJdkVersion().get()));
            compileOptions.setTargetCompatibility(JavaVersion.toVersion(dslModel.getJdkVersion().get()));
            return null;
        });
        ifPresent(dslModel.getJdkVersion(), jdkVersion -> {
            kotlin.jvmToolchain(jdkVersion);
            android.getCompileOptions().setSourceCompatibility(JavaVersion.toVersion(jdkVersion));
            android.getCompileOptions().setTargetCompatibility(JavaVersion.toVersion(jdkVersion));
        });

        // Link build types
        AndroidLibraryBuildTypes modelBuildType = dslModel.getBuildTypes();
        NamedDomainObjectContainer<? extends LibraryBuildType> androidBuildTypes = android.getBuildTypes();
        linkBuildType(androidBuildTypes.getByName("debug"), modelBuildType.getDebug(), configurations);
        linkBuildType(androidBuildTypes.getByName("release"), modelBuildType.getRelease(), configurations);

        configureTesting(project, dslModel, android);
        
        configureKotlinSerialization(project, dslModel);
        configureDesugaring(project, dslModel, android);
        configureHilt(project, dslModel, android);
        configureCompose(project, dslModel, android);

        NiaSupport.configureNia(project, dslModel);
    }

    @SuppressWarnings("UnstableApiUsage")
    private void configureCompose(Project project, AndroidLibrary dslModel, LibraryExtension androidLib) {
        if (dslModel.getCompose().getEnabled().get()) {
            androidLib.getBuildFeatures().setCompose(true);

            androidLib.getComposeOptions().setKotlinCompilerExtensionVersion("1.5.12");

            DependencyHandler dependencies = project.getDependencies();
            dependencies.add("implementation", dependencies.platform("androidx.compose:compose-bom:2024.02.02"));
            dependencies.add("androidTestImplementation", dependencies.platform("androidx.compose:compose-bom:2024.02.02"));
            dependencies.add("implementation", "androidx.compose.ui:ui-tooling-preview");

            dslModel.getBuildTypes().getDebug().getDependencies().getImplementation().add("androidx.compose.ui:ui-tooling");

            androidLib.getTestOptions().getUnitTests().setIncludeAndroidResources(true); // For Robolectric

            project.getTasks().withType(KotlinCompile.class).configureEach(task -> {
                KotlinJvmOptions kotlinOptions = task.getKotlinOptions();
                List<String> freeCompilerArgs = new ArrayList<>();
                freeCompilerArgs.addAll(buildComposeMetricsParameters(project));
                freeCompilerArgs.addAll(stabilityConfiguration(project, dslModel));
                freeCompilerArgs.addAll(strongSkippingConfiguration(dslModel));
                kotlinOptions.setFreeCompilerArgs(freeCompilerArgs);
            });
        }
    }

    private List<String> buildComposeMetricsParameters(Project project) {
        List<String> metricParameters = new ArrayList<>();
        Path relativePath = project.getProjectDir().toPath().relativize(project.getRootDir().toPath());
        File buildDir = project.getLayout().getBuildDirectory().get().getAsFile();

        Provider<String> enableMetricsProvider = project.getProviders().gradleProperty("enableComposeCompilerMetrics");
        boolean enableMetrics = Objects.equals(enableMetricsProvider.getOrNull(), "true");
        if (enableMetrics) {
            Path metricsFolder = buildDir.toPath().resolve("compose-metrics").resolve(relativePath);
            metricParameters.add("-P");
            metricParameters.add("plugin:androidx.compose.compiler.plugins.kotlin:metricsDestination=" + metricsFolder.toAbsolutePath());
        }

        Provider<String> enableReportsProvider = project.getProviders().gradleProperty("enableComposeCompilerReports");
        boolean enableReports = Objects.equals(enableReportsProvider.getOrNull(), "true");
        if (enableReports) {
            Path reportsFolder = buildDir.toPath().resolve("compose-reports").resolve(relativePath);
            metricParameters.add("-P");
            metricParameters.add("plugin:androidx.compose.compiler.plugins.kotlin:reportsDestination=" + reportsFolder.toAbsolutePath());
        }

        return metricParameters;
    }

    private List<String> stabilityConfiguration(Project project, AndroidLibrary dslModel) {
        if (dslModel.getCompose().getStabilityConfigurationFilePath().isPresent()) {
            return Arrays.asList(
                    "-P",
                    "plugin:androidx.compose.compiler.plugins.kotlin:stabilityConfigurationPath=" + project.getRootDir().getAbsolutePath() + dslModel.getCompose().getStabilityConfigurationFilePath().get()
            );
        } else {
            return Collections.emptyList();
        }
    }

    private List<String> strongSkippingConfiguration(AndroidLibrary dslModel) {
        if (dslModel.getCompose().getExperimentalStrongSkipping().isPresent()) {
            return Arrays.asList(
                    "-P",
                    "plugin:androidx.compose.compiler.plugins.kotlin:experimentalStrongSkipping=" + dslModel.getCompose().getExperimentalStrongSkipping().get()
            );
        } else {
            return Collections.emptyList();
        }
    }

    private void configureHilt(Project project, AndroidLibrary dslModel, LibraryExtension android) {
        if (dslModel.getHilt().getEnabled().get()) {
            // Add support for KSP
            project.getPlugins().apply("com.google.devtools.ksp");
            project.getDependencies().add("ksp", "com.google.dagger:hilt-android-compiler:2.50");

            // Add support for Hilt
            project.getPlugins().apply("dagger.hilt.android.plugin");
            project.getDependencies().add("implementation", "com.google.dagger:hilt-android:2.50");
        }
    }

    private void configureDesugaring(Project project, AndroidLibrary dslModel, LibraryExtension android) {
        if (dslModel.getCoreLibraryDesugaring().getEnabled().get()) {
            android.compileOptions(compileOptions -> {
                compileOptions.setCoreLibraryDesugaringEnabled(dslModel.getCoreLibraryDesugaring().getEnabled().get());
                return null;
            });
            
            project.getDependencies().addProvider("coreLibraryDesugaring", dslModel.getCoreLibraryDesugaring().getLibVersion().map(version -> "com.android.tools:desugar_jdk_libs:" + version));
        }
    }

    private void configureTesting(Project project, AndroidLibrary dslModel, LibraryExtension android) {
        Testing testing = dslModel.getTesting();
        AndroidTestDependencies testDependencies = testing.getDependencies();
        TestOptions testOptions = testing.getTestOptions();

        UnitTestOptions unitTestOptions = android.getTestOptions().getUnitTests();
        unitTestOptions.setIncludeAndroidResources(testOptions.getIncludeAndroidResources().get());
        unitTestOptions.setReturnDefaultValues(testOptions.getReturnDefaultValues().get());

        ConfigurationContainer configurations = project.getConfigurations();
        configurations.getByName("testImplementation").fromDependencyCollector(testDependencies.getImplementation());
        configurations.getByName("androidTestImplementation").fromDependencyCollector(testDependencies.getAndroidImplementation());
    }

    private void configureKotlinSerialization(Project project, AndroidLibrary dslModel) {
        if (dslModel.getKotlinSerialization().getEnabled().get()) {
            project.getPlugins().apply("org.jetbrains.kotlin.plugin.serialization");
            project.getConfigurations().getByName("testImplementation").fromDependencyCollector(dslModel.getKotlinSerialization().getDependencies().getImplementation());

            if (dslModel.getKotlinSerialization().getJsonEnabled().get()) {
                project.getDependencies().addProvider("implementation", dslModel.getKotlinSerialization().getVersion().map(version -> "org.jetbrains.kotlinx:kotlinx-serialization-json:" + version));
            }
        }
    }

    /**
     * Performs common dependency linking actions that do not need to occur within an afterEvaluate block.
     */
    private void linkCommonDependencies(AndroidLibraryDependencies dependencies, ConfigurationContainer configurations) {
        configurations.getByName("implementation").fromDependencyCollector(dependencies.getImplementation());
        configurations.getByName("api").fromDependencyCollector(dependencies.getApi());
        configurations.getByName("compileOnly").fromDependencyCollector(dependencies.getCompileOnly());
        configurations.getByName("runtimeOnly").fromDependencyCollector(dependencies.getRuntimeOnly());
    }

    /**
     * Links build types from the model to the android extension.
     */
    private void linkBuildType(LibraryBuildType buildType, AndroidLibraryBuildType model, ConfigurationContainer configurations) {
        buildType.setMinifyEnabled(model.getMinify().getEnabled().get());
        linkBuildTypeDependencies(buildType, model.getDependencies(), configurations);
    }

    private void linkBuildTypeDependencies(BuildType buildType, AndroidLibraryDependencies dependencies, ConfigurationContainer configurations) {
        String name = buildType.getName();
        configurations.getByName(name + "Implementation").fromDependencyCollector(dependencies.getImplementation());
        configurations.getByName(name + "Api").fromDependencyCollector(dependencies.getApi());
        configurations.getByName(name + "CompileOnly").fromDependencyCollector(dependencies.getCompileOnly());
        configurations.getByName(name + "RuntimeOnly").fromDependencyCollector(dependencies.getRuntimeOnly());
    }
}
