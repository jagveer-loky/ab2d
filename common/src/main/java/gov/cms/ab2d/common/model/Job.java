package gov.cms.ab2d.common.model;

import gov.cms.ab2d.fhir.FhirVersion;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;

import javax.annotation.Nullable;
import javax.persistence.*;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Pattern;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;

import static gov.cms.ab2d.fhir.BundleUtils.EOB;
import static gov.cms.ab2d.fhir.FhirVersion.STU3;
import static javax.persistence.EnumType.STRING;

@Entity
@Getter
@Setter
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Job {

    @Id
    @GeneratedValue
    @EqualsAndHashCode.Include
    private Long id;

    @Column(unique = true)
    @NotNull
    private String jobUuid;

    @ManyToOne
    @NotNull
    @JoinColumn(name = "user_account_id")
    private PdpClient pdpClient;

    @OneToMany(
            mappedBy = "job",
            cascade = CascadeType.ALL,
            orphanRemoval = true,
            fetch = FetchType.EAGER
    )
    private List<JobOutput> jobOutputs = new ArrayList<>();

    @Column(columnDefinition = "TIMESTAMP WITH TIME ZONE")
    @NotNull
    private OffsetDateTime createdAt;

    @Column(columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private OffsetDateTime completedAt;

    private String requestUrl;

    @Enumerated(STRING)
    @NotNull
    private JobStatus status;
    private String statusMessage;
    private String outputFormat;
    private Integer progress;

    @Enumerated(STRING)
    private FhirVersion fhirVersion = STU3;

    @Column(columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private OffsetDateTime lastPollTime;

    @Column(columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private OffsetDateTime since;

    @Column(columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private OffsetDateTime expiresAt;

    @Pattern(regexp = EOB, message = "_type should be ExplanationOfBenefit")
    private String resourceTypes; // for now just limited to ExplanationOfBenefit

    // Default a job to started by a PDP and only override if necessary
    @Enumerated(STRING)
    @NotNull
    private JobStartedBy startedBy = JobStartedBy.PDP;

    @Enumerated(STRING)
    private SinceSource sinceSource;

    @ManyToOne
    @JoinColumn(name = "contract_id")
    @Nullable
    private Contract contract;

    public void addJobOutput(JobOutput jobOutput) {
        jobOutputs.add(jobOutput);
        jobOutput.setJob(this);
    }
}
