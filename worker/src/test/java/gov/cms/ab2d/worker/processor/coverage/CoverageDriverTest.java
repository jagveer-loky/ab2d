package gov.cms.ab2d.worker.processor.coverage;

import gov.cms.ab2d.bfd.client.BFDClient;
import gov.cms.ab2d.common.dto.ContractDTO;
import gov.cms.ab2d.common.dto.PdpClientDTO;
import gov.cms.ab2d.common.dto.PropertiesDTO;
import gov.cms.ab2d.common.model.*;
import gov.cms.ab2d.common.repository.*;
import gov.cms.ab2d.common.service.*;
import gov.cms.ab2d.common.util.AB2DPostgresqlContainer;
import gov.cms.ab2d.common.util.Constants;
import gov.cms.ab2d.common.util.DataSetup;
import gov.cms.ab2d.common.util.DateUtil;
import gov.cms.ab2d.fhir.IdentifierUtils;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.annotation.Nullable;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.*;

import static gov.cms.ab2d.common.util.Constants.SPONSOR_ROLE;
import static gov.cms.ab2d.common.util.DateUtil.*;
import static gov.cms.ab2d.fhir.FhirVersion.STU3;
import static java.util.Collections.singletonList;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

// Never run internal coverage processor so this coverage processor runs unimpeded
@SpringBootTest(properties = "coverage.update.initial.delay=1000000")
@Testcontainers
class CoverageDriverTest {

    private static final int PAST_MONTHS = 3;
    private static final int STALE_DAYS = 3;
    private static final int MAX_ATTEMPTS = 3;
    private static final int STUCK_HOURS = 24;

    @Container
    private static final PostgreSQLContainer postgres = new AB2DPostgresqlContainer();

    @Autowired
    private ContractRepository contractRepo;

    @Autowired
    private CoveragePeriodRepository coveragePeriodRepo;

    @Autowired
    private CoverageSearchRepository coverageSearchRepo;

    @Autowired
    private CoverageSearchEventRepository coverageSearchEventRepo;

    @Autowired
    private JobRepository jobRepo;

    @Autowired
    private CoverageService coverageService;

    @Autowired
    private PdpClientService pdpClientService;

    @Autowired
    private PropertiesService propertiesService;

    @Autowired
    private DataSetup dataSetup;

    @Autowired
    private CoverageLockWrapper searchLock;

    private Contract contract;
    private Contract contract1;
    private CoveragePeriod january;
    private CoveragePeriod february;
    private CoveragePeriod march;
    private Job job;

    private BFDClient bfdClient;

    private CoverageDriverImpl driver;
    private CoverageProcessorImpl processor;

    @BeforeEach
    void before() {

        // Set properties values in database
        addPropertiesTableValues();

        contract = dataSetup.setupContract("TST-12", AB2D_EPOCH.toOffsetDateTime());

        contract1 = dataSetup.setupContract("TST-45", AB2D_EPOCH.toOffsetDateTime());

        contractRepo.saveAndFlush(contract);

        january = dataSetup.createCoveragePeriod(contract, 1, 2020);
        february = dataSetup.createCoveragePeriod(contract, 2, 2020);
        march = dataSetup.createCoveragePeriod(contract, 3, 2020);

        PdpClientDTO contractPdpClient = createClient(contract, "TST-12", SPONSOR_ROLE);
        pdpClientService.createClient(contractPdpClient);
        dataSetup.queueForCleanup(pdpClientService.getClientById("TST-12"));

        PdpClient pdpClient = dataSetup.setupPdpClient(List.of());
        job = new Job();
        job.setContract(contract);
        job.setJobUuid("unique");
        job.setPdpClient(pdpClient);
        job.setStatus(JobStatus.SUBMITTED);
        job.setCreatedAt(OffsetDateTime.now());
        job.setFhirVersion(STU3);
        jobRepo.saveAndFlush(job);
        dataSetup.queueForCleanup(job);

        bfdClient = mock(BFDClient.class);

        ThreadPoolTaskExecutor taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setMaxPoolSize(6);
        taskExecutor.setCorePoolSize(3);
        taskExecutor.initialize();

        processor = new CoverageProcessorImpl(coverageService, bfdClient, taskExecutor, MAX_ATTEMPTS);
        driver = new CoverageDriverImpl(coverageSearchRepo, pdpClientService, coverageService, propertiesService, processor, searchLock);
    }

    @AfterEach
    void cleanup() {
        processor.shutdown();

        dataSetup.cleanup();

        PropertiesDTO engagement = new PropertiesDTO();
        engagement.setKey(Constants.WORKER_ENGAGEMENT);
        engagement.setValue(FeatureEngagement.IN_GEAR.getSerialValue());

        PropertiesDTO override = new PropertiesDTO();
        override.setKey(Constants.COVERAGE_SEARCH_OVERRIDE);
        override.setValue("false");
        propertiesService.updateProperties(List.of(engagement, override));
    }

    private void addPropertiesTableValues() {
        List<PropertiesDTO> propertiesDTOS = new ArrayList<>();

        PropertiesDTO workerEngagement = new PropertiesDTO();
        workerEngagement.setKey(Constants.WORKER_ENGAGEMENT);
        workerEngagement.setValue(FeatureEngagement.NEUTRAL.getSerialValue());
        propertiesDTOS.add(workerEngagement);

        PropertiesDTO pastMonths = new PropertiesDTO();
        pastMonths.setKey(Constants.COVERAGE_SEARCH_UPDATE_MONTHS);
        pastMonths.setValue("" + PAST_MONTHS);
        propertiesDTOS.add(pastMonths);

        PropertiesDTO stuckHours = new PropertiesDTO();
        stuckHours.setKey(Constants.COVERAGE_SEARCH_STUCK_HOURS);
        stuckHours.setValue("" + STUCK_HOURS);
        propertiesDTOS.add(stuckHours);

        propertiesService.updateProperties(propertiesDTOS);
    }

    @DisplayName("Loading coverage periods")
    @Test
    void discoverCoveragePeriods() {

        Contract attestedAfterEpoch = dataSetup.setupContract("TST-AFTER-EPOCH",
                AB2D_EPOCH.toOffsetDateTime().plusMonths(3));
        contractRepo.saveAndFlush(attestedAfterEpoch);

        PdpClientDTO attestedAfterClient = createClient(attestedAfterEpoch, "TST-AFTER-EPOCH", SPONSOR_ROLE);
        pdpClientService.createClient(attestedAfterClient);
        dataSetup.queueForCleanup(pdpClientService.getClientById("TST-AFTER-EPOCH"));

        Contract attestedBeforeEpoch = dataSetup.setupContract("TST-BEFORE-EPOCH",
                AB2D_EPOCH.toOffsetDateTime().minusNanos(1));
        contractRepo.saveAndFlush(attestedBeforeEpoch);

        PdpClientDTO attestedBeforeClient = createClient(attestedBeforeEpoch, "TST-BEFORE-EPOCH", SPONSOR_ROLE);
        pdpClientService.createClient(attestedBeforeClient);
        dataSetup.queueForCleanup(pdpClientService.getClientById("TST-BEFORE-EPOCH"));

        long months = ChronoUnit.MONTHS.between(AB2D_EPOCH.toOffsetDateTime(), OffsetDateTime.now());
        long expectedNumPeriods = months + 1;

        try {
            driver.discoverCoveragePeriods();
        } catch (CoverageDriverException | InterruptedException exception) {
            fail("could not queue periods due to driver exception", exception);
        }

        List<CoveragePeriod> periods = coveragePeriodRepo.findAllByContractId(contract.getId());
        assertFalse(periods.isEmpty());
        assertEquals(expectedNumPeriods, periods.size());

        periods = coveragePeriodRepo.findAllByContractId(attestedAfterEpoch.getId());
        assertFalse(periods.isEmpty());
        assertEquals(expectedNumPeriods - 3, periods.size());

        periods = coveragePeriodRepo.findAllByContractId(attestedBeforeEpoch.getId());
        assertFalse(periods.isEmpty());
        assertEquals(expectedNumPeriods, periods.size());

    }

    @DisplayName("Ignore contracts marked test")
    @Test
    void discoverCoveragePeriodsIgnoresTestContracts() {

        Contract testContract = dataSetup.setupContract("TST-AFTER-EPOCH",
                AB2D_EPOCH.toOffsetDateTime().plusMonths(3));
        testContract.setUpdateMode(Contract.UpdateMode.NONE);

        contractRepo.saveAndFlush(testContract);

        try {
            driver.discoverCoveragePeriods();
        } catch (CoverageDriverException | InterruptedException exception) {
            fail("could not queue periods due to driver exception", exception);
        }
        List<CoveragePeriod> periods = coveragePeriodRepo.findAllByContractId(testContract.getId());
        assertTrue(periods.isEmpty());
    }

    @DisplayName("Queue stale coverage find never searched")
    @Test
    void queueStaleCoverageNeverSearched() {

        january.setStatus(null);
        coveragePeriodRepo.saveAndFlush(january);

        february.setStatus(null);
        coveragePeriodRepo.saveAndFlush(february);

        march.setStatus(null);
        coveragePeriodRepo.saveAndFlush(march);

        assertDoesNotThrow(() -> driver.queueStaleCoveragePeriods(), "could not queue periods due to driver exception");

        assertEquals(3, coverageSearchRepo.findAll().size());

        coverageSearchRepo.deleteAll();

        january.setStatus(JobStatus.SUCCESSFUL);
        january.setLastSuccessfulJob(OffsetDateTime.now());
        coveragePeriodRepo.saveAndFlush(january);

        createEvent(january, JobStatus.SUCCESSFUL, OffsetDateTime.now());

        february.setStatus(null);
        coveragePeriodRepo.saveAndFlush(february);

        march.setStatus(null);
        coveragePeriodRepo.saveAndFlush(march);

        assertDoesNotThrow(() -> driver.queueStaleCoveragePeriods(), "could not queue periods due to driver exception");

        assertEquals(2, coverageSearchRepo.findAll().size());
    }

    @DisplayName("Queue stale coverage find never successful")
    @Test
    void queueStaleCoverageNeverSuccessful() {

        january.setStatus(JobStatus.CANCELLED);
        coveragePeriodRepo.saveAndFlush(january);

        february.setStatus(JobStatus.FAILED);
        coveragePeriodRepo.saveAndFlush(february);

        march.setStatus(null);
        coveragePeriodRepo.saveAndFlush(march);

        createEvent(january, JobStatus.CANCELLED, OffsetDateTime.now());
        createEvent(february, JobStatus.FAILED, OffsetDateTime.now());

        assertDoesNotThrow(() -> driver.queueStaleCoveragePeriods(), "could not queue periods due to driver exception");

        assertEquals(3, coverageSearchRepo.findAll().size());
    }

    @DisplayName("Queue stale coverages ignores coverage periods with last successful search after a boundary in time")
    @Test
    void queueStaleCoverageTimeRanges() {

        coveragePeriodRepo.deleteAll();

        OffsetDateTime currentDate = OffsetDateTime.now(DateUtil.AB2D_ZONE);
        OffsetDateTime previousSunday = currentDate.truncatedTo(ChronoUnit.DAYS)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY)).plusSeconds(1);

        OffsetDateTime oneMonthAgo = currentDate.minusMonths(1);
        OffsetDateTime twoMonthsAgo = currentDate.minusMonths(2);

        CoveragePeriod currentMonth = dataSetup.createCoveragePeriod(contract, currentDate.getMonthValue(), currentDate.getYear());
        currentMonth.setStatus(JobStatus.SUCCESSFUL);
        currentMonth.setLastSuccessfulJob(previousSunday);
        coveragePeriodRepo.saveAndFlush(currentMonth);

        CoveragePeriod oneMonth = dataSetup.createCoveragePeriod(contract, oneMonthAgo.getMonthValue(), oneMonthAgo.getYear());
        oneMonth.setStatus(JobStatus.SUCCESSFUL);
        oneMonth.setLastSuccessfulJob(previousSunday);
        coveragePeriodRepo.saveAndFlush(oneMonth);

        CoveragePeriod twoMonth = dataSetup.createCoveragePeriod(contract, twoMonthsAgo.getMonthValue(), twoMonthsAgo.getYear());
        twoMonth.setStatus(JobStatus.SUCCESSFUL);
        twoMonth.setLastSuccessfulJob(previousSunday);
        coveragePeriodRepo.saveAndFlush(twoMonth);

        createEvent(currentMonth, JobStatus.SUCCESSFUL, previousSunday);
        createEvent(oneMonth, JobStatus.SUCCESSFUL, previousSunday);
        createEvent(twoMonth, JobStatus.SUCCESSFUL, previousSunday);

        assertDoesNotThrow(() -> driver.queueStaleCoveragePeriods(), "could not queue periods due to driver exception");

        assertEquals(0, coverageSearchRepo.findAll().size());
    }

    @DisplayName("Queue stale coverages ignores coverage periods with last successful search before a boundary in time")
    @Test
    void queueStaleCoverageOverrideRecentlySearched() {

        coveragePeriodRepo.deleteAll();

        PropertiesDTO override = new PropertiesDTO();
        override.setKey(Constants.COVERAGE_SEARCH_OVERRIDE);
        override.setValue("true");
        propertiesService.updateProperties(singletonList(override));

        OffsetDateTime currentDate = OffsetDateTime.now(DateUtil.AB2D_ZONE);
        OffsetDateTime previousSunday = currentDate
                .truncatedTo(ChronoUnit.DAYS)
                .with(TemporalAdjusters.previous(DayOfWeek.SUNDAY)).plusSeconds(1);

        OffsetDateTime oneMonthAgo = currentDate.minusMonths(1);
        OffsetDateTime twoMonthsAgo = currentDate.minusMonths(2);

        CoveragePeriod currentMonth = dataSetup.createCoveragePeriod(contract, currentDate.getMonthValue(), currentDate.getYear());
        currentMonth.setStatus(JobStatus.SUCCESSFUL);
        currentMonth.setLastSuccessfulJob(previousSunday);
        coveragePeriodRepo.saveAndFlush(currentMonth);

        CoveragePeriod oneMonth = dataSetup.createCoveragePeriod(contract, oneMonthAgo.getMonthValue(), oneMonthAgo.getYear());
        oneMonth.setStatus(JobStatus.SUCCESSFUL);
        oneMonth.setLastSuccessfulJob(previousSunday);
        coveragePeriodRepo.saveAndFlush(oneMonth);

        CoveragePeriod twoMonth = dataSetup.createCoveragePeriod(contract, twoMonthsAgo.getMonthValue(), twoMonthsAgo.getYear());
        twoMonth.setStatus(JobStatus.SUCCESSFUL);
        twoMonth.setLastSuccessfulJob(previousSunday);
        coveragePeriodRepo.saveAndFlush(twoMonth);

        createEvent(currentMonth, JobStatus.SUCCESSFUL, previousSunday);
        createEvent(oneMonth, JobStatus.SUCCESSFUL, previousSunday);
        createEvent(twoMonth, JobStatus.SUCCESSFUL, previousSunday);

        assertDoesNotThrow(() -> driver.queueStaleCoveragePeriods(), "could not queue periods due to driver exception");

        assertEquals(3, coverageSearchRepo.findAll().size());
    }

    @DisplayName("Queue stale coverages ignores coverage periods belonging to old months")
    @Test
    void queueStaleCoverageIgnoresOldMonths() {

        coveragePeriodRepo.deleteAll();

        OffsetDateTime currentDate = OffsetDateTime.now(DateUtil.AB2D_ZONE);
        OffsetDateTime previousSaturday = currentDate
                .truncatedTo(ChronoUnit.DAYS)
                .with(TemporalAdjusters.previousOrSame(DayOfWeek.SUNDAY))
                .minusSeconds(1);

        OffsetDateTime oneMonthAgo = currentDate.minusMonths(1);
        OffsetDateTime twoMonthsAgo = currentDate.minusMonths(2);
        OffsetDateTime threeMonthsAgo = currentDate.minusMonths(3);

        CoveragePeriod currentMonth = dataSetup.createCoveragePeriod(contract, currentDate.getMonthValue(), currentDate.getYear());
        currentMonth.setStatus(JobStatus.SUCCESSFUL);
        currentMonth.setLastSuccessfulJob(previousSaturday);
        coveragePeriodRepo.saveAndFlush(currentMonth);

        CoveragePeriod oneMonth = dataSetup.createCoveragePeriod(contract, oneMonthAgo.getMonthValue(), oneMonthAgo.getYear());
        oneMonth.setStatus(JobStatus.SUCCESSFUL);
        oneMonth.setLastSuccessfulJob(previousSaturday);
        coveragePeriodRepo.saveAndFlush(oneMonth);

        CoveragePeriod twoMonth = dataSetup.createCoveragePeriod(contract, twoMonthsAgo.getMonthValue(), twoMonthsAgo.getYear());
        twoMonth.setStatus(JobStatus.SUCCESSFUL);
        twoMonth.setLastSuccessfulJob(previousSaturday);
        coveragePeriodRepo.saveAndFlush(twoMonth);

        CoveragePeriod threeMonth = dataSetup.createCoveragePeriod(contract, threeMonthsAgo.getMonthValue(), threeMonthsAgo.getYear());
        threeMonth.setStatus(JobStatus.SUCCESSFUL);
        threeMonth.setLastSuccessfulJob(previousSaturday);
        coveragePeriodRepo.saveAndFlush(threeMonth);

        createEvent(currentMonth, JobStatus.SUCCESSFUL, previousSaturday);
        createEvent(oneMonth, JobStatus.SUCCESSFUL, previousSaturday);
        createEvent(twoMonth, JobStatus.SUCCESSFUL, previousSaturday);
        createEvent(threeMonth, JobStatus.SUCCESSFUL, previousSaturday);

        assertDoesNotThrow(() -> driver.queueStaleCoveragePeriods(), "could not queue periods due to driver exception");

        // Only three because we ignore three months ago
        assertEquals(3, coverageSearchRepo.findAll().size());
    }

    @DisplayName("Queue stale coverages finds coverage periods that got stuck in progress")
    @Test
    void queueStaleCoverageFindStuckJobs() {

        coveragePeriodRepo.deleteAll();

        OffsetDateTime currentDate = OffsetDateTime.now(DateUtil.AB2D_ZONE);

        CoveragePeriod currentMonth = dataSetup.createCoveragePeriod(contract, currentDate.getMonthValue(), currentDate.getYear());
        currentMonth.setStatus(JobStatus.IN_PROGRESS);
        currentMonth.setLastSuccessfulJob(currentDate.minusDays(STALE_DAYS + 1));
        coveragePeriodRepo.saveAndFlush(currentMonth);

        createEvent(currentMonth, JobStatus.SUCCESSFUL, currentDate.minusDays(STALE_DAYS + 1));
        createEvent(currentMonth, JobStatus.IN_PROGRESS, currentDate.minusDays(1).minusMinutes(1));

        assertDoesNotThrow(() -> driver.queueStaleCoveragePeriods(), "could not queue periods due to driver exception");

        assertEquals(1, coverageSearchRepo.findAll().size());

        assertTrue(coverageSearchEventRepo.findAll().stream().anyMatch(event -> event.getNewStatus() == JobStatus.FAILED));

        assertEquals(JobStatus.SUBMITTED, coveragePeriodRepo.findById(currentMonth.getId()).get().getStatus());
    }

    @DisplayName("Queue stale coverages ignore coverage periods with non-stuck submitted or in progress jobs")
    @Test
    void queueStaleCoverageIgnoreSubmittedOrInProgress() {

        coveragePeriodRepo.deleteAll();

        // Test whether queue stale coverage ignores regular in progress jobs

        OffsetDateTime currentDate = OffsetDateTime.now(DateUtil.AB2D_ZONE);
        OffsetDateTime previousSaturday = currentDate
                .truncatedTo(ChronoUnit.DAYS)
                .with(TemporalAdjusters.previous(DayOfWeek.SUNDAY)).minusSeconds(1);

        CoveragePeriod currentMonth = dataSetup.createCoveragePeriod(contract, currentDate.getMonthValue(), currentDate.getYear());
        currentMonth.setStatus(JobStatus.IN_PROGRESS);
        currentMonth.setLastSuccessfulJob(previousSaturday);
        coveragePeriodRepo.saveAndFlush(currentMonth);

        createEvent(currentMonth, JobStatus.SUCCESSFUL, previousSaturday);
        createEvent(currentMonth, JobStatus.IN_PROGRESS, previousSaturday.minusMinutes(1));

        assertDoesNotThrow(() -> driver.queueStaleCoveragePeriods(), "could not queue periods due to driver exception");

        assertEquals(0, coverageSearchRepo.findAll().size());

        assertEquals(JobStatus.IN_PROGRESS, coveragePeriodRepo.findById(currentMonth.getId()).get().getStatus());

        coverageSearchEventRepo.deleteAll();

        // Test whether an already submitted job is queued

        currentMonth.setStatus(JobStatus.SUBMITTED);
        coveragePeriodRepo.saveAndFlush(currentMonth);

        createEvent(currentMonth, JobStatus.SUCCESSFUL, previousSaturday);
        createEvent(currentMonth, JobStatus.SUBMITTED, previousSaturday.minusMinutes(1));

        assertDoesNotThrow(() -> driver.queueStaleCoveragePeriods(), "could not queue periods due to driver exception");

        assertEquals(0, coverageSearchRepo.findAll().size());
        assertEquals(JobStatus.SUBMITTED, coveragePeriodRepo.findById(currentMonth.getId()).get().getStatus());
    }

    @DisplayName("Normal workflow functions")
    @Test
    void normalExecution() {

        org.hl7.fhir.dstu3.model.Bundle bundle1 = buildBundle(0, 10);
        bundle1.setLink(singletonList(new org.hl7.fhir.dstu3.model.Bundle.BundleLinkComponent().setRelation(org.hl7.fhir.dstu3.model.Bundle.LINK_NEXT)));

        org.hl7.fhir.dstu3.model.Bundle bundle2 = buildBundle(10, 20);

        when(bfdClient.requestPartDEnrolleesFromServer(eq(STU3), anyString(), anyInt(), anyInt())).thenReturn(bundle1);
        when(bfdClient.requestNextBundleFromServer(eq(STU3), any(org.hl7.fhir.dstu3.model.Bundle.class))).thenReturn(bundle2);

        processor.queueCoveragePeriod(january, false);
        JobStatus status = coverageService.getSearchStatus(january.getId());
        assertEquals(JobStatus.SUBMITTED, status);

        driver.loadMappingJob();
        status = coverageService.getSearchStatus(january.getId());
        assertEquals(JobStatus.IN_PROGRESS, status);

        sleep(1000);

        processor.monitorMappingJobs();
        status = coverageService.getSearchStatus(january.getId());
        assertEquals(JobStatus.IN_PROGRESS, status);

        processor.insertJobResults();
        status = coverageService.getSearchStatus(january.getId());
        assertEquals(JobStatus.SUCCESSFUL, status);
    }

    /**
     * Verify that null is returned if there are no searches, a search there is one and verify that it
     * was deleted after it was searched.
     */
    @DisplayName("Getting another search gets and removes a coverage search specification")
    @Test
    void getNextSearchDefaultsToFirst() {
        assertTrue(driver.getNextSearch().isEmpty());

        CoverageSearch search1 = new CoverageSearch(null, january, OffsetDateTime.now(), 0);
        CoverageSearch savedSearch1 = coverageSearchRepo.save(search1);
        Optional<CoverageSearch> returnedSearch = driver.getNextSearch();

        assertEquals(savedSearch1.getPeriod().getMonth(), returnedSearch.get().getPeriod().getMonth());
        assertEquals(savedSearch1.getPeriod().getYear(), returnedSearch.get().getPeriod().getYear());
        assertTrue(driver.getNextSearch().isEmpty());
    }

    /**
     * Verify that null is returned if there are no searches, a search there is one and verify that it
     * was deleted after it was searched.
     */
    @DisplayName("Getting a search prioritizes coverage searches for already submitted eob jobs")
    @Test
    void getNextSearchPrioritizesCoverageForExistinEobJobs() {

        CoveragePeriod secondPeriod = dataSetup.createCoveragePeriod(contract1, 2, 2020);

        assertTrue(driver.getNextSearch().isEmpty());

        coverageService.submitSearch(secondPeriod.getId(), "first submitted");
        coverageService.submitSearch(january.getId(), "second submitted");

        Optional<CoverageSearch> coverageSearch = driver.getNextSearch();
        assertTrue(coverageSearch.isPresent());
        assertEquals(january, coverageSearch.get().getPeriod());

        coverageSearch = driver.getNextSearch();
        assertTrue(coverageSearch.isPresent());
        assertEquals(secondPeriod, coverageSearch.get().getPeriod());

        assertTrue(driver.getNextSearch().isEmpty());
    }

    @DisplayName("Do not start an eob job if any relevant coverage period has never had data pulled for it")
    @Test
    void availableCoverageWhenNeverSearched() {

        Job job = new Job();
        job.setContract(contract);

        try {
            boolean noCoverageStatuses = driver.isCoverageAvailable(job);

            assertFalse(noCoverageStatuses, "eob searches should not run when a" +
                    " coverage period has no information");
        } catch (InterruptedException | CoverageDriverException exception) {
            fail("could not check for available coverage", exception);
        }
    }

    @DisplayName("Do not start an eob job if any relevant coverage period is queued for an update")
    @Test
    void availableCoverageWhenPeriodSubmitted() {

        Job job = new Job();
        job.setContract(contract);
        job.setCreatedAt(OffsetDateTime.now());

        try {
            changeStatus(contract, AB2D_EPOCH.toOffsetDateTime(), JobStatus.SUBMITTED);

            // Make sure that there is a lastSuccessfulJob
            ZonedDateTime now = ZonedDateTime.now(AB2D_ZONE);
            CoveragePeriod currentMonth = coverageService.getCoveragePeriod(contract, now.getMonthValue(), now.getYear());
            currentMonth.setLastSuccessfulJob(OffsetDateTime.now().plusHours(2));
            currentMonth.setStatus(JobStatus.SUCCESSFUL);
            coveragePeriodRepo.saveAndFlush(currentMonth);

            boolean submittedCoverageStatus = driver.isCoverageAvailable(job);
            assertFalse(submittedCoverageStatus, "eob searches should not run if a " +
                    "coverage period is submitted");
        } catch (InterruptedException | CoverageDriverException exception) {
            fail("could not check for available coverage", exception);
        }
    }

    @DisplayName("Do not start an eob job if any relevant coverage period is being updated")
    @Test
    void availableCoverageWhenPeriodInProgress() {

        Job job = new Job();
        job.setContract(contract);
        job.setCreatedAt(OffsetDateTime.now());

        try {

            changeStatus(contract, AB2D_EPOCH.toOffsetDateTime(), JobStatus.IN_PROGRESS);

            // Make sure that there is a lastSuccessfulJob
            ZonedDateTime now = ZonedDateTime.now(AB2D_ZONE);
            CoveragePeriod currentMonth = coverageService.getCoveragePeriod(contract, now.getMonthValue(), now.getYear());
            currentMonth.setLastSuccessfulJob(OffsetDateTime.now().plusHours(2));
            currentMonth.setStatus(JobStatus.SUCCESSFUL);
            coveragePeriodRepo.saveAndFlush(currentMonth);

            boolean inProgressCoverageStatus = driver.isCoverageAvailable(job);
            assertFalse(inProgressCoverageStatus, "eob searches should not run when a coverage period is in progress");
        } catch (InterruptedException | CoverageDriverException exception) {
            fail("could not check for available coverage", exception);
        }
    }

    @DisplayName("Do start an eob job if all coverage periods are in progress")
    @Test
    void availableCoverageWhenAllSuccessful() {

        Job job = new Job();
        job.setContract(contract);
        job.setCreatedAt(OffsetDateTime.now());

        try {

            changeStatus(contract, AB2D_EPOCH.toOffsetDateTime(), JobStatus.SUCCESSFUL);

            // Make sure that there is a lastSuccessfulJob
            ZonedDateTime now = ZonedDateTime.now(AB2D_ZONE);
            CoveragePeriod currentMonth = coverageService.getCoveragePeriod(contract, now.getMonthValue(), now.getYear());
            currentMonth.setLastSuccessfulJob(OffsetDateTime.now().plusHours(2));
            currentMonth.setStatus(JobStatus.SUCCESSFUL);
            coveragePeriodRepo.saveAndFlush(currentMonth);

            boolean submittedCoverageStatus = driver.isCoverageAvailable(job);
            assertTrue(submittedCoverageStatus, "eob searches should not run if a " +
                    "coverage period is submitted");
        } catch (InterruptedException | CoverageDriverException exception) {
            fail("could not check for available coverage", exception);
        }
    }

    /**
     * The since date is only relevant for claims data not enrollment data. So even though the since date
     * is set the enrollment data must all be up to date before a job can start.
     */
    @DisplayName("Do not start an eob job if periods before since are being worked on. Ignore since.")
    @Test
    void availableCoverageWhenSinceContainsOnlySuccessful() {

        Job job = new Job();
        job.setCreatedAt(OffsetDateTime.now());

        Contract temp = contractRepo.findContractByContractNumber(contract.getContractNumber()).get();
        job.setContract(temp);

        OffsetDateTime since = OffsetDateTime.of(LocalDate.of(2020, 3, 1),
                LocalTime.of(0, 0, 0), AB2D_ZONE.getRules().getOffset(Instant.now()));

        try {

            changeStatus(contract, since, JobStatus.SUCCESSFUL);

            LocalDate startMonth = LocalDate.of(2020, 3, 1);
            LocalTime startDay = LocalTime.of(0,0,0);

            job.setSince(OffsetDateTime.of(startMonth, startDay, AB2D_ZONE.getRules().getOffset(Instant.now())));

            boolean inProgressBeginningMonth = driver.isCoverageAvailable(job);
            assertFalse(inProgressBeginningMonth, "eob searches should run when only month after since is successful");

            LocalDate endMonth = LocalDate.of(2020, 3, 31);
            LocalTime endDay = LocalTime.of(23,59,59);

            job.setSince(OffsetDateTime.of(endMonth, endDay, AB2D_ZONE.getRules().getOffset(Instant.now())));

            boolean inProgressEndMonth = driver.isCoverageAvailable(job);
            assertFalse(inProgressEndMonth, "eob searches should run when only month after since is successful");
        } catch (InterruptedException | CoverageDriverException exception) {
            fail("could not check for available coverage", exception);
        }
    }

    @DisplayName("Create a coverage period and a mapping job for an eob job if any periods do not exist or have never" +
            " been searched")
    @Test
    void availableCoverageDiscoversCoveragePeriodsAndQueuesThem() {

        Job job = new Job();
        job.setContract(contract);

        long numberPeriodsBeforeCheck = coveragePeriodRepo.count();

        try {

            boolean inProgressBeginningMonth = driver.isCoverageAvailable(job);
            assertFalse(inProgressBeginningMonth, "eob searches should run when only month after since is successful");

            assertTrue(numberPeriodsBeforeCheck < coveragePeriodRepo.count());

            Set<CoveragePeriod> periods = contract.getCoveragePeriods();
            periods.forEach(period -> assertEquals(JobStatus.SUBMITTED, period.getStatus()));

        } catch (InterruptedException | CoverageDriverException exception) {
            fail("could not check for available coverage", exception);
        }
    }

    @DisplayName("Number of beneficiaries to process calculation works")
    @Test
    void numberOfBeneficiariesToProcess() {

        // Override BeforeEach method settings to make this test work for a smaller period of time
        contract.setAttestedOn(OffsetDateTime.now().minus(1, ChronoUnit.SECONDS));
        contractRepo.save(contract);

        CoveragePeriod period = dataSetup.createCoveragePeriod(contract, contract.getESTAttestationTime().getMonthValue(), contract.getESTAttestationTime().getYear());

        int total = driver.numberOfBeneficiariesToProcess(job);
        assertEquals(0, total);

        CoverageSearchEvent event = new CoverageSearchEvent();
        event.setOldStatus(JobStatus.SUBMITTED);
        event.setNewStatus(JobStatus.IN_PROGRESS);
        event.setDescription("test");
        event.setCoveragePeriod(period);
        event = coverageSearchEventRepo.saveAndFlush(event);
        dataSetup.queueForCleanup(event);

        Set<Identifiers> members = new HashSet<>();
        members.add(new Identifiers(1, "1234", new LinkedHashSet<>()));
        coverageService.insertCoverage(event.getId(), members);

        total = driver.numberOfBeneficiariesToProcess(job);
        assertEquals(1, total);
    }

    private CoverageSearchEvent createEvent(CoveragePeriod period, JobStatus status, OffsetDateTime created) {
        CoverageSearchEvent event = new CoverageSearchEvent();
        event.setCoveragePeriod(period);
        event.setNewStatus(status);
        event.setCreated(created);
        event.setDescription("testing");

        event = coverageSearchEventRepo.saveAndFlush(event);
        event.setCreated(created);
        coverageSearchEventRepo.saveAndFlush(event);

        return event;
    }

    private org.hl7.fhir.dstu3.model.Bundle buildBundle(int startIndex, int endIndex) {
        org.hl7.fhir.dstu3.model.Bundle bundle1 = new org.hl7.fhir.dstu3.model.Bundle();

        for (int i = startIndex; i < endIndex; i++) {
            org.hl7.fhir.dstu3.model.Bundle.BundleEntryComponent component = new org.hl7.fhir.dstu3.model.Bundle.BundleEntryComponent();
            org.hl7.fhir.dstu3.model.Patient patient = new org.hl7.fhir.dstu3.model.Patient();

            org.hl7.fhir.dstu3.model.Identifier identifier = new org.hl7.fhir.dstu3.model.Identifier();
            identifier.setSystem(IdentifierUtils.BENEFICIARY_ID);
            identifier.setValue("test-" + i);

            patient.setIdentifier(singletonList(identifier));
            component.setResource(patient);

            bundle1.addEntry(component);
        }
        return bundle1;
    }

    private void sleep(int milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException ie) {

        }
    }

    private void changeStatus(Contract contract, OffsetDateTime attestationTime, JobStatus status) {

        OffsetDateTime now = OffsetDateTime.now();
        while (attestationTime.isBefore(now)) {
            CoveragePeriod period = coverageService.getCreateIfAbsentCoveragePeriod(contract, attestationTime.getMonthValue(), attestationTime.getYear());

            period.setStatus(status);
            if (status == JobStatus.SUCCESSFUL) {
                period.setLastSuccessfulJob(now);
            }
            coveragePeriodRepo.saveAndFlush(period);

            attestationTime = attestationTime.plusMonths(1);
        }
    }

    private PdpClientDTO createClient(Contract contract, String clientId, @Nullable String roleName) {
        PdpClientDTO client = new PdpClientDTO();
        client.setClientId(clientId);
        client.setOrganization(clientId);
        client.setEnabled(true);
        ContractDTO contractDTO = new ContractDTO(contract.getContractNumber(), contract.getContractName(),
                contract.getAttestedOn().toString());
        client.setContract(contractDTO);
        client.setRole(roleName);

        return client;
    }
}
