package net.neoforged.gradle.common.attributes;

import net.neoforged.gradle.dsl.common.attributes.Side;
import org.gradle.api.attributes.AttributeDisambiguationRule;
import org.gradle.api.attributes.MultipleCandidatesDetails;

import javax.inject.Inject;

public abstract class SideDisambiguation implements AttributeDisambiguationRule<Side> {
    final Side joined;

    @Inject
    public SideDisambiguation(Side joined) {
        this.joined = joined;
    }

    @Override
    public void execute(MultipleCandidatesDetails<Side> details) {
        details.closestMatch(joined);
    }
}
