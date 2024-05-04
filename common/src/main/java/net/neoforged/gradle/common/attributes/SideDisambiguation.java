package net.neoforged.gradle.common.attributes;

import net.neoforged.gradle.dsl.common.attributes.Distribution;
import org.gradle.api.attributes.AttributeDisambiguationRule;
import org.gradle.api.attributes.MultipleCandidatesDetails;

import javax.inject.Inject;

public abstract class SideDisambiguation implements AttributeDisambiguationRule<Distribution> {
    final Distribution joined;

    @Inject
    public SideDisambiguation(Distribution joined) {
        this.joined = joined;
    }

    @Override
    public void execute(MultipleCandidatesDetails<Distribution> details) {
        details.closestMatch(joined);
    }
}
