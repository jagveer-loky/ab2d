package gov.cms.ab2d.worker.processor;

import gov.cms.ab2d.common.model.*;
import gov.cms.ab2d.common.repository.JobRepository;
import gov.cms.ab2d.eventlogger.LogManager;
import gov.cms.ab2d.common.util.FilterOutByDate;
import gov.cms.ab2d.worker.TestUtil;
import gov.cms.ab2d.worker.config.RoundRobinBlockingQueue;
import gov.cms.ab2d.worker.processor.stub.PatientClaimsProcessorStub;
import gov.cms.ab2d.worker.service.JobChannelService;
import gov.cms.ab2d.worker.service.JobChannelServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static gov.cms.ab2d.common.util.EventUtils.getOrganization;
import static gov.cms.ab2d.fhir.FhirVersion.STU3;
import static gov.cms.ab2d.worker.processor.BundleUtils.createIdentifierWithoutMbi;
import static java.lang.Boolean.TRUE;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ContractProcessorUnitTest {

    private static final String jobUuid = "6d08bf08-f926-4e19-8d89-ad67ef89f17e";

    // class under test
    private ContractProcessor cut;

    @TempDir Path efsMountTmpDir;

    @Mock private JobRepository jobRepository;
    @Mock private LogManager eventLogger;
    @Mock private RoundRobinBlockingQueue<PatientClaimsRequest> requestQueue;
    private PatientClaimsProcessor patientClaimsProcessor;
    private JobChannelService jobChannelService;

    private Path outputDir;
    private Contract contract;
    private Job job;

    @BeforeEach
    void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        patientClaimsProcessor = spy(PatientClaimsProcessorStub.class);

        JobProgressServiceImpl jobProgressImpl = new JobProgressServiceImpl(jobRepository);
        jobProgressImpl.initJob(jobUuid);
        ReflectionTestUtils.setField(jobProgressImpl, "reportProgressDbFrequency", 2);
        ReflectionTestUtils.setField(jobProgressImpl, "reportProgressLogFrequency", 3);
        jobChannelService = new JobChannelServiceImpl(jobProgressImpl);

        cut = new ContractProcessorImpl(
                jobRepository,
                patientClaimsProcessor,
                eventLogger,
                requestQueue,
                jobChannelService,
                jobProgressImpl);
        ReflectionTestUtils.setField(cut, "cancellationCheckFrequency", 2);
        ReflectionTestUtils.setField(cut, "tryLockTimeout", 30);

        PdpClient pdpClient = createClient();
        job = createJob(pdpClient);
        contract = createContract();
        job.setContract(contract);

        var outputDirPath = Paths.get(efsMountTmpDir.toString(), jobUuid);
        outputDir = Files.createDirectories(outputDirPath);
    }

    @Test
    @DisplayName("When a job is cancelled while it is being processed, then attempt to stop the job gracefully without completing it")
    void whenJobIsCancelledWhileItIsBeingProcessed_ThenAttemptToStopTheJob() {

        Map<Long, CoverageSummary> patientsByContract = createPatientsByContractResponse(contract, 3);

        jobChannelService.sendUpdate(jobUuid, JobMeasure.PATIENTS_EXPECTED, 3);
        jobChannelService.sendUpdate(jobUuid, JobMeasure.FAILURE_THRESHHOLD, 10);

        JobData jobData = new JobData(jobUuid, job.getSince(),
                getOrganization(job), patientsByContract);

        when(jobRepository.findJobStatus(anyString())).thenReturn(JobStatus.CANCELLED);

        var exceptionThrown = assertThrows(JobCancelledException.class,
                () -> cut.process(outputDir, job, jobData));

        assertTrue(exceptionThrown.getMessage().startsWith("Job was cancelled while it was being processed"));
        verify(patientClaimsProcessor, atLeast(1)).process(any());
        verify(jobRepository, atLeastOnce()).updatePercentageCompleted(anyString(), anyInt());
    }

    @Test
    @DisplayName("When many patientId are present, 'PercentageCompleted' should be updated many times")
    void whenManyPatientIdsAreProcessed_shouldUpdatePercentageCompletedMultipleTimes() {
        Map<Long, CoverageSummary> patientsByContract = createPatientsByContractResponse(contract, 18);

        jobChannelService.sendUpdate(jobUuid, JobMeasure.PATIENTS_EXPECTED, 18);
        jobChannelService.sendUpdate(jobUuid, JobMeasure.FAILURE_THRESHHOLD, 10);
        JobData jobData = new JobData(job.getJobUuid(), job.getSince(),
                getOrganization(job), patientsByContract);

        var jobOutputs = cut.process(outputDir, job, jobData);

        assertFalse(jobOutputs.isEmpty());
        verify(jobRepository, times(9)).updatePercentageCompleted(anyString(), anyInt());
        verify(patientClaimsProcessor, atLeast(1)).process(any());
    }

    @Test
    @DisplayName("When round robin blocking queue is full, patients should not be skipped")
    void whenBlockingQueueFullPatientsNotSkipped() throws InterruptedException {

        Map<Long, CoverageSummary> patientsByContract = createPatientsByContractResponse(contract, 2);

        jobChannelService.sendUpdate(jobUuid, JobMeasure.PATIENTS_EXPECTED, 2);
        jobChannelService.sendUpdate(jobUuid, JobMeasure.FAILURE_THRESHHOLD, 1);
        JobData jobData = new JobData(job.getJobUuid(), job.getSince(),
                getOrganization(job), patientsByContract);

        when(requestQueue.size(anyString())).thenReturn(1_0000_000);

        ExecutorService singleThreadedExecutor = Executors.newSingleThreadExecutor();

        Runnable testRunnable = () -> cut.process(outputDir, job, jobData);

        Future<?> future = singleThreadedExecutor.submit(testRunnable);

        Thread.sleep(5000);

        assertFalse(future.isDone());

        future.cancel(true);
    }

    private PdpClient createClient() {
        PdpClient pdpClient = new PdpClient();
        pdpClient.setClientId("Harry_Potter");
        pdpClient.setEnabled(TRUE);
        pdpClient.setContract(createContract());
        return pdpClient;
    }

    private Contract createContract() {
        Contract contract = new Contract();
        contract.setContractName("CONTRACT_NM_00000");
        contract.setContractNumber("CONTRACT_00000");
        contract.setAttestedOn(OffsetDateTime.now().minusDays(10));

        return contract;
    }

    private Job createJob(PdpClient pdpClient) {
        Job job = new Job();
        job.setJobUuid(jobUuid);
        job.setStatusMessage("0%");
        job.setStatus(JobStatus.IN_PROGRESS);
        job.setPdpClient(pdpClient);
        job.setFhirVersion(STU3);
        return job;
    }

    private static Map<Long, CoverageSummary> createPatientsByContractResponse(Contract contract, int num) {
        Map<Long, CoverageSummary> summaries = new HashMap<>();

        FilterOutByDate.DateRange dateRange = TestUtil.getOpenRange();
        for (long i = 0; i < num; i++) {
            CoverageSummary summary = new CoverageSummary(
                    createIdentifierWithoutMbi(i),
                    contract,
                    List.of(dateRange)
            );
            summaries.put(summary.getIdentifiers().getBeneficiaryId(), summary);
        }
        return summaries;
    }
}