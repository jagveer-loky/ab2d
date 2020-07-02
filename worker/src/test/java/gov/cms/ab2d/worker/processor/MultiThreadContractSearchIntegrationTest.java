package gov.cms.ab2d.worker.processor;

import gov.cms.ab2d.bfd.client.BFDClient;
import gov.cms.ab2d.common.util.AB2DPostgresqlContainer;
import gov.cms.ab2d.worker.adapter.bluebutton.ContractBeneSearch;
import gov.cms.ab2d.worker.adapter.bluebutton.ContractBeneficiaries;
import org.hl7.fhir.dstu3.model.Bundle;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.when;

@SpringBootTest
@Testcontainers
public class MultiThreadContractSearchIntegrationTest {

    @Container
    private static final PostgreSQLContainer postgreSQLContainer= new AB2DPostgresqlContainer();

    @Autowired
    private ContractBeneSearch contractBeneSearch;

    @Mock
    private BFDClient bfdClient;

    @BeforeEach
    private void init() {
        ReflectionTestUtils.setField(contractBeneSearch, "bfdClient", bfdClient);
    }

    @Test
    public void testMultipleContract() throws ExecutionException, InterruptedException {
        String contractNo = "0001";
        Bundle.BundleEntryComponent entry1 = BundleUtils.createBundleEntry("P1");
        Bundle.BundleEntryComponent entry2 = BundleUtils.createBundleEntry("P2");
        Bundle.BundleEntryComponent entry3 = BundleUtils.createBundleEntry("P3");
        Bundle.BundleEntryComponent entry4 = BundleUtils.createBundleEntry("P4");
        Bundle.BundleEntryComponent entry5 = BundleUtils.createBundleEntry("P5");

        Bundle bundleA = BundleUtils.createBundle(entry1, entry2, entry3);
        Bundle bundleB = BundleUtils.createBundle(entry2, entry3, entry4);
        Bundle bundleC = BundleUtils.createBundle(entry1, entry3, entry5);

        when(bfdClient.requestPartDEnrolleesFromServer(contractNo, 1)).thenReturn(bundleA);
        when(bfdClient.requestPartDEnrolleesFromServer(contractNo, 2)).thenReturn(bundleB);
        when(bfdClient.requestPartDEnrolleesFromServer(contractNo, 3)).thenReturn(bundleC);
        ContractBeneficiaries beneficiaries = contractBeneSearch.getPatients(contractNo, 3);
        assertEquals(contractNo, beneficiaries.getContractNumber());
        List<ContractBeneficiaries.PatientDTO> patients = beneficiaries.getPatients();
        assertNotNull(patients);
        assertEquals(5, patients.size());

        List<ContractBeneficiaries.PatientDTO> patient1 = BundleUtils.getPatient("P1", patients);
        assertEquals(1, patient1.size());
        assertEquals(2, patient1.get(0).getDateRangesUnderContract().size());

        List<ContractBeneficiaries.PatientDTO> patient2 = BundleUtils.getPatient("P2", patients);
        assertEquals(1, patient2.size());
        assertEquals(2, patient2.get(0).getDateRangesUnderContract().size());

        List<ContractBeneficiaries.PatientDTO> patient3 = BundleUtils.getPatient("P3", patients);
        assertEquals(1, patient3.size());
        assertEquals(3, patient3.get(0).getDateRangesUnderContract().size());

        List<ContractBeneficiaries.PatientDTO> patient4 = BundleUtils.getPatient("P4", patients);
        assertEquals(1, patient4.size());
        assertEquals(1, patient4.get(0).getDateRangesUnderContract().size());

        List<ContractBeneficiaries.PatientDTO> patient5 = BundleUtils.getPatient("P5", patients);
        assertEquals(1, patient5.size());
        assertEquals(1, patient5.get(0).getDateRangesUnderContract().size());
    }
}
