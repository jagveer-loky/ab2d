package gov.cms.ab2d.hpms.hmsapi;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.Set;
import javax.validation.constraints.NotNull;

/**
 * Holder class for what is returned by the HMS Organizations endpoint
 */
@AllArgsConstructor
@Data
public class HPMSOrganizations {

    @NotNull
    private Set<HPMSOrganizationInfo> orgs;
}
