package gov.cms.ab2d.worker.processor;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import gov.cms.ab2d.common.model.Contract;
import gov.cms.ab2d.common.model.CoveragePagingResult;
import gov.cms.ab2d.common.model.CoverageSummary;
import gov.cms.ab2d.common.model.Job;
import gov.cms.ab2d.common.repository.JobOutputRepository;
import gov.cms.ab2d.common.repository.JobRepository;
import gov.cms.ab2d.common.util.EventUtils;
import gov.cms.ab2d.eventlogger.Ab2dEnvironment;
import gov.cms.ab2d.eventlogger.LogManager;
import gov.cms.ab2d.eventlogger.events.ContractBeneSearchEvent;
import gov.cms.ab2d.eventlogger.events.FileEvent;
import gov.cms.ab2d.worker.processor.coverage.CoverageDriver;
import gov.cms.ab2d.worker.processor.coverage.CoverageDriverException;
import gov.cms.ab2d.worker.service.FileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;

import static gov.cms.ab2d.common.model.JobStatus.FAILED;
import static gov.cms.ab2d.common.model.JobStatus.SUCCESSFUL;
import static gov.cms.ab2d.common.util.EventUtils.getOrganization;
import static gov.cms.ab2d.worker.processor.StreamHelperImpl.FileOutputType.NDJSON;
import static gov.cms.ab2d.worker.processor.StreamHelperImpl.FileOutputType.ZIP;

@SuppressWarnings("PMD.TooManyStaticImports")
@Slf4j
@Service
@RequiredArgsConstructor
public class JobProcessorImpl implements JobProcessor {

    @Value("${efs.mount}")
    private String efsMount;

    @Value("${audit.files.ttl.hours}")
    private int auditFilesTTLHours;

    /**
     * Failure threshold an integer expressed as a percentage of failure tolerated in a batch
     **/
    @Value("${failure.threshold}")
    private int failureThreshold;

    private final FileService fileService;
    private final JobRepository jobRepository;
    private final JobOutputRepository jobOutputRepository;
    private final ContractProcessor contractProcessor;
    private final CoverageDriver coverageDriver;
    private final LogManager eventLogger;

    /**
     * Load the job and process it
     *
     * @param jobUuid - the job id of the job to process
     * @return the processed job
     */
    @Override
    @Transactional(propagation = Propagation.NEVER)
    public Job process(final String jobUuid) {

        // Load the job
        final Job job = jobRepository.findByJobUuid(jobUuid);
        log.info("Found job");

        // Determine the output directory based on the job id
        Path outputDirPath = null;
        try {
            outputDirPath = Paths.get(efsMount, jobUuid);
            processJob(job, outputDirPath);

        } catch (JobCancelledException e) {
            log.warn("Job: [{}] CANCELLED", jobUuid);

            if (outputDirPath != null) {
                log.info("Deleting output directory : {} ", outputDirPath.toAbsolutePath());
                deleteExistingDirectory(outputDirPath, job);
            }
        } catch (Exception e) {

            // Log exception to relevant loggers
            String contract = job.getContract() != null ? job.getContract().getContractNumber() : "empty";
            String message = String.format("Job %s failed for contract #%s because %s", jobUuid, contract, e.getMessage());
            eventLogger.logAndAlert(EventUtils.getJobChangeEvent(job, FAILED, message), Ab2dEnvironment.PROD_LIST);
            log.error("Unexpected exception executing job {}", e.getMessage());

            // Update database status
            job.setStatus(FAILED);
            job.setStatusMessage(e.getMessage());
            job.setCompletedAt(OffsetDateTime.now());
            log.info("Job: [{}] FAILED", jobUuid);
            jobRepository.save(job);
        }

        return job;
    }

    /**
     * Process in individual contract
     *
     * @param job             - the job in which the contract belongs
     * @param outputDirPath   - the location of the job output
     * @param progressTracker - the progress tracker which indicates how far the job is along
     * @throws ExecutionException   when there is an issue with searching
     * @throws InterruptedException - when the search is interrupted
     */
    void processContract(Job job, Path outputDirPath, ProgressTracker progressTracker)
            throws ExecutionException, InterruptedException {
        Contract contract = job.getContract();
        assert contract != null;
        log.info("Job [{}] - contract [{}] ", job.getJobUuid(), contract.getContractNumber());
        // Retrieve the contract beneficiaries

        Map<String, CoverageSummary> patients = processContractBenes(job, progressTracker);

        // Create a holder for the contract, writer, progress tracker and attested date
        JobData jobData = new JobData(contract, progressTracker, job.getSince(),
                getOrganization(job), patients);

        var jobOutputs = contractProcessor.process(outputDirPath, jobData);

        // For each job output, add to the job and save the result
        jobOutputs.forEach(job::addJobOutput);
        jobOutputRepository.saveAll(jobOutputs);

        eventLogger.log(new ContractBeneSearchEvent(getOrganization(job),
                job.getJobUuid(),
                contract.getContractNumber(),
                progressTracker.getExpectedBeneficiaries(),
                progressTracker.getPatientRequestProcessedCount(),
                progressTracker.getFailureCount()));
    }

    Map<String, CoverageSummary> processContractBenes(Job job, ProgressTracker progressTracker) {
        Contract contract = job.getContract();
        assert contract != null;
        try {
            int numBenes = coverageDriver.numberOfBeneficiariesToProcess(job);
            progressTracker.setExpectedBeneficiaries(numBenes);
            Map<String, CoverageSummary> retMap = new HashMap<>(numBenes);

            CoveragePagingResult result = coverageDriver.pageCoverage(job);
            addPatients(result, progressTracker, retMap);

            while (result.getNextRequest().isPresent()) {
                result = coverageDriver.pageCoverage(result.getNextRequest().get());
                addPatients(result, progressTracker, retMap);
            }

            int progress = progressTracker.getPercentageCompleted();
            job.setProgress(progress);
            job.setStatusMessage(progress + "% complete");
            jobRepository.save(job);
            return retMap;
        } catch (CoverageDriverException ex) {
            log.error("Having issue retrieving patients for contract " + contract.getContractNumber());
            throw ex;
        }
    }

    private void addPatients(CoveragePagingResult result, ProgressTracker progressTracker, Map<String, CoverageSummary> beneMap) {
        progressTracker.addPatients(result.getCoverageSummaries().size());
        result.getCoverageSummaries().forEach(summary -> beneMap.put(summary.getIdentifiers().getBeneficiaryId(), summary));
    }

    /**
     * Process the Job and put the contents into the output directory
     *
     * @param job           - the job to process
     * @param outputDirPath - the output directory to put all the files
     */
    @SuppressFBWarnings("REC_CATCH_EXCEPTION")
    private void processJob(Job job, Path outputDirPath) throws ExecutionException, InterruptedException {
        // Create the output directory
        createOutputDirectory(outputDirPath, job);

        // Retrieve the patients for each contract and start a progress tracker
        ProgressTracker progressTracker = ProgressTracker.builder()
                .jobUuid(job.getJobUuid())
                .failureThreshold(failureThreshold)
                .build();

        try {
            processContract(job, outputDirPath, progressTracker);
        } catch (ExecutionException | InterruptedException ex) {
            log.error("Having issue retrieving patients for contract " + job.getContract());
            throw ex;
        }

        completeJob(job);
    }

    /**
     * Given a path to a directory, create it. If it already exists, delete it and its contents and recreate it
     *
     * @param outputDirPath - the path to the output directory you want to create
     */
    private void createOutputDirectory(Path outputDirPath, Job job) {
        Path directory;
        try {
            directory = fileService.createDirectory(outputDirPath);
        } catch (UncheckedIOException e) {
            final IOException cause = e.getCause();
            if (cause != null && cause.getMessage().equalsIgnoreCase("Directory already exists")) {
                log.warn("Directory already exists. Delete and create afresh ...");
                deleteExistingDirectory(outputDirPath, job);
                directory = fileService.createDirectory(outputDirPath);
            } else {
                throw e;
            }
        }

        log.info("Created job output directory: {}", directory.toAbsolutePath());
    }

    /**
     * Delete directory with all the ndjson files or zip files. If the files are directories or symbolic links, write
     * error, but continue. If it's a regular file, delete it, then delete the directory. If the directory is not
     * empty, throws an exception (if it has files other than ndjson or zip in it)
     *
     * @param outputDirPath - the directory to delete
     */
    @SuppressFBWarnings
    private void deleteExistingDirectory(Path outputDirPath, Job job) {
        final File[] files = outputDirPath.toFile().listFiles(getFilenameFilter());

        assert files != null;
        for (File file : files) {
            final Path filePath = file.toPath();
            if (file.isDirectory() || Files.isSymbolicLink(filePath)) {
                var errMsg = "File is not a regular file";
                log.error("{} - isDirectory: {}", errMsg, file.isDirectory());
                continue;
            }

            if (Files.isRegularFile(filePath)) {
                eventLogger.log(EventUtils.getFileEvent(job, filePath.toFile(), FileEvent.FileStatus.DELETE));
                doDelete(filePath);
            }
        }

        doDelete(outputDirPath);
    }

    /**
     * @return a Filename filter for ndjson and zip files
     */
    private FilenameFilter getFilenameFilter() {
        return (dir, name) -> {
            final String filename = name.toLowerCase();
            final String ndjson = NDJSON.getSuffix();
            final String zip = ZIP.getSuffix();
            return filename.endsWith(ndjson) || filename.endsWith(zip);
        };
    }

    private void doDelete(Path path) {
        try {
            Files.delete(path);
        } catch (IOException ex) {
            var errMsg = "Could not delete ";
            log.error("{} : {} ", errMsg, path.toAbsolutePath());
            throw new UncheckedIOException(errMsg + path.toFile().getName(), ex);
        }
    }

    /**
     * Set the job as complete in the database
     *
     * @param job - The job to set as complete
     */
    private void completeJob(Job job) {
        eventLogger.log(EventUtils.getJobChangeEvent(job, SUCCESSFUL, "Job Finished"));
        job.setStatus(SUCCESSFUL);
        job.setStatusMessage("100%");
        job.setProgress(100);
        job.setExpiresAt(OffsetDateTime.now().plusHours(auditFilesTTLHours));
        job.setCompletedAt(OffsetDateTime.now());

        jobRepository.save(job);
        log.info("Job: [{}] is DONE", job.getJobUuid());
    }
}
