package org.gradle.api.experimental.kmp;

import org.gradle.api.Action;
import org.gradle.api.experimental.common.ApplicationDependencies;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.Nested;
import org.gradle.declarative.dsl.model.annotations.Configuring;
import org.gradle.declarative.dsl.model.annotations.Restricted;

/**
 * The public DSL interface for a declarative KMP application.
 */
@Restricted
public interface KmpApplication {
    @Input
    Property<String> getLanguageVersion();

    @Nested
    ApplicationDependencies getDependencies();

    @Configuring
    default void dependencies(Action<? super ApplicationDependencies> action) {
        action.execute(getDependencies());
    }

    @Nested
    KmpApplicationTargetContainer getTargets();

    @Configuring
    default void targets(Action<? super KmpApplicationTargetContainer> action) {
        action.execute(getTargets());
    }
}
