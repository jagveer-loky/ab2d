package gov.cms.ab2d.worker.processor;

import com.newrelic.api.agent.Token;
import gov.cms.ab2d.common.model.Contract;
import gov.cms.ab2d.common.model.CoverageSummary;
import gov.cms.ab2d.fhir.FhirVersion;
import lombok.AllArgsConstructor;
import lombok.Getter;

import javax.annotation.Nullable;
import java.time.OffsetDateTime;

/**
 * Request to BFD for a single patient's claims matching the provided parameters and requirements.
 */
@Getter
@AllArgsConstructor
public class PatientClaimsRequest {

    /** Identifiers associated with patient and date ranges that patient is/was enrolled in the Part D contract.
     * Used by {@link PatientClaimsCollector} to filter out claims with billable periods outside enrolled dates.
     *
     * Do not change without consulting multiple people.
     */
    private final CoverageSummary coverageSummary;

    // Datetime that contract was legally attested for
    private final OffsetDateTime attTime;

    // Optional datetime that PDP wants data for. Does not correspond to when services were conducted only
    @Nullable
    private final OffsetDateTime sinceTime;

    // Organization name of contract that is not sensitive
    private final String organization;

    // Job UUID
    private final String job;

    private final String contractNum;

    /** Dictates how date filtering is done in {@link PatientClaimsCollector}.
     *
     * Do not change without consulting multiple people.
     */
    private final Contract.ContractType contractType;

    // NR token corresponding to transaction. Calls are sampled to profile performance.
    private final Token token;

    /** Dictates what fields are removed from claims in {@link PatientClaimsCollector}
     *
     * Do not change without consulting multiple people.
     */
    private final FhirVersion version;
}
