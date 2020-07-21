package gov.cms.ab2d.worker.processor.domainmodel;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.util.Set;

@Data
@EqualsAndHashCode
public class ContractMapping {
    private Set<String> patients;
    private int month;
}