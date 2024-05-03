package net.neoforged.gradle.common.attributes;

import net.neoforged.gradle.dsl.common.attributes.OperatingSystem;
import org.gradle.api.attributes.AttributeDisambiguationRule;
import org.gradle.api.attributes.MultipleCandidatesDetails;

import javax.inject.Inject;

public abstract class OperatingSystemDisambiguation implements AttributeDisambiguationRule<OperatingSystem> {
    final OperatingSystem currentOperatingSystem;

    @Inject
    public OperatingSystemDisambiguation(OperatingSystem currentOperatingSystem) {
        this.currentOperatingSystem = currentOperatingSystem;
    }


    @Override
    public void execute(MultipleCandidatesDetails<OperatingSystem> details) {
        details.closestMatch(currentOperatingSystem);
    }
}
