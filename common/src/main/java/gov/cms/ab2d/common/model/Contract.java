package gov.cms.ab2d.common.model;


import lombok.*;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.GeneratedValue;
import javax.persistence.Id;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.OneToMany;
import javax.persistence.PreRemove;
import javax.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.Set;

import static gov.cms.ab2d.common.util.DateUtil.getESTOffset;

@Entity
@Getter
@Setter
@NoArgsConstructor
@EqualsAndHashCode(onlyExplicitlyIncluded = true, callSuper = false)
public class Contract extends TimestampBase {

    public static final DateTimeFormatter FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd H:m:s Z");

    @Id
    @GeneratedValue
    @EqualsAndHashCode.Include
    private Long id;

    @Column(unique = true)
    @NotNull
    private String contractNumber;

    private String contractName;

    @Column(name = "hpms_parent_org_id")
    private Long hpmsParentOrgId;

    @Column(name = "hpms_parent_org_name")
    private String hpmsParentOrg;

    @Column(name = "hpms_org_marketing_name")
    private String hpmsOrgMarketingName;

    public Contract(@NotNull String contractNumber, String contractName, Long hpmsParentOrgId, String hpmsParentOrg,
                    String hpmsOrgMarketingName, @NotNull Sponsor sponsor) {
        this.contractNumber = contractNumber;
        this.contractName = contractName;
        this.hpmsParentOrgId = hpmsParentOrgId;
        this.hpmsParentOrg = hpmsParentOrg;
        this.hpmsOrgMarketingName = hpmsOrgMarketingName;
        this.sponsor = sponsor;
    }

    @ManyToOne
    @JoinColumn(name = "sponsor_id")
    @NotNull
    private Sponsor sponsor;

    @Column(columnDefinition = "TIMESTAMP WITH TIME ZONE")
    private OffsetDateTime attestedOn;

    @OneToMany(mappedBy = "contract")
    private Set<CoveragePeriod> coveragePeriods = new HashSet<>();

    public boolean hasAttestation() {
        return attestedOn != null;
    }

    public void clearAttestation() {
        attestedOn = null;
    }

    /*
     * Returns true if new state differs from existing which requires a save.
     */
    public boolean updateAttestation(boolean attested, String attestationDate) {
        boolean hasAttestation = hasAttestation();
        if (attested == hasAttestation) {
            return false;   // No changes needed
        }

        if (hasAttestation) {
            clearAttestation();
            return true;
        }

        String dateWithTZ = attestationDate + " " + getESTOffset();
        attestedOn = OffsetDateTime.parse(dateWithTZ, FORMATTER);
        return true;
    }

    /**
     * Trigger removal of contract from sponsor parent relationship. If this is not triggered then deleting a contract
     * will not work because hibernate persistence will recognize that {@link Sponsor#getContracts()} still has a
     * relationship to this contract instance.
     */
    @SuppressWarnings("PMD.UnusedPrivateMethod")
    @PreRemove
    private void removeContractFromSponsors() {

        if (sponsor != null) {
            sponsor.getContracts().remove(this);
        }
    }
}
