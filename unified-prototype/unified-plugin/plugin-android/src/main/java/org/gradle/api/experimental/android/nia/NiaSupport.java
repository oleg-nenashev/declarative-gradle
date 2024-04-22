package org.gradle.api.experimental.android.nia;

import com.android.build.api.dsl.*;
import org.gradle.api.Project;
import org.gradle.api.experimental.android.library.AndroidLibrary;
import org.jetbrains.annotations.NotNull;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;

import static org.gradle.api.experimental.android.library.StandaloneAndroidLibraryPlugin.TARGET_ANDROID_SDK;

public class NiaSupport {
    public static void configureNia(Project project, AndroidLibrary dslModel, LibraryExtension android) {
        setTargetSdk(android);
        android.setResourcePrefix(buildResourcePrefix(project));
        configureFlavors(android, (flavor, niaFlavor) -> {});
    }

    private static void configureFlavors(
            CommonExtension<?, ?, ?, ?, ?> commonExtension,
            BiConsumer<ProductFlavor, NiaFlavor> flavorConfigurationBlock) {
        commonExtension.getFlavorDimensions().add(FlavorDimension.contentType.name());

        Arrays.stream(NiaFlavor.values()).forEach(it -> {
            commonExtension.getProductFlavors().create(it.name(), flavor -> {
                setDimensionReflectively(flavor, it.dimension.name());
                flavorConfigurationBlock.accept(flavor, it);

                if (commonExtension instanceof ApplicationExtension && flavor instanceof ApplicationProductFlavor) {
                    if (it.applicationIdSuffix != null) {
                        ((ApplicationProductFlavor) flavor).setApplicationIdSuffix(it.applicationIdSuffix);
                    }
                }
            });
        });
    }

    /**
     * This method uses reflection to call setDimension on the ProductFlavor.
     * <p>
     * This is necessary because otherwise calling a method with a {@code String?} argument
     * from Java results in the following compile error:
     * <pre>
     * flavor.setDimension(name);
     *                       ^
     *   both method setDimension(String) in ProductFlavor and method setDimension(String) in ProductFlavor match
     * </pre>
     * @param flavor the flavor to set the dimension on
     * @param name the name of the dimension
     */
    private static void setDimensionReflectively(ProductFlavor flavor, String name) {
        Method setDimension;
        try {
            setDimension = ProductFlavor.class.getMethod("setDimension", String.class);
        } catch (Exception e) {
            throw new RuntimeException("Failed to find setDimension on flavor: " + flavor, e);
        }
        try {
            setDimension.invoke(flavor, name);
        } catch (Exception e) {
            throw new RuntimeException("Failed to call setDimension on flavor: " + flavor + " with: " + name, e);
        }
    }

    @SuppressWarnings("deprecation")
    private static void setTargetSdk(LibraryExtension android) {
        android.getDefaultConfig().setTargetSdk(TARGET_ANDROID_SDK); // Deprecated, but done in NiA
    }

    /**
     * Builds a resource prefix based on the project path.
     * <p>
     * The resource prefix is derived from the module name,
     * so resources inside ":core:module1" must be prefixed with "core_module1_".
     *
     * @param project the project to derive the resource prefix from
     * @return the computed resource prefix
     */
    private static @NotNull String buildResourcePrefix(Project project) {
        String[] parts = project.getPath().split("\\W+");
        String result = Arrays.stream(parts)
                .skip(1)  // Skipping the first element
                .distinct()  // Why? This was in the original code though
                .collect(Collectors.joining("_"))
                .toLowerCase();
        result += "_";
        return result;
    }
}
