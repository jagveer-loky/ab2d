package gov.cms.ab2d.worker.quartz;

import gov.cms.ab2d.common.service.PropertiesService;
import gov.cms.ab2d.eventlogger.LogManager;
import gov.cms.ab2d.worker.processor.coverage.CoverageDriver;
import gov.cms.ab2d.worker.processor.coverage.CoverageVerificationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.springframework.scheduling.quartz.QuartzJobBean;

import java.util.List;

import static gov.cms.ab2d.eventlogger.Ab2dEnvironment.PRODUCTION;

/**
 * Verify that all coverage/enrollment cached in database meets the expected structure and business requirements.
 *
 * During coverage updates we attempt to guarantee that the coverage for each contract always ends up in a "good"
 * state.
 *
 * This quartz job is meant to detect anything we missed and alert us that we need to manually fix the state of the
 * coverage.
 *
 * The business requirements include:
 *
 *      - Contracts must have non-zero enrollment for every month except the current month
 *      - Contract must only have one copy of enrollment in the database (only results of one BFD search at a time)
 *      - For each month and year the contract is active, the enrollment associated with that month and year
 *          should be from the latest BFD search and not older searches
 *      - Contracts should not have drastic changes in enrollment numbers month to month, except for the months of
 *        December -> January when major changes typically occur
 *
 * This verification only needs to run as often as BFD receives enrollment updates and should not be run when
 * AB2D is updating the enrollment cache.
 */
@Slf4j
@RequiredArgsConstructor
@DisallowConcurrentExecution
public class CoverageCheckQuartzJob extends QuartzJobBean {

    private final LogManager logManager;
    private final CoverageDriver driver;
    private final PropertiesService propertiesService;

    @Override
    protected void executeInternal(JobExecutionContext jobExecutionContext) throws JobExecutionException {

        if (propertiesService.isInMaintenanceMode()) {
            log.info("Skipping enrollment verification because AB2D is already in maintenance mode");
        }

        try {
            driver.verifyCoverage();
        } catch (CoverageVerificationException exception) {
            log.error("coverage is invalid or not able to be verified {}", exception.getAlertMessage());

            logManager.alert("Coverage verification failed:\n" + exception.getAlertMessage(), List.of(PRODUCTION));

            throw new JobExecutionException(exception);
        } catch (Exception exception) {
            log.error("unexpected failure attempting to verify coverage", exception);

            logManager.alert("could not verify coverage due to " + exception.getClass()
                    + ":\n" + exception.getMessage(), List.of(PRODUCTION));

            throw new JobExecutionException(exception);
        }
    }
}