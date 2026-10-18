package com.iaf.platform.providers.ports.outbound;

import com.iaf.platform.providers.domain.ReviewEvidence;
import com.iaf.platform.providers.domain.ReviewEvidence.ActionTry;
import com.iaf.platform.providers.domain.ReviewEvidence.ConnectionTest;

/**
 * What the wizard's Test connection and Try it calls proved, per provider name, for the Review
 * step and the publish to judge. The demo keeps it in memory; it is evidence, not secrets: an
 * entry holds endpoints, credential references and responses, never a credential value.
 */
public interface ReviewEvidencePort {

    void recordConnection(String provider, ConnectionTest test);

    void recordTry(String provider, ActionTry tryResult);

    /** Everything recorded for a provider name; {@link ReviewEvidence#NONE} when nothing was. */
    ReviewEvidence evidence(String provider);
}
